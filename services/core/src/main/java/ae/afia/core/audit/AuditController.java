package ae.afia.core.audit;

import ae.afia.core.platform.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final String[] READERS = {"compliance-officer", "security-auditor", "tenant-admin"};

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/events")
    List<AuditService.AuditEvent> events(@RequestParam String resourceId) {
        CurrentUser user = CurrentUser.current();
        user.requireAny(READERS);
        return audit.forResource(user.tenant(), resourceId);
    }

    @GetMapping("/verify")
    AuditService.Verification verify() {
        CurrentUser user = CurrentUser.current();
        user.requireAny(READERS);
        return audit.verify(user.tenant());
    }
}
