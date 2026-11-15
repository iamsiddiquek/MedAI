package ae.afia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.afia.core.platform.Outbox;
import ae.afia.core.support.CoreIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/** Negative paths: tenant isolation, authorisation, consent, continuity without AI, event reliability. */
class SafetyAndIsolationIT extends CoreIT {

    @Autowired
    Outbox outbox;

    @Test
    void anotherTenantCannotSeeOrChangeAnything() throws Exception {
        String encounterId = reviewEncounter(DR_A);
        String base = "/api/encounters/" + encounterId;
        mvc.perform(get(base).with(DR_B)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/transcript").with(DR_B)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/note").with(DR_B)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/note/versions").with(DR_B)).andExpect(status().isNotFound());
        consent(encounterId, DR_B, "GRANTED").andExpect(status().isNotFound());
        postJson(base + "/note/sign", DR_B, "{\"attested\":true,\"expectedVersion\":1}").andExpect(status().isNotFound());
        mvc.perform(get("/api/encounters").with(DR_B)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.encounter.id == '" + encounterId + "')]").isEmpty());
        mvc.perform(get("/api/patients").param("query", "synthetic").with(DR_B)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/audit/events").param("resourceId", encounterId)
                        .with(user("tenant-b", "auditor-b", "security-auditor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(state(encounterId)).isEqualTo("REVIEW");
    }

    @Test
    void aTenantNamedInTheRequestIsIgnored() throws Exception {
        String id = read(postJson("/api/patients?tenantId=tenant-a", DR_B, """
                {"tenantId":"tenant-a","mrn":"%s","mrnIssuer":"synthetic-hospital","fullName":"Spoof Attempt",
                 "dateOfBirth":"1990-01-01"}
                """.formatted("MRN-" + UUID.randomUUID())).andExpect(status().isCreated()), "$.id");
        assertThat(jdbc.sql("SELECT tenant_id FROM patient.patient WHERE id = :id").param("id", UUID.fromString(id))
                .query(String.class).single()).isEqualTo("tenant-b");
    }

    @Test
    void authenticationAndRolesAreEnforced() throws Exception {
        String encounterId = newEncounter(DR_A);
        mvc.perform(get("/api/encounters")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/encounters").with(user(null, "no-tenant", "clinician"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/encounters").with(AMBIENT)).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/verify").with(DR_A)).andExpect(status().isForbidden());
        mvc.perform(post("/api/encounters").with(user("tenant-a", "nurse-a", "nurse"))
                        .contentType("application/json").content("{\"patientId\":\"" + newPatient(DR_A) + "\"}"))
                .andExpect(status().isForbidden());
        // A clinician token cannot use the service API, and a missing encounter is not revealed.
        putJson("/internal/encounters/" + encounterId + "/transcript", DR_A, "{}").andExpect(status().isForbidden());
        submitTranscript(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "k").andExpect(status().isNotFound());
        mvc.perform(get("/something-else").with(DR_A)).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheEncountersOwnClinicianRecordsReviewsAndSigns() throws Exception {
        String encounterId = newEncounter(DR_A);
        consent(encounterId, DR_A, "GRANTED").andExpect(status().isOk());
        postJson("/api/encounters/" + encounterId + "/recordings", DR_A2, "{\"patientConfirmed\":true}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not_encounter_clinician"));
        String reviewId = reviewEncounter(DR_A);
        postJson("/api/encounters/" + reviewId + "/note/statements/review", DR_A2,
                "{\"sectionKey\":\"plan\",\"ord\":1,\"action\":\"REJECT\",\"expectedVersion\":1}")
                .andExpect(status().isForbidden());
        postJson("/api/encounters/" + reviewId + "/note/sign", DR_A2, "{\"attested\":true,\"expectedVersion\":1}")
                .andExpect(status().isForbidden());
    }

    @Test
    void refusedConsentBlocksCaptureAndOpensTheManualNotePath() throws Exception {
        String encounterId = newEncounter(DR_A);
        consent(encounterId, DR_A, "REFUSED").andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.state").value("CONSENT_PENDING"));
        postJson("/api/encounters/" + encounterId + "/recordings", DR_A, "{\"patientConfirmed\":true}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("consent_required"));

        String note = "/api/encounters/" + encounterId + "/note";
        postJson(note + "/manual", DR_A, "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("MANUAL"));
        postJson(note + "/sign", DR_A, "{\"attested\":true,\"expectedVersion\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("empty_note"));
        postJson(note + "/statements", DR_A,
                "{\"sectionKey\":\"assessment\",\"text\":\"Tension-type headache.\",\"expectedVersion\":1}")
                .andExpect(status().isOk());
        postJson(note + "/sign", DR_A, "{\"attested\":true,\"expectedVersion\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SIGNED")).andExpect(jsonPath("$.provenance.modelId").isEmpty());
    }

    @Test
    void consentRevokedDuringRecordingQuarantinesAudioAndStopsProcessing() throws Exception {
        String encounterId = newEncounter(DR_A);
        consent(encounterId, DR_A, "GRANTED").andExpect(status().isOk());
        String recordingId = startRecording(encounterId, DR_A);
        postJson("/api/encounters/" + encounterId + "/consent/revoke", DR_A, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.state").value("CONSENT_PENDING"))
                .andExpect(jsonPath("$.consent.decision").value("REVOKED"));
        assertThat(jdbc.sql("SELECT state FROM media.recording WHERE id = :id").param("id", UUID.fromString(recordingId))
                .query(String.class).single()).isEqualTo("QUARANTINED");
        upload(encounterId, recordingId, DR_A, "late-audio".getBytes()).andExpect(status().isConflict());
        assertThat(store.objects.keySet()).noneMatch(k -> k.contains(recordingId));
    }

    @Test
    void consentRevokedAfterUploadRejectsLateAiResults() throws Exception {
        String[] ids = processingEncounter(DR_A);
        postJson("/api/encounters/" + ids[0] + "/consent/revoke", DR_A, "{}").andExpect(status().isOk());
        submitTranscript(ids[0], ids[1], ids[0] + ":transcript").andExpect(status().isConflict());
        submitDraft(ids[0], ids[0] + ":draft").andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT count(*) FROM transcript.transcript WHERE encounter_id = :id")
                .param("id", UUID.fromString(ids[0])).query(Long.class).single()).isZero();
    }

    @Test
    void revocationAfterADraftExistsIsRefusedUntilPolicyIsDecided() throws Exception {
        String encounterId = reviewEncounter(DR_A);
        postJson("/api/encounters/" + encounterId + "/consent/revoke", DR_A, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revocation_after_draft_needs_policy"));
    }

    @Test
    void aiFailureShowsDegradedStatusAndAllowsAManualNote() throws Exception {
        String[] ids = processingEncounter(DR_A);
        postJson("/api/encounters/" + ids[0] + "/note/manual", DR_A, "{}").andExpect(status().isConflict());
        postJson("/internal/encounters/" + ids[0] + "/processing-failed", AMBIENT,
                "{\"idempotencyKey\":\"evt:failed\",\"reasonCode\":\"ai_disabled_for_tenant\"}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/encounters/{id}", ids[0]).with(DR_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.aiStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.encounter.aiStatusReason").value("ai_disabled_for_tenant"));
        postJson("/api/encounters/" + ids[0] + "/note/manual", DR_A, "{}").andExpect(status().isCreated());
        assertThat(state(ids[0])).isEqualTo("REVIEW");
    }

    @Test
    void uploadRejectsOversizedEmptyAndNonAudioContent() throws Exception {
        String encounterId = newEncounter(DR_A);
        consent(encounterId, DR_A, "GRANTED").andExpect(status().isOk());
        String recordingId = startRecording(encounterId, DR_A);
        upload(encounterId, recordingId, DR_A, new byte[2048]).andExpect(status().isPayloadTooLarge());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/encounters/{e}/recordings/{r}/audio", encounterId, recordingId).with(DR_A)
                        .contentType("application/pdf").content("%PDF".getBytes()))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(state(encounterId)).isEqualTo("RECORDING");
    }

    @Test
    void eventsCannotBeRecordedOutsideATransaction() {
        assertThatThrownBy(() -> outbox.record("tenant-a", "Encounter", UUID.randomUUID(), 1, "X", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void relayRetriesAfterBrokerFailureAndPublishesEachEventOnceInOrder() throws Exception {
        while (relay.publishPending() > 0) {
            // drain events left by other tests
        }
        bus.published.clear();
        String encounterId = newEncounter(DR_A);
        consent(encounterId, DR_A, "GRANTED").andExpect(status().isOk());

        bus.failNext = true;
        assertThat(relay.publishPending()).isZero();
        assertThat(bus.published).isEmpty();
        assertThat(jdbc.sql("SELECT attempts FROM platform.outbox WHERE aggregate_id = :id AND event_type = 'EncounterCreated'")
                .param("id", encounterId).query(Integer.class).single()).isEqualTo(1);

        assertThat(relay.publishPending()).isEqualTo(2);
        assertThat(relay.publishPending()).isZero();
        assertThat(bus.published).hasSize(2);
        assertThat(bus.published.get(0).payload()).contains("\"eventType\":\"EncounterCreated\"");
        assertThat(bus.published.get(1).payload()).contains("\"eventType\":\"ConsentGranted\"");
        assertThat(bus.published.get(0).key()).isEqualTo("tenant-a:" + encounterId);
        assertThat(bus.published.get(0).topic()).isEqualTo("afia.encounter.events.v1");
    }
}
