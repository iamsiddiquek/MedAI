package ae.afia.core.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.afia.core.platform.OutboxRelay;
import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Shared wiring: real PostgreSQL in Testcontainers, real security filter chain, synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class CoreIT {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected RecordingEventBus bus;
    @Autowired
    protected InMemoryObjectStore store;
    @Autowired
    protected OutboxRelay relay;

    @MockitoBean
    JwtDecoder jwtDecoder; // tokens are injected by the test; no identity provider is contacted

    protected static final RequestPostProcessor DR_A = user("tenant-a", "dr-a", "clinician");
    protected static final RequestPostProcessor DR_A2 = user("tenant-a", "dr-a2", "clinician");
    protected static final RequestPostProcessor DR_B = user("tenant-b", "dr-b", "clinician");
    protected static final RequestPostProcessor AUDITOR_A = user("tenant-a", "auditor-a", "security-auditor");
    protected static final RequestPostProcessor AMBIENT = user(null, "svc-ambient-ai", "svc-ambient");

    protected static RequestPostProcessor user(String tenant, String subject, String... roles) {
        return jwt().jwt(j -> {
                    j.subject(subject).claim("name", "Name of " + subject);
                    if (tenant != null) {
                        j.claim("tenant_id", tenant);
                    }
                })
                .authorities(Arrays.stream(roles)
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    protected static String read(ResultActions result, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path));
    }

    protected ResultActions postJson(String url, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions putJson(String url, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(put(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected String newPatient(RequestPostProcessor who) throws Exception {
        String mrn = "MRN-" + UUID.randomUUID();
        return read(postJson("/api/patients", who, """
                {"mrn":"%s","mrnIssuer":"synthetic-hospital","fullName":"Synthetic Patient","dateOfBirth":"1980-02-03","sex":"F"}
                """.formatted(mrn)).andExpect(status().isCreated()), "$.id");
    }

    protected String newEncounter(RequestPostProcessor who) throws Exception {
        return read(postJson("/api/encounters", who, "{\"patientId\":\"%s\"}".formatted(newPatient(who)))
                .andExpect(status().isCreated()), "$.encounter.id");
    }

    protected ResultActions consent(String encounterId, RequestPostProcessor who, String decision) throws Exception {
        return postJson("/api/encounters/" + encounterId + "/consent", who, """
                {"decision":"%s","consentTextVersion":"ambient-consent-v1","language":"en"}
                """.formatted(decision));
    }

    protected String startRecording(String encounterId, RequestPostProcessor who) throws Exception {
        return read(postJson("/api/encounters/" + encounterId + "/recordings", who, "{\"patientConfirmed\":true}")
                .andExpect(status().isCreated()), "$.id");
    }

    protected ResultActions upload(String encounterId, String recordingId, RequestPostProcessor who, byte[] audio)
            throws Exception {
        return mvc.perform(put("/api/encounters/{e}/recordings/{r}/audio", encounterId, recordingId).with(who)
                .contentType("audio/webm").content(audio));
    }

    /** Encounter with consent, a finished recording and state PROCESSING. Returns {encounterId, recordingId}. */
    protected String[] processingEncounter(RequestPostProcessor who) throws Exception {
        String encounterId = newEncounter(who);
        consent(encounterId, who, "GRANTED").andExpect(status().isOk());
        String recordingId = startRecording(encounterId, who);
        upload(encounterId, recordingId, who, "synthetic-audio".getBytes()).andExpect(status().isOk());
        return new String[] {encounterId, recordingId};
    }

    protected ResultActions submitTranscript(String encounterId, String recordingId, String key) throws Exception {
        return putJson("/internal/encounters/" + encounterId + "/transcript", AMBIENT, """
                {"recordingId":"%s","idempotencyKey":"%s",
                 "segments":[
                   {"seq":1,"speaker":"patient","language":"en","text":"I have had a headache for three days.","startMs":0,"endMs":3000,"confidence":0.93},
                   {"seq":2,"speaker":"clinician","language":"en","text":"Take ibuprofen after food.","startMs":3200,"endMs":6000,"confidence":0.91}],
                 "provenance":{"executionId":"exec-asr-1","modelId":"stub-asr-v1","modelVersion":"1.0.0","promptId":null,
                               "promptVersion":null,"policyVersion":"2026-11-15.1","route":"primary"}}
                """.formatted(recordingId, key));
    }

    /** Draft with one supported statement, one citing a missing segment and one with no evidence. */
    protected ResultActions submitDraft(String encounterId, String key) throws Exception {
        return putJson("/internal/encounters/" + encounterId + "/draft", AMBIENT, """
                {"idempotencyKey":"%s","template":"soap",
                 "sections":[
                   {"key":"subjective","title":"Subjective","statements":[
                     {"text":"Headache for three days.","evidenceSeqs":[1],"supported":true,"confidence":0.9},
                     {"text":"No known drug allergies.","evidenceSeqs":[],"supported":false,"confidence":0.4}]},
                   {"key":"plan","title":"Plan","statements":[
                     {"text":"Start amoxicillin.","evidenceSeqs":[99],"supported":true,"confidence":0.8}]}],
                 "provenance":{"executionId":"exec-llm-1","modelId":"stub-llm-v1","modelVersion":"1.0.0",
                               "promptId":"soap-note-draft","promptVersion":1,"policyVersion":"2026-11-15.1","route":"primary"}}
                """.formatted(key));
    }

    /** Encounter in REVIEW with an AI draft. */
    protected String reviewEncounter(RequestPostProcessor who) throws Exception {
        String[] ids = processingEncounter(who);
        submitTranscript(ids[0], ids[1], ids[0] + ":transcript").andExpect(status().isNoContent());
        submitDraft(ids[0], ids[0] + ":draft").andExpect(status().isNoContent());
        return ids[0];
    }

    protected String state(String encounterId) {
        return jdbc.sql("SELECT state FROM encounter.encounter WHERE id = :id")
                .param("id", UUID.fromString(encounterId)).query(String.class).single();
    }
}
