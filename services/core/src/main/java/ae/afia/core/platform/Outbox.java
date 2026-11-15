package ae.afia.core.platform;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox. An event is stored in the same database transaction as the state
 * change that caused it; the relay publishes it afterwards. MANDATORY propagation makes a
 * dual write impossible: recording an event outside a transaction fails.
 */
@Component
public class Outbox {

    public static final int SCHEMA_VERSION = 1;

    private final JdbcClient jdbc;

    public Outbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param data ids and object references only; never audio, transcript text or other PHI */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(String tenantId, String aggregateType, UUID aggregateId, int aggregateVersion,
                       String eventType, Map<String, ?> data) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("tenantId", tenantId);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("correlationId", CorrelationFilter.current());
        envelope.put("causationId", null);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("producer", "afia-core");
        envelope.put("classification", "INTERNAL");
        envelope.put("data", data);
        jdbc.sql("""
                INSERT INTO platform.outbox (id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
                                             event_type, payload)
                VALUES (:id, :tenant, :type, :aggregateId, :version, :eventType, :payload)
                """)
                .param("id", eventId).param("tenant", tenantId).param("type", aggregateType)
                .param("aggregateId", aggregateId.toString()).param("version", aggregateVersion)
                .param("eventType", eventType).param("payload", Json.object(envelope))
                .update();
        return eventId;
    }
}
