package ae.afia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.afia.core.support.CoreIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Record -> transcribe -> draft -> review -> sign, with the guards that make it safe. */
class GoldenPathIT extends CoreIT {

    @Test
    void recordingToSignedNote() throws Exception {
        String encounterId = newEncounter(DR_A);
        assertThat(state(encounterId)).isEqualTo("CONSENT_PENDING");

        // No capture before consent.
        postJson("/api/encounters/" + encounterId + "/recordings", DR_A, "{\"patientConfirmed\":true}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("consent_required"));
        consent(encounterId, DR_A, "GRANTED").andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.state").value("READY"))
                .andExpect(jsonPath("$.consent.decision").value("GRANTED"));

        // The clinician must confirm the patient before capture; one active recording only.
        postJson("/api/encounters/" + encounterId + "/recordings", DR_A, "{\"patientConfirmed\":false}")
                .andExpect(status().isBadRequest());
        String recordingId = startRecording(encounterId, DR_A);
        postJson("/api/encounters/" + encounterId + "/recordings", DR_A, "{\"patientConfirmed\":true}")
                .andExpect(status().isConflict());

        // An interrupted session can find its recording again; another clinician or tenant cannot.
        mvc.perform(get("/api/encounters/{id}/recordings/active", encounterId).with(DR_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordingId));
        mvc.perform(get("/api/encounters/{id}/recordings/active", encounterId).with(DR_A2))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/encounters/{id}/recordings/active", encounterId).with(DR_B))
                .andExpect(status().isNotFound());

        upload(encounterId, recordingId, DR_A, "synthetic-audio".getBytes()).andExpect(status().isOk());
        assertThat(state(encounterId)).isEqualTo("PROCESSING");
        mvc.perform(get("/api/encounters/{id}/recordings/active", encounterId).with(DR_A))
                .andExpect(status().isNotFound());
        assertThat(store.objects).containsKey("afia-media/tenant-a/" + encounterId + "/" + recordingId);

        // The event is stored with the state change and carries references, not content.
        String payload = jdbc.sql("""
                        SELECT payload FROM platform.outbox WHERE aggregate_id = :id AND event_type = 'RecordingCompleted'
                        """).param("id", encounterId).query(String.class).single();
        assertThat(payload).contains("\"schemaVersion\":1", "\"tenantId\":\"tenant-a\"", "\"eventId\":",
                "\"correlationId\":", "\"objectKey\":\"tenant-a/" + encounterId + "/" + recordingId + "\"");
        assertThat(payload).doesNotContain("Synthetic Patient", "synthetic-audio");

        // Transcript and draft arrive from ambient-ai; repeats change nothing.
        submitTranscript(encounterId, recordingId, "evt-1:transcript").andExpect(status().isNoContent());
        submitTranscript(encounterId, recordingId, "evt-1:transcript").andExpect(status().isNoContent());
        submitDraft(encounterId, "evt-1:draft").andExpect(status().isNoContent());
        submitDraft(encounterId, "evt-1:draft").andExpect(status().isNoContent());
        assertThat(state(encounterId)).isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT count(*) FROM transcript.segment s JOIN transcript.transcript t "
                + "ON t.id = s.transcript_id WHERE t.encounter_id = :id")
                .param("id", UUID.fromString(encounterId)).query(Long.class).single()).isEqualTo(2);

