package ae.afia.core.transcript;

import ae.afia.core.audit.AuditService;
import ae.afia.core.encounter.EncounterService;
import ae.afia.core.encounter.EncounterService.Encounter;
import ae.afia.core.media.MediaService;
import ae.afia.core.platform.ApiException;
import ae.afia.core.platform.CurrentUser;
import ae.afia.core.platform.Outbox;
import ae.afia.core.platform.Provenance;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Timestamped, speaker-attributed transcript. Derived data with recorded provenance. */
@Service
public class TranscriptService {

    public static final String SERVICE_ACTOR = "svc:ambient-ai";

    public record Segment(int seq, String speaker, String language, String text, int startMs, int endMs,
                          double confidence) {
    }

    public record NewTranscript(UUID recordingId, String idempotencyKey, List<Segment> segments,
                                Provenance provenance) {
    }

    public record TranscriptView(UUID id, List<Segment> segments, Provenance provenance, String createdAt) {
    }

    private final JdbcClient jdbc;
    private final EncounterService encounters;
    private final MediaService media;
    private final AuditService audit;
    private final Outbox outbox;

    public TranscriptService(JdbcClient jdbc, EncounterService encounters, MediaService media, AuditService audit,
                             Outbox outbox) {
        this.jdbc = jdbc;
        this.encounters = encounters;
        this.media = media;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** Idempotent: a repeated submission with the same key changes nothing. */
    @Transactional
    public void store(Encounter encounter, NewTranscript in) {
        if (in == null || in.idempotencyKey() == null || in.idempotencyKey().isBlank() || in.recordingId() == null
                || in.segments() == null || in.segments().isEmpty() || in.provenance() == null) {
            throw ApiException.badRequest("invalid_transcript");
        }
        in.provenance().requireComplete();
        boolean seen = jdbc.sql("SELECT count(*) FROM transcript.transcript WHERE idempotency_key = :key")
                .param("key", in.idempotencyKey()).query(Long.class).single() > 0;
        if (seen) {
            return;
        }
        if (!encounter.state().equals("PROCESSING")) {
            throw ApiException.conflict("invalid_state");
        }
        if (!encounters.consentGranted(encounter.id())) {
            throw ApiException.conflict("consent_required");
        }
        if (!media.isUploaded(encounter.tenantId(), encounter.id(), in.recordingId())) {
            throw ApiException.conflict("recording_not_uploaded");
        }
        Set<Integer> seqs = new HashSet<>();
        for (Segment s : in.segments()) {
            if (s.text() == null || s.speaker() == null || s.language() == null || s.endMs() < s.startMs()
                    || !seqs.add(s.seq())) {
                throw ApiException.badRequest("invalid_segment");
            }
        }
        UUID id = UUID.randomUUID();
        Provenance p = in.provenance();
        jdbc.sql("""
                INSERT INTO transcript.transcript (id, tenant_id, encounter_id, recording_id, idempotency_key,
                                                   execution_id, model_id, model_version, policy_version, route)
                VALUES (:id, :tenant, :encounter, :recording, :key, :execution, :model, :modelVersion, :policy, :route)
                """)
                .param("id", id).param("tenant", encounter.tenantId()).param("encounter", encounter.id())
                .param("recording", in.recordingId()).param("key", in.idempotencyKey())
                .param("execution", p.executionId()).param("model", p.modelId())
                .param("modelVersion", p.modelVersion()).param("policy", p.policyVersion()).param("route", p.route())
                .update();
        for (Segment s : in.segments()) {
            jdbc.sql("""
                    INSERT INTO transcript.segment (transcript_id, seq, speaker, language, text, start_ms, end_ms,
                                                    confidence)
                    VALUES (:id, :seq, :speaker, :language, :text, :start, :end, :confidence)
                    """)
                    .param("id", id).param("seq", s.seq()).param("speaker", s.speaker())
                    .param("language", s.language()).param("text", s.text()).param("start", s.startMs())
                    .param("end", s.endMs()).param("confidence", clamp(s.confidence())).update();
        }
        audit.record(encounter.tenantId(), SERVICE_ACTOR, "TRANSCRIPT_STORED", "Encounter", encounter.id().toString());
        outbox.record(encounter.tenantId(), "Encounter", encounter.id(), encounter.version(), "TranscriptCompleted",
                Map.of("encounterId", encounter.id().toString(), "transcriptId", id.toString()));
    }

    /** Reading clinical content is itself an audited access. */
    @Transactional
    public TranscriptView view(CurrentUser user, UUID encounterId) {
        user.requireAny("clinician", "nurse", "compliance-officer");
        Encounter encounter = encounters.require(user.tenant(), encounterId);
        TranscriptView header = jdbc.sql("""
                        SELECT id, execution_id, model_id, model_version, policy_version, route, created_at
                        FROM transcript.transcript WHERE tenant_id = :tenant AND encounter_id = :encounter
                        """)
                .param("tenant", user.tenant()).param("encounter", encounter.id())
                .query((rs, n) -> new TranscriptView(rs.getObject("id", UUID.class), List.of(),
                        new Provenance(rs.getString("execution_id"), rs.getString("model_id"),
                                rs.getString("model_version"), null, null, rs.getString("policy_version"),
                                rs.getString("route")),
                        rs.getTimestamp("created_at").toInstant().toString()))
                .optional().orElseThrow(ApiException::notFound);
        List<Segment> segments = jdbc.sql("""
                        SELECT seq, speaker, language, text, start_ms, end_ms, confidence
                        FROM transcript.segment WHERE transcript_id = :id ORDER BY seq
                        """)
                .param("id", header.id())
                .query((rs, n) -> new Segment(rs.getInt("seq"), rs.getString("speaker"), rs.getString("language"),
                        rs.getString("text"), rs.getInt("start_ms"), rs.getInt("end_ms"),
                        rs.getDouble("confidence")))
                .list();
        audit.record(user.tenant(), user.subject(), "TRANSCRIPT_VIEWED", "Encounter", encounterId.toString());
        return new TranscriptView(header.id(), segments, header.provenance(), header.createdAt());
    }

    /** Module API: the segment numbers a note statement may cite as evidence. */
    public Set<Integer> segmentSeqs(UUID encounterId) {
        return new HashSet<>(jdbc.sql("""
                        SELECT s.seq FROM transcript.segment s
                        JOIN transcript.transcript t ON t.id = s.transcript_id WHERE t.encounter_id = :encounter
                        """)
                .param("encounter", encounterId).query(Integer.class).list());
    }

    static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
