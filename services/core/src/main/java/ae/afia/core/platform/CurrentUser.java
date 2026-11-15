package ae.afia.core.platform;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated caller. Tenant identity comes only from the validated token; no
 * endpoint accepts a tenant as a path, query, header or body value.
 */
public record CurrentUser(String subject, String name, String tenantId, Set<String> roles) {

    public static CurrentUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        Jwt jwt = token.getToken();
        Set<String> roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toSet());
        String name = jwt.getClaimAsString("name");
        return new CurrentUser(jwt.getSubject(), name == null ? jwt.getSubject() : name,
                jwt.getClaimAsString("tenant_id"), roles);
    }

    /** The caller's tenant, or a refusal when the token carries none. */
    public String tenant() {
        if (tenantId == null || tenantId.isBlank()) {
            throw ApiException.forbidden("no_tenant");
        }
        return tenantId;
    }

    public void requireAny(String... allowed) {
        for (String role : allowed) {
            if (roles.contains(role)) {
                return;
            }
        }
        throw ApiException.forbidden("forbidden");
    }
}