        mvc.perform(get("/api/encounters/{id}/transcript", encounterId).with(DR_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.provenance.modelId").value("stub-asr-v1"));

        // Evidence is re-checked by core: a missing segment and no evidence are both unsupported.
        mvc.perform(get("/api/encounters/{id}/note", encounterId).with(DR_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AI_GENERATED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.provenance.promptId").value("soap-note-draft"))
                .andExpect(jsonPath("$.provenance.promptVersion").value(1))
                .andExpect(jsonPath("$.sections[0].statements[0].supported").value(true))
                .andExpect(jsonPath("$.sections[0].statements[0].evidenceSeqs[0]").value(1))
                .andExpect(jsonPath("$.sections[0].statements[1].supported").value(false))
                .andExpect(jsonPath("$.sections[3].key").value("plan"))
                .andExpect(jsonPath("$.sections[3].statements[0].supported").value(false))
                .andExpect(jsonPath("$.sections[3].statements[0].evidenceSeqs.length()").value(0));

        // No sign-off while an unsupported statement is unresolved.
        String note = "/api/encounters/" + encounterId + "/note";
        postJson(note + "/sign", DR_A, "{\"attested\":true,\"expectedVersion\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unresolved_unsupported_statements"));
        postJson(note + "/statements/review", DR_A,
                "{\"sectionKey\":\"plan\",\"ord\":1,\"action\":\"ACCEPT\",\"expectedVersion\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unsupported_requires_edit_or_reject"));

        postJson(note + "/statements/review", DR_A,
                "{\"sectionKey\":\"plan\",\"ord\":1,\"action\":\"REJECT\",\"expectedVersion\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.state").value("UNDER_REVIEW"));
        postJson(note + "/statements/review", DR_A,
                "{\"sectionKey\":\"plan\",\"ord\":1,\"action\":\"REJECT\",\"expectedVersion\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale_version"));
        postJson(note + "/statements/review", DR_A, """
                {"sectionKey":"subjective","ord":2,"action":"EDIT","text":"Patient denies drug allergies.","expectedVersion":2}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.sections[0].statements[1].origin").value("CLINICIAN"))
                .andExpect(jsonPath("$.sections[0].statements[1].status").value("EDITED"));
        postJson(note + "/statements", DR_A,
                "{\"sectionKey\":\"plan\",\"text\":\"Review in one week.\",\"expectedVersion\":3}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));

        // Attestation is required; then the note is final.
        postJson(note + "/sign", DR_A, "{\"attested\":false,\"expectedVersion\":4}").andExpect(status().isBadRequest());
        postJson(note + "/sign", DR_A, "{\"attested\":true,\"expectedVersion\":4}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SIGNED"))
                .andExpect(jsonPath("$.signedBy").value("dr-a"))
                .andExpect(jsonPath("$.signedVersion").value(4));
        assertThat(state(encounterId)).isEqualTo("SIGNED");

        // A signed note cannot be changed through the API or directly in the database.
        postJson(note + "/statements", DR_A,
                "{\"sectionKey\":\"plan\",\"text\":\"Late change.\",\"expectedVersion\":4}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_signed"));
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE note.statement SET text = 'tampered'
                WHERE note_id = (SELECT id FROM note.note WHERE encounter_id = :id)
                """).param("id", UUID.fromString(encounterId)).update()).hasMessageContaining("signed note is immutable");

        // History is kept and the first version is still the untouched AI draft.
        mvc.perform(get(note + "/versions").with(DR_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].authorKind").value("AI"))
                .andExpect(jsonPath("$[3].author").value("dr-a"));

        // Every step left evidence, the chain verifies, and the ledger refuses edits.
        List<String> actions = jdbc.sql("""
                        SELECT action FROM audit.audit_event WHERE tenant_id = 'tenant-a' AND correlation_id IS NOT NULL
                        ORDER BY seq
                        """).query(String.class).list();
        assertThat(actions).contains("ENCOUNTER_CREATED", "CONSENT_GRANTED", "RECORDING_STARTED", "RECORDING_UPLOADED",
                "TRANSCRIPT_STORED", "NOTE_DRAFT_STORED", "NOTE_VIEWED", "NOTE_STATEMENT_REJECT", "NOTE_SIGNED");
        mvc.perform(get("/api/audit/verify").with(AUDITOR_A)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit.audit_event SET actor = 'someone-else'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit.audit_event").update())
                .hasMessageContaining("append-only");

        assertThat(jdbc.sql("SELECT count(*) FROM platform.outbox WHERE event_type = 'ClinicalNoteSigned' "
                + "AND payload LIKE :like").param("like", "%" + encounterId + "%").query(Long.class).single())
                .isEqualTo(1);
    }
}
