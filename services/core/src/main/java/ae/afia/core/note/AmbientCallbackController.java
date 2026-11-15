package ae.afia.core.note;

import ae.afia.core.audit.AuditService;
import ae.afia.core.encounter.EncounterService;
import ae.afia.core.encounter.EncounterService.Encounter;
import ae.afia.core.platform.ApiException;
import ae.afia.core.transcript.TranscriptService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API for ambient-ai (role svc-ambient, enforced in SecurityConfig).
 * The caller never names a tenant: it is read from the stored encounter.
 */
@RestController
@RequestMapping("/internal/encounters/{encounterId}")
public class AmbientCallbackController {

    public record Failure(String idempotencyKey, String reasonCode) {
    }

    private final EncounterService encounters;
    private final TranscriptService transcripts;
    private final NoteService notes;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public AmbientCallbackController(EncounterService encounters, TranscriptService transcripts, NoteService notes,
                                     AuditService audit, TransactionTemplate tx) {
        this.encounters = encounters;
        this.transcripts = transcripts;
        this.notes = notes;
        this.audit = audit;
        this.tx = tx;
    }

    @PutMapping("/transcript")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transcript(@PathVariable UUID encounterId, @RequestBody TranscriptService.NewTranscript body) {
        transcripts.store(encounter(encounterId), body);
    }

    @PutMapping("/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void draft(@PathVariable UUID encounterId, @RequestBody NoteService.NewDraft body) {
        notes.storeDraft(encounter(encounterId), body);
    }

    /** AI could not complete: the encounter shows a degraded status and the manual-note path opens. */
    @PostMapping("/processing-failed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void failed(@PathVariable UUID encounterId, @RequestBody Failure body) {
        if (body == null || body.reasonCode() == null || !body.reasonCode().matches("[a-z_]{3,64}")) {
            throw ApiException.badRequest("invalid_reason");
        }
        Encounter encounter = encounter(encounterId);
        if (!encounter.state().equals("PROCESSING")) {
            return; // late or repeated report: nothing to degrade
        }
        tx.executeWithoutResult(status -> {
            encounters.setAiStatus(encounter, "UNAVAILABLE", body.reasonCode());
            audit.record(encounter.tenantId(), TranscriptService.SERVICE_ACTOR, "AI_PROCESSING_FAILED", "Encounter",
                    encounterId.toString());
        });
    }

    private Encounter encounter(UUID encounterId) {
        return encounters.findForService(encounterId).orElseThrow(ApiException::notFound);
    }
}
