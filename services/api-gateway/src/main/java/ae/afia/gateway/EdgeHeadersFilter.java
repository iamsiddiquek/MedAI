package ae.afia.gateway;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request one correlation id and removes identity headers a client may have
 * invented. Tenant and user identity come only from the validated token.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EdgeHeadersFilter implements WebFilter {

    public static final String CORRELATION = "X-Correlation-Id";
    static final List<String> CLIENT_IDENTITY_HEADERS =
            List.of("X-Tenant-Id", "X-User-Id", "X-User-Roles", "X-Forwarded-User");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(CORRELATION);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(CORRELATION, id);
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.set(CORRELATION, id);
                    CLIENT_IDENTITY_HEADERS.forEach(h::remove);
                }))
                .build();
        return chain.filter(mutated);
    }
}
