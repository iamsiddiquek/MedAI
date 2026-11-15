package ae.afia.core.note;

import ae.afia.core.platform.CurrentUser;
import ae.afia.core.transcript.TranscriptService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Clinician-facing review API: transcript, note, statement review and sign-off. */
@RestController
@RequestMapping("/api/encounters/{encounterId}")
public class ClinicalController {

    private final TranscriptService transcripts;
    private final NoteService notes;

    public ClinicalController(TranscriptService transcripts, NoteService notes) {
        this.transcripts = transcripts;
        this.notes = notes;
    }

    @GetMapping("/transcript")
    TranscriptService.TranscriptView transcript(@PathVariable UUID encounterId) {
        return transcripts.view(CurrentUser.current(), encounterId);
    }

    @GetMapping("/note")
    NoteService.NoteView note(@PathVariable UUID encounterId) {
        return notes.view(CurrentUser.current(), encounterId);
    }

    @GetMapping("/note/versions")
    List<NoteService.VersionInfo> versions(@PathVariable UUID encounterId) {
        return notes.versions(CurrentUser.current(), encounterId);
    }

    @PostMapping("/note/manual")
    @ResponseStatus(HttpStatus.CREATED)
    NoteService.NoteView manual(@PathVariable UUID encounterId) {
        return notes.createManual(CurrentUser.current(), encounterId);
    }

    @PostMapping("/note/statements/review")
    NoteService.NoteView review(@PathVariable UUID encounterId, @RequestBody NoteService.Review body) {
        return notes.review(CurrentUser.current(), encounterId, body);
    }

    @PostMapping("/note/statements")
    NoteService.NoteView add(@PathVariable UUID encounterId, @RequestBody NoteService.NewStatement body) {
        return notes.addStatement(CurrentUser.current(), encounterId, body);
    }

    @PostMapping("/note/sign")
    NoteService.NoteView sign(@PathVariable UUID encounterId, @RequestBody NoteService.SignRequest body) {
        return notes.sign(CurrentUser.current(), encounterId, body);
    }
}
