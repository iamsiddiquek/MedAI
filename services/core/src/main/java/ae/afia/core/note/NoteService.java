package ae.afia.core.note;

import ae.afia.core.audit.AuditService;
import ae.afia.core.encounter.EncounterService;
import ae.afia.core.encounter.EncounterService.Encounter;
import ae.afia.core.platform.ApiException;
import ae.afia.core.platform.CurrentUser;
import ae.afia.core.platform.Outbox;
import ae.afia.core.platform.Provenance;
import ae.afia.core.transcript.TranscriptService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinical note lifecycle: AI_GENERATED -> UNDER_REVIEW -> SIGNED.
 *
 * AI output is a draft. Every clinician change creates a new version. Signing is refused
 * while any unsupported statement is unresolved, and a signed note is immutable.
 */
@Service
public class NoteService {

    static final Map<String, String> SECTIONS = sections();

    public record StatementIn(String text, List<Integer> evidenceSeqs, double confidence) {
    }

    public record SectionIn(String key, List<StatementIn> statements) {
    }

    public record NewDraft(String idempotencyKey, String template, List<SectionIn> sections, Provenance provenance) {
    }

    public record Statement(int ord, String text, String origin, boolean supported, double confidence, String status,
                            List<Integer> evidenceSeqs) {
    }

    public record Section(String key, String title, List<Statement> statements) {
    }

    public record NoteView(UUID id, String state, String origin, String template, int version, Provenance provenance,
                           String signedBy, String signedByName, String signedAt, Integer signedVersion,
                           List<Section> sections) {
    }

    public record VersionInfo(int version, String author, String authorKind, String summary, String createdAt) {
    }

    public record Review(String sectionKey, Integer ord, String action, String text, Integer expectedVersion) {
    }

    public record NewStatement(String sectionKey, String text, Integer expectedVersion) {
    }

    public record SignRequest(boolean attested, Integer expectedVersion) {
    }

    private record Header(UUID id, String state, String origin, String template, int version, Provenance provenance,
                          String signedBy, String signedByName, String signedAt, Integer signedVersion) {
    }

    private final JdbcClient jdbc;
    private final EncounterService encounters;
    private final TranscriptService transcripts;
    private final AuditService audit;
    private final Outbox outbox;

