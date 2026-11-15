package ae.afia.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayEdgeTest {

    @Autowired
    WebTestClient client;
    @Autowired
    RouteLocator routes;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder; // no identity provider is contacted

    @Test
    void healthIsPublicAndTheApiNeedsAToken() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/api/encounters").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theInternalServiceApiIsNotReachableFromTheEdge() {
        client.put().uri("/internal/encounters/x/draft").exchange()
                .expectStatus().value(status -> assertThat(status).isIn(401, 403));
        List<String> ids = routes.getRoutes().map(Route::getId).collectList().block();
        assertThat(ids).containsExactly("core-api");
    }

    @Test
    void corsPreflightFromTheWebAppIsAllowedAndEveryResponseHasACorrelationId() {
        client.options().uri("/api/encounters")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().exists("X-Correlation-Id");
    }
}
