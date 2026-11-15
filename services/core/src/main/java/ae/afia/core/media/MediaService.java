package ae.afia.core.media;

import ae.afia.core.audit.AuditService;
import ae.afia.core.encounter.EncounterService;
import ae.afia.core.encounter.EncounterService.ConsentRevoked;
import ae.afia.core.encounter.EncounterService.Encounter;
import ae.afia.core.platform.ApiException;
import ae.afia.core.platform.CurrentUser;
import ae.afia.core.platform.Outbox;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Recording sessions. Capture is allowed only inside a consented encounter, by its own clinician. */
@Service
public class MediaService {

    public record Recording(UUID id, String state) {
    }

    private final JdbcClient jdbc;
    private final EncounterService encounters;
    private final ObjectStore store;
    private final AuditService audit;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final String bucket;
    private final long maxBytes;

    public MediaService(JdbcClient jdbc, EncounterService encounters, ObjectStore store, AuditService audit,
                        Outbox outbox, TransactionTemplate tx,
                        @Value("${afia.storage.media-bucket}") String bucket,
                        @Value("${afia.storage.max-audio-bytes}") long maxBytes) {
        this.jdbc = jdbc;
        this.encounters = encounters;
        this.store = store;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.bucket = bucket;
        this.maxBytes = maxBytes;
    }

    @Transactional
    public Recording start(CurrentUser user, UUID encounterId, boolean patientConfirmed) {
        Encounter encounter = encounters.require(user.tenant(), encounterId);
        encounters.requireOwner(user, encounter);
        if (!patientConfirmed) {
            throw ApiException.badRequest("patient_confirmation_required");
        }
        if (!encounters.consentGranted(encounterId)) {
            throw ApiException.conflict("consent_required");
        }
        encounters.advance(encounter, Set.of("READY"), "RECORDING");
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO media.recording (id, tenant_id, encounter_id, state, started_by)
                    VALUES (:id, :tenant, :encounter, 'STARTED', :actor)
                    """)
                    .param("id", id).param("tenant", user.tenant()).param("encounter", encounterId)
                    .param("actor", user.subject()).update();
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("recording_already_active");
        }
        audit.record(user.tenant(), user.subject(), "RECORDING_STARTED", "Recording", id.toString());
        return new Recording(id, "STARTED");
    }

    /**
     * Stores the audio, then commits recording state, encounter state, audit evidence and
     * the RecordingCompleted event in one transaction. The object is written first so no
     * database transaction is held while waiting for storage; a failure after the write
     * leaves an unreferenced object for the cleanup job, never a false completion.
     */
    public Recording upload(CurrentUser user, UUID encounterId, UUID recordingId, byte[] audio, String contentType) {
        Encounter encounter = encounters.require(user.tenant(), encounterId);
        encounters.requireOwner(user, encounter);
        if (contentType == null || !contentType.toLowerCase().startsWith("audio/")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "audio_content_type_required");
        }
        if (audio == null || audio.length == 0) {
            throw ApiException.badRequest("empty_audio");
        }
        if (audio.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "audio_too_large");
        }
        requireStarted(user.tenant(), encounterId, recordingId);
        if (!encounters.consentGranted(encounterId)) {
            throw ApiException.conflict("consent_required");
        }
        String key = user.tenant() + "/" + encounterId + "/" + recordingId;
        store.put(bucket, key, audio, contentType);
        String sha256 = sha256(audio);
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            UPDATE media.recording
                            SET state = 'UPLOADED', bucket = :bucket, object_key = :key, content_type = :type,
                                size_bytes = :size, sha256 = :sha, completed_at = now()
                            WHERE id = :id AND tenant_id = :tenant AND encounter_id = :encounter AND state = 'STARTED'
                            """)
                    .param("bucket", bucket).param("key", key).param("type", contentType)
                    .param("size", (long) audio.length).param("sha", sha256).param("id", recordingId)
                    .param("tenant", user.tenant()).param("encounter", encounterId).update();
            if (updated != 1) {
                throw ApiException.conflict("recording_not_active");
            }
            encounters.advance(encounter, Set.of("RECORDING"), "PROCESSING");
            audit.record(user.tenant(), user.subject(), "RECORDING_UPLOADED", "Recording", recordingId.toString());
            outbox.record(user.tenant(), "Encounter", encounterId, encounter.version() + 1, "RecordingCompleted",
                    Map.of("encounterId", encounterId.toString(), "recordingId", recordingId.toString(),
                            "bucket", bucket, "objectKey", key, "contentType", contentType));
        });
        return new Recording(recordingId, "UPLOADED");
    }

    /** The recording still waiting for its audio, so an interrupted session can be resumed after a reload. */
    @Transactional(readOnly = true)
    public Recording active(CurrentUser user, UUID encounterId) {
        Encounter encounter = encounters.require(user.tenant(), encounterId);
        encounters.requireOwner(user, encounter);
        return jdbc.sql("""
                        SELECT id FROM media.recording
                        WHERE tenant_id = :tenant AND encounter_id = :encounter AND state = 'STARTED'
                        """)
                .param("tenant", user.tenant()).param("encounter", encounterId)
                .query(UUID.class).optional().map(id -> new Recording(id, "STARTED"))
                .orElseThrow(ApiException::notFound);
    }

    /** Runs inside the transaction that revokes consent: captured audio is quarantined, not processed. */
    @EventListener
    public void onConsentRevoked(ConsentRevoked event) {
        int quarantined = jdbc.sql("""
                        UPDATE media.recording SET state = 'QUARANTINED', completed_at = now()
                        WHERE tenant_id = :tenant AND encounter_id = :encounter AND state IN ('STARTED', 'UPLOADED')
                        """)
                .param("tenant", event.tenantId()).param("encounter", event.encounterId()).update();
        if (quarantined > 0) {
            audit.record(event.tenantId(), event.actor(), "RECORDING_QUARANTINED", "Encounter",
                    event.encounterId().toString());
        }
    }

    /** Module API: true when the recording belongs to the encounter and its audio was accepted. */
    public boolean isUploaded(String tenantId, UUID encounterId, UUID recordingId) {
        return jdbc.sql("""
                        SELECT count(*) FROM media.recording
                        WHERE id = :id AND tenant_id = :tenant AND encounter_id = :encounter AND state = 'UPLOADED'
                        """)
                .param("id", recordingId).param("tenant", tenantId).param("encounter", encounterId)
                .query(Long.class).single() == 1;
    }

    private void requireStarted(String tenantId, UUID encounterId, UUID recordingId) {
        long found = jdbc.sql("""
                        SELECT count(*) FROM media.recording
                        WHERE id = :id AND tenant_id = :tenant AND encounter_id = :encounter AND state = 'STARTED'
                        """)
                .param("id", recordingId).param("tenant", tenantId).param("encounter", encounterId)
                .query(Long.class).single();
        if (found != 1) {
            throw ApiException.conflict("recording_not_active");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
