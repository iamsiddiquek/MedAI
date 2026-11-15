package ae.afia.core.encounter;

import ae.afia.core.audit.AuditService;
import ae.afia.core.patient.PatientService;
import ae.afia.core.patient.PatientService.Patient;
import ae.afia.core.platform.ApiException;
import ae.afia.core.platform.CurrentUser;
import ae.afia.core.platform.Outbox;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encounter lifecycle and consent. Consent lives here until its policy complexity
 * justifies a separate module. Other modules change encounter state only through
 * {@link #advance}, which refuses any transition that is not valid from the current state.
 */
@Service
public class EncounterService {

    public record Encounter(UUID id, String tenantId, UUID patientId, String clinicianId, String clinicianName,
                            String state, String aiStatus, String aiStatusReason, int version, String createdAt) {
    }

    public record Consent(String decision, String consentTextVersion, String language, String recordedBy,
                          String recordedAt) {
    }

    public record EncounterView(Encounter encounter, Patient patient, Consent consent) {
    }

    public record ConsentDecision(String decision, String consentTextVersion, String language) {
    }

    /** Published inside the revoking transaction so other modules react atomically. */
    public record ConsentRevoked(String tenantId, UUID encounterId, String actor) {
    }

    private static final RowMapper<Encounter> MAPPER = (rs, n) -> new Encounter(rs.getObject("id", UUID.class),
            rs.getString("tenant_id"), rs.getObject("patient_id", UUID.class), rs.getString("clinician_id"),
            rs.getString("clinician_name"), rs.getString("state"), rs.getString("ai_status"),
            rs.getString("ai_status_reason"), rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant().toString());
    private static final String SELECT = """
            SELECT id, tenant_id, patient_id, clinician_id, clinician_name, state, ai_status, ai_status_reason,
                   version, created_at
            FROM encounter.encounter\s""";
    private static final Set<String> LANGUAGES = Set.of("en", "ar");

    private final JdbcClient jdbc;
    private final PatientService patients;
    private final AuditService audit;
    private final Outbox outbox;
    private final ApplicationEventPublisher events;

    public EncounterService(JdbcClient jdbc, PatientService patients, AuditService audit, Outbox outbox,
                            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.audit = audit;
        this.outbox = outbox;
        this.events = events;
    }

    @Transactional
    public EncounterView create(CurrentUser user, UUID patientId) {
        user.requireAny("clinician");
        if (patientId == null) {
            throw ApiException.badRequest("patient_required");
        }
        Patient patient = patients.find(user.tenant(), patientId).orElseThrow(ApiException::notFound);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO encounter.encounter (id, tenant_id, patient_id, clinician_id, clinician_name, state)
                VALUES (:id, :tenant, :patient, :clinician, :name, 'CONSENT_PENDING')
                """)
                .param("id", id).param("tenant", user.tenant()).param("patient", patientId)
                .param("clinician", user.subject()).param("name", user.name())
                .update();
        audit.record(user.tenant(), user.subject(), "ENCOUNTER_CREATED", "Encounter", id.toString());
        outbox.record(user.tenant(), "Encounter", id, 1, "EncounterCreated", Map.of("encounterId", id.toString()));
        return new EncounterView(require(user.tenant(), id), patient, null);
    }

    /** Clinicians see their own encounters; other clinical and administrative roles see the tenant's. */
    @Transactional(readOnly = true)
    public List<EncounterView> list(CurrentUser user) {
        user.requireAny("clinician", "nurse", "tenant-admin", "compliance-officer");
        boolean ownOnly = user.roles().contains("clinician") && !user.roles().contains("tenant-admin");
        List<Encounter> rows = jdbc.sql(SELECT + """
                        WHERE tenant_id = :tenant AND (:all OR clinician_id = :clinician)
                        ORDER BY created_at DESC LIMIT 100
                        """)
                .param("tenant", user.tenant()).param("all", !ownOnly).param("clinician", user.subject())
                .query(MAPPER).list();
        Map<UUID, Patient> byId = patients.findAll(user.tenant(), rows.stream().map(Encounter::patientId).toList());
        return rows.stream().map(e -> new EncounterView(e, byId.get(e.patientId()), null)).toList();
    }

    @Transactional(readOnly = true)
    public EncounterView view(CurrentUser user, UUID id) {
        user.requireAny("clinician", "nurse", "tenant-admin", "compliance-officer");
        Encounter encounter = require(user.tenant(), id);
        Patient patient = patients.find(user.tenant(), encounter.patientId()).orElse(null);
        return new EncounterView(encounter, patient, latestConsent(id).orElse(null));
    }

    @Transactional
    public EncounterView recordConsent(CurrentUser user, UUID id, ConsentDecision in) {
        user.requireAny("clinician", "nurse");
        if (in == null || in.consentTextVersion() == null || in.consentTextVersion().isBlank()
                || !LANGUAGES.contains(in.language()) || !Set.of("GRANTED", "REFUSED").contains(in.decision())) {
            throw ApiException.badRequest("invalid_consent");
        }
        Encounter encounter = require(user.tenant(), id);
        if (!encounter.state().equals("CONSENT_PENDING")) {
            throw ApiException.conflict("invalid_state");
        }
        insertConsent(encounter, in.decision(), in.consentTextVersion(), in.language(), user.subject());
        if (in.decision().equals("GRANTED")) {
            advance(encounter, Set.of("CONSENT_PENDING"), "READY");
        }
        // Evidence is written in this transaction: if it cannot be stored, consent is not recorded.
        audit.record(user.tenant(), user.subject(), "CONSENT_" + in.decision(), "Encounter", id.toString());
        outbox.record(user.tenant(), "Encounter", id, encounter.version() + 1, "Consent" + titleCase(in.decision()),
                Map.of("encounterId", id.toString()));
        return view(user, id);
    }

    /**
     * Revocation before a draft exists stops the AI path: the encounter returns to
     * CONSENT_PENDING and the media module quarantines any audio in the same transaction.
     * What happens to an existing draft or signed note is a policy decision not yet made.
     */
    @Transactional
    public EncounterView revokeConsent(CurrentUser user, UUID id) {
        user.requireAny("clinician", "nurse");
        Encounter encounter = require(user.tenant(), id);
        if (!Set.of("READY", "RECORDING", "PROCESSING").contains(encounter.state())) {
            throw ApiException.conflict("revocation_after_draft_needs_policy");
        }
        Consent latest = latestConsent(id).orElseThrow(() -> ApiException.conflict("invalid_state"));
        insertConsent(encounter, "REVOKED", latest.consentTextVersion(), latest.language(), user.subject());
        advance(encounter, Set.of("READY", "RECORDING", "PROCESSING"), "CONSENT_PENDING");
        events.publishEvent(new ConsentRevoked(user.tenant(), id, user.subject()));
        audit.record(user.tenant(), user.subject(), "CONSENT_REVOKED", "Encounter", id.toString());
        outbox.record(user.tenant(), "Encounter", id, encounter.version() + 1, "ConsentRevoked",
                Map.of("encounterId", id.toString()));
        return view(user, id);
    }

    // ------------------------------------------------------------ module API

    public Encounter require(String tenantId, UUID id) {
        return jdbc.sql(SELECT + "WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId).param("id", id).query(MAPPER).optional()
                .orElseThrow(ApiException::notFound);
    }

    /** For internal service callbacks only: the tenant is taken from the stored encounter. */
    public Optional<Encounter> findForService(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public boolean consentGranted(UUID encounterId) {
        return latestConsent(encounterId).map(c -> c.decision().equals("GRANTED")).orElse(false);
    }

    /** Only the encounter's own clinician may record, review or sign it. */
    public void requireOwner(CurrentUser user, Encounter encounter) {
        user.requireAny("clinician");
        if (!encounter.clinicianId().equals(user.subject())) {
            throw ApiException.forbidden("not_encounter_clinician");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(Encounter encounter, Set<String> from, String to) {
        int updated = jdbc.sql("""
                        UPDATE encounter.encounter SET state = :to, version = version + 1, updated_at = now()
                        WHERE id = :id AND tenant_id = :tenant AND state IN (:from)
                        """)
                .param("to", to).param("id", encounter.id()).param("tenant", encounter.tenantId())
                .param("from", List.copyOf(from)).update();
        if (updated != 1) {
            throw ApiException.conflict("invalid_state");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setAiStatus(Encounter encounter, String status, String reason) {
        jdbc.sql("""
                UPDATE encounter.encounter SET ai_status = :status, ai_status_reason = :reason, updated_at = now()
                WHERE id = :id AND tenant_id = :tenant
                """)
                .param("status", status).param("reason", reason).param("id", encounter.id())
                .param("tenant", encounter.tenantId()).update();
    }

    private Optional<Consent> latestConsent(UUID encounterId) {
        return jdbc.sql("""
                        SELECT decision, consent_text_version, language, recorded_by, recorded_at
                        FROM encounter.consent WHERE encounter_id = :id ORDER BY recorded_at DESC, id LIMIT 1
                        """)
                .param("id", encounterId)
                .query((rs, n) -> new Consent(rs.getString("decision"), rs.getString("consent_text_version"),
                        rs.getString("language"), rs.getString("recorded_by"),
                        rs.getTimestamp("recorded_at").toInstant().toString()))
                .optional();
    }

    private void insertConsent(Encounter encounter, String decision, String textVersion, String language,
                               String actor) {
        jdbc.sql("""
                INSERT INTO encounter.consent (id, tenant_id, encounter_id, decision, consent_text_version, language,
                                               recorded_by, recorded_at)
                VALUES (:id, :tenant, :encounter, :decision, :version, :language, :actor, clock_timestamp())
                """)
                .param("id", UUID.randomUUID()).param("tenant", encounter.tenantId())
                .param("encounter", encounter.id()).param("decision", decision).param("version", textVersion)
                .param("language", language).param("actor", actor).update();
    }

    private static String titleCase(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase();
    }
}
