package ae.afia.core.platform;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes stored events in creation order. No database transaction is held while
 * waiting for the broker. Delivery is at-least-once: a crash between publish and mark
 * republishes the event, and consumers deduplicate by eventId.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Pending(UUID id, String tenantId, String aggregateId, String payload) {
    }

    private final JdbcClient jdbc;
    private final EventBus bus;
    private final String topic;

    public OutboxRelay(JdbcClient jdbc, EventBus bus, @Value("${afia.events.topic}") String topic) {
        this.jdbc = jdbc;
        this.bus = bus;
        this.topic = topic;
    }

    /** @return number of events published in this pass */
    public int publishPending() {
        List<Pending> batch = jdbc.sql("""
                        SELECT id, tenant_id, aggregate_id, payload FROM platform.outbox
                        WHERE published_at IS NULL ORDER BY created_at, id LIMIT 50
                        """)
                .query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                        rs.getString("aggregate_id"), rs.getString("payload")))
                .list();
        int published = 0;
        for (Pending event : batch) {
            try {
                bus.publish(topic, event.tenantId() + ":" + event.aggregateId(), event.payload());
            } catch (Exception ex) {
                jdbc.sql("UPDATE platform.outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                        .param("error", ex.getClass().getSimpleName()).param("id", event.id()).update();
                log.warn("outbox publish failed for event {}; will retry", event.id());
                break; // keep ordering: do not publish later events before this one
            }
            jdbc.sql("UPDATE platform.outbox SET published_at = now() WHERE id = :id AND published_at IS NULL")
                    .param("id", event.id()).update();
            published++;
        }
        return published;
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "afia.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final OutboxRelay relay;

        Schedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${afia.outbox.relay.interval-ms:1000}")
        void tick() {
            relay.publishPending();
        }
    }
}
