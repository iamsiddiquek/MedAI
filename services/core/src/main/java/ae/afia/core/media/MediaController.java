package ae.afia.core.media;

import ae.afia.core.platform.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/encounters/{encounterId}/recordings")
public class MediaController {

    public record StartRecording(boolean patientConfirmed) {
    }

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MediaService.Recording start(@PathVariable UUID encounterId, @RequestBody StartRecording body) {
        return media.start(CurrentUser.current(), encounterId, body.patientConfirmed());
    }

    @GetMapping("/active")
    MediaService.Recording active(@PathVariable UUID encounterId) {
        return media.active(CurrentUser.current(), encounterId);
    }

    @PutMapping(value = "/{recordingId}/audio", consumes = "audio/*")
    MediaService.Recording upload(@PathVariable UUID encounterId, @PathVariable UUID recordingId,
                                  @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                                  @RequestBody byte[] audio) {
        return media.upload(CurrentUser.current(), encounterId, recordingId, audio, contentType);
    }
}