    public NoteService(JdbcClient jdbc, EncounterService encounters, TranscriptService transcripts,
                       AuditService audit, Outbox outbox) {
        this.jdbc = jdbc;
        this.encounters = encounters;
        this.transcripts = transcripts;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** Stores the AI draft. Idempotent by key. Evidence is re-checked here, never trusted from the caller. */
    @Transactional
    public void storeDraft(Encounter encounter, NewDraft in) {
        if (in == null || in.idempotencyKey() == null || in.idempotencyKey().isBlank() || in.sections() == null
                || in.provenance() == null || in.template() == null) {
            throw ApiException.badRequest("invalid_draft");
        }
        in.provenance().requireComplete();
        boolean seen = jdbc.sql("SELECT count(*) FROM note.note WHERE idempotency_key = :key")
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
        Set<Integer> valid = transcripts.segmentSeqs(encounter.id());
        if (valid.isEmpty()) {
            throw ApiException.conflict("transcript_required");
        }
        UUID id = UUID.randomUUID();
        Provenance p = in.provenance();
        jdbc.sql("""
                INSERT INTO note.note (id, tenant_id, encounter_id, state, origin, template, current_version,
                                       idempotency_key, execution_id, model_id, model_version, prompt_id,
                                       prompt_version, policy_version, route)
                VALUES (:id, :tenant, :encounter, 'AI_GENERATED', 'AI', :template, 1, :key, :execution, :model,
                        :modelVersion, :prompt, :promptVersion, :policy, :route)
                """)
                .param("id", id).param("tenant", encounter.tenantId()).param("encounter", encounter.id())
                .param("template", in.template()).param("key", in.idempotencyKey())
                .param("execution", p.executionId()).param("model", p.modelId())
                .param("modelVersion", p.modelVersion()).param("prompt", p.promptId())
                .param("promptVersion", p.promptVersion()).param("policy", p.policyVersion())
                .param("route", p.route()).update();
        insertVersion(id, 1, "ai:" + p.modelId(), "AI", "AI draft");
        for (SectionIn section : in.sections()) {
            if (section.key() == null || !SECTIONS.containsKey(section.key()) || section.statements() == null) {
                throw ApiException.badRequest("invalid_section");
            }
            int ord = 0;
            for (StatementIn st : section.statements()) {
                if (st.text() == null || st.text().isBlank()) {
                    throw ApiException.badRequest("invalid_statement");
                }
                List<Integer> evidence = st.evidenceSeqs() == null ? List.of()
                        : st.evidenceSeqs().stream().filter(valid::contains).distinct().sorted().toList();
                insertStatement(id, 1, section.key(), ++ord, st.text().trim(), "AI", !evidence.isEmpty(),
                        st.confidence(), "PENDING", evidence);
            }
        }
        encounters.advance(encounter, Set.of("PROCESSING"), "REVIEW");
        audit.record(encounter.tenantId(), TranscriptService.SERVICE_ACTOR, "NOTE_DRAFT_STORED", "Note", id.toString());
        outbox.record(encounter.tenantId(), "Encounter", encounter.id(), encounter.version() + 1, "DraftReady",
                Map.of("encounterId", encounter.id().toString(), "noteId", id.toString()));
    }

    /** Continuity without AI: the clinician writes the note by hand when AI is refused or unavailable. */
    @Transactional
    public NoteView createManual(CurrentUser user, UUID encounterId) {
        Encounter encounter = encounters.require(user.tenant(), encounterId);
        encounters.requireOwner(user, encounter);
        boolean aiDown = encounter.state().equals("PROCESSING") && encounter.aiStatus().equals("UNAVAILABLE");
        if (!aiDown && !Set.of("CONSENT_PENDING", "READY").contains(encounter.state())) {
            throw ApiException.conflict("invalid_state");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO note.note (id, tenant_id, encounter_id, state, origin, template, current_version)
                VALUES (:id, :tenant, :encounter, 'UNDER_REVIEW', 'MANUAL', 'soap', 1)
                """)
                .param("id", id).param("tenant", user.tenant()).param("encounter", encounterId).update();
        insertVersion(id, 1, user.subject(), "CLINICIAN", "Manual note started");
        encounters.advance(encounter, Set.of("CONSENT_PENDING", "READY", "PROCESSING"), "REVIEW");
        audit.record(user.tenant(), user.subject(), "NOTE_MANUAL_STARTED", "Note", id.toString());
        return load(user.tenant(), encounterId);
    }

    @Transactional
    public NoteView view(CurrentUser user, UUID encounterId) {
        user.requireAny("clinician", "nurse", "compliance-officer");
        encounters.require(user.tenant(), encounterId);
        NoteView note = load(user.tenant(), encounterId);
        audit.record(user.tenant(), user.subject(), "NOTE_VIEWED", "Note", note.id().toString());
        return note;
    }

    @Transactional(readOnly = true)
    public List<VersionInfo> versions(CurrentUser user, UUID encounterId) {
        user.requireAny("clinician", "nurse", "compliance-officer");
        Header header = header(user.tenant(), encounterId);
        return jdbc.sql("""
                        SELECT version, author, author_kind, summary, created_at FROM note.note_version
                        WHERE note_id = :id ORDER BY version
                        """)
                .param("id", header.id())
                .query((rs, n) -> new VersionInfo(rs.getInt("version"), rs.getString("author"),
                        rs.getString("author_kind"), rs.getString("summary"),
                        rs.getTimestamp("created_at").toInstant().toString()))
                .list();
    }

    /** ACCEPT, EDIT or REJECT one statement. An unsupported statement cannot simply be accepted. */
    @Transactional
    public NoteView review(CurrentUser user, UUID encounterId, Review in) {
        if (in == null || in.sectionKey() == null || in.ord() == null || in.expectedVersion() == null
                || !Set.of("ACCEPT", "EDIT", "REJECT").contains(in.action())) {
            throw ApiException.badRequest("invalid_review");
        }
        if (in.action().equals("EDIT") && (in.text() == null || in.text().isBlank())) {
            throw ApiException.badRequest("text_required");
        }
        Header header = editable(user, encounterId, in.expectedVersion());
        Boolean supported = jdbc.sql("""
                        SELECT supported FROM note.statement
                        WHERE note_id = :id AND version = :version AND section_key = :section AND ord = :ord
                        """)
                .param("id", header.id()).param("version", header.version()).param("section", in.sectionKey())
                .param("ord", in.ord()).query(Boolean.class).optional().orElseThrow(ApiException::notFound);
        if (in.action().equals("ACCEPT") && !supported) {
            throw ApiException.conflict("unsupported_requires_edit_or_reject");
        }
        int next = newVersion(header, user.subject(), in.action() + " " + in.sectionKey() + "#" + in.ord());
        String sql = switch (in.action()) {
            case "ACCEPT" -> "UPDATE note.statement SET status = 'ACCEPTED'";
            case "REJECT" -> "UPDATE note.statement SET status = 'REJECTED'";
            default -> "UPDATE note.statement SET status = 'EDITED', origin = 'CLINICIAN', supported = true, text = :text";
        };
        JdbcClient.StatementSpec spec = jdbc.sql(sql
                        + " WHERE note_id = :id AND version = :version AND section_key = :section AND ord = :ord")
                .param("id", header.id()).param("version", next).param("section", in.sectionKey())
                .param("ord", in.ord());
        if (in.action().equals("EDIT")) {
            spec = spec.param("text", in.text().trim());
        }
        spec.update();
        audit.record(user.tenant(), user.subject(), "NOTE_STATEMENT_" + in.action(), "Note", header.id().toString());
        return load(user.tenant(), encounterId);
    }

    @Transactional
    public NoteView addStatement(CurrentUser user, UUID encounterId, NewStatement in) {
        if (in == null || in.sectionKey() == null || !SECTIONS.containsKey(in.sectionKey()) || in.text() == null
                || in.text().isBlank() || in.expectedVersion() == null) {
            throw ApiException.badRequest("invalid_statement");
        }
        Header header = editable(user, encounterId, in.expectedVersion());
        int next = newVersion(header, user.subject(), "ADD " + in.sectionKey());
        int ord = jdbc.sql("""
                        SELECT coalesce(max(ord), 0) + 1 FROM note.statement
                        WHERE note_id = :id AND version = :version AND section_key = :section
                        """)
                .param("id", header.id()).param("version", next).param("section", in.sectionKey())
                .query(Integer.class).single();
        insertStatement(header.id(), next, in.sectionKey(), ord, in.text().trim(), "CLINICIAN", true, 1.0, "ACCEPTED",
                List.of());
        audit.record(user.tenant(), user.subject(), "NOTE_STATEMENT_ADDED", "Note", header.id().toString());
        return load(user.tenant(), encounterId);
    }

    /**
     * Clinician attestation. The audit record is written in this transaction, so a note
     * cannot become final without its evidence. Export failures later never undo this.
     */
    @Transactional
    public NoteView sign(CurrentUser user, UUID encounterId, SignRequest in) {
        if (in == null || in.expectedVersion() == null) {
            throw ApiException.badRequest("invalid_sign_request");
        }
        if (!in.attested()) {
            throw ApiException.badRequest("attestation_required");
        }
        Encounter encounter = encounters.require(user.tenant(), encounterId);
        Header header = editable(user, encounterId, in.expectedVersion());
        long unresolved = count(header, "AND supported = false AND status = 'PENDING'");
        if (unresolved > 0) {
            throw ApiException.conflict("unresolved_unsupported_statements");
        }
        if (count(header, "AND status <> 'REJECTED'") == 0) {
            throw ApiException.conflict("empty_note");
        }
        jdbc.sql("""
                UPDATE note.note SET state = 'SIGNED', signed_by = :by, signed_by_name = :name, signed_at = now(),
                                     signed_version = current_version
                WHERE id = :id AND state <> 'SIGNED'
                """)
                .param("by", user.subject()).param("name", user.name()).param("id", header.id()).update();
        encounters.advance(encounter, Set.of("REVIEW"), "SIGNED");
        audit.record(user.tenant(), user.subject(), "NOTE_SIGNED", "Note", header.id().toString());
        outbox.record(user.tenant(), "Note", header.id(), header.version(), "ClinicalNoteSigned",
                Map.of("encounterId", encounterId.toString(), "noteId", header.id().toString(),
                        "noteVersion", header.version()));
        return load(user.tenant(), encounterId);
    }

    // --------------------------------------------------------------- internals

    private Header editable(CurrentUser user, UUID encounterId, int expectedVersion) {
        Encounter encounter = encounters.require(user.tenant(), encounterId);
        encounters.requireOwner(user, encounter);
        Header header = header(user.tenant(), encounterId);
        if (header.state().equals("SIGNED")) {
            throw ApiException.conflict("already_signed");
        }
        if (header.version() != expectedVersion) {
            throw ApiException.conflict("stale_version");
        }
        return header;
    }

    /** Copy-on-write: the previous version stays intact as history. */
    private int newVersion(Header header, String author, String summary) {
        int next = header.version() + 1;
        int moved = jdbc.sql("""
                        UPDATE note.note SET current_version = :next, state = 'UNDER_REVIEW'
                        WHERE id = :id AND current_version = :current AND state <> 'SIGNED'
                        """)
                .param("next", next).param("id", header.id()).param("current", header.version()).update();
        if (moved != 1) {
            throw ApiException.conflict("stale_version");
        }
        insertVersion(header.id(), next, author, "CLINICIAN", summary);
        jdbc.sql("""
                INSERT INTO note.statement (note_id, version, section_key, ord, text, origin, supported, confidence,
                                            status, evidence_seqs)
                SELECT note_id, :next, section_key, ord, text, origin, supported, confidence, status, evidence_seqs
                FROM note.statement WHERE note_id = :id AND version = :current
                """)
                .param("next", next).param("id", header.id()).param("current", header.version()).update();
        return next;
    }

    private void insertVersion(UUID noteId, int version, String author, String kind, String summary) {
        jdbc.sql("""
                INSERT INTO note.note_version (note_id, version, author, author_kind, summary)
                VALUES (:id, :version, :author, :kind, :summary)
                """)
                .param("id", noteId).param("version", version).param("author", author).param("kind", kind)
                .param("summary", summary).update();
    }

    private void insertStatement(UUID noteId, int version, String section, int ord, String text, String origin,
                                 boolean supported, double confidence, String status, List<Integer> evidence) {
        String array = evidence.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        jdbc.sql("""
                INSERT INTO note.statement (note_id, version, section_key, ord, text, origin, supported, confidence,
                                            status, evidence_seqs)
                VALUES (:id, :version, :section, :ord, :text, :origin, :supported, :confidence, :status,
                        CAST(:evidence AS integer[]))
                """)
                .param("id", noteId).param("version", version).param("section", section).param("ord", ord)
                .param("text", text).param("origin", origin).param("supported", supported)
                .param("confidence", Math.max(0.0, Math.min(1.0, confidence))).param("status", status)
                .param("evidence", array).update();
    }

    private long count(Header header, String filter) {
        return jdbc.sql("SELECT count(*) FROM note.statement WHERE note_id = :id AND version = :version " + filter)
                .param("id", header.id()).param("version", header.version()).query(Long.class).single();
    }

    private Header header(String tenantId, UUID encounterId) {
        return jdbc.sql("""
                        SELECT id, state, origin, template, current_version, execution_id, model_id, model_version,
                               prompt_id, prompt_version, policy_version, route, signed_by, signed_by_name, signed_at,
                               signed_version
                        FROM note.note WHERE tenant_id = :tenant AND encounter_id = :encounter
                        """)
                .param("tenant", tenantId).param("encounter", encounterId)
                .query((rs, n) -> new Header(rs.getObject("id", UUID.class), rs.getString("state"),
                        rs.getString("origin"), rs.getString("template"), rs.getInt("current_version"),
                        new Provenance(rs.getString("execution_id"), rs.getString("model_id"),
                                rs.getString("model_version"), rs.getString("prompt_id"),
                                (Integer) rs.getObject("prompt_version"), rs.getString("policy_version"),
                                rs.getString("route")),
                        rs.getString("signed_by"), rs.getString("signed_by_name"),
                        rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant().toString(),
                        (Integer) rs.getObject("signed_version")))
                .optional().orElseThrow(ApiException::notFound);
    }

    private NoteView load(String tenantId, UUID encounterId) {
        Header h = header(tenantId, encounterId);
        Map<String, List<Statement>> bySection = new LinkedHashMap<>();
        SECTIONS.keySet().forEach(key -> bySection.put(key, new ArrayList<>()));
        jdbc.sql("""
                        SELECT section_key, ord, text, origin, supported, confidence, status,
                               array_to_string(evidence_seqs, ',') AS evidence
                        FROM note.statement WHERE note_id = :id AND version = :version ORDER BY section_key, ord
                        """)
                .param("id", h.id()).param("version", h.version())
                .query((rs, n) -> {
                    String evidence = rs.getString("evidence");
                    List<Integer> seqs = evidence == null || evidence.isEmpty() ? List.of()
                            : Arrays.stream(evidence.split(",")).map(Integer::valueOf).toList();
                    bySection.get(rs.getString("section_key")).add(new Statement(rs.getInt("ord"),
                            rs.getString("text"), rs.getString("origin"), rs.getBoolean("supported"),
                            rs.getDouble("confidence"), rs.getString("status"), seqs));
                    return 1;
                }).list();
        List<Section> sections = bySection.entrySet().stream()
                .map(e -> new Section(e.getKey(), SECTIONS.get(e.getKey()), e.getValue())).toList();
        return new NoteView(h.id(), h.state(), h.origin(), h.template(), h.version(), h.provenance(), h.signedBy(),
                h.signedByName(), h.signedAt(), h.signedVersion(), sections);
    }

    private static Map<String, String> sections() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("subjective", "Subjective");
        map.put("objective", "Objective");
        map.put("assessment", "Assessment");
        map.put("plan", "Plan");
        return map;
    }
}
