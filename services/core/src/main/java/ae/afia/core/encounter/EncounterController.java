package ae.afia.core.encounter;

import ae.afia.core.encounter.EncounterService.ConsentDecision;
import ae.afia.core.encounter.EncounterService.EncounterView;
import ae.afia.core.platform.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EncounterController {

    public record NewEncounter(UUID patientId) {
    }

    private final EncounterService encounters;

    public EncounterController(EncounterService encounters) {
        this.encounters = encounters;
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        CurrentUser user = CurrentUser.current();
        return Map.of("subject", user.subject(), "name", user.name(), "tenantId", user.tenant(),
                "roles", user.roles().stream().sorted().toList());
    }

    @PostMapping("/encounters")
    @ResponseStatus(HttpStatus.CREATED)
    EncounterView create(@RequestBody NewEncounter body) {
        return encounters.create(CurrentUser.current(), body.patientId());
    }

    @GetMapping("/encounters")
    List<EncounterView> list() {
        return encounters.list(CurrentUser.current());
    }

    @GetMapping("/encounters/{encounterId}")
    EncounterView view(@PathVariable UUID encounterId) {
        return encounters.view(CurrentUser.current(), encounterId);
    }

    @PostMapping("/encounters/{encounterId}/consent")
    EncounterView consent(@PathVariable UUID encounterId, @RequestBody ConsentDecision body) {
        return encounters.recordConsent(CurrentUser.current(), encounterId, body);
    }

    @PostMapping("/encounters/{encounterId}/consent/revoke")
    EncounterView revoke(@PathVariable UUID encounterId) {
        return encounters.revokeConsent(CurrentUser.current(), encounterId);
    }
}
