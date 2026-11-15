package ae.afia.core.audit;

import ae.afia.core.platform.CorrelationFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only, hash-chained audit ledger, one chain per tenant.
 *
 * Recording joins the caller's transaction (MANDATORY): the business change and its
 * evidence commit together or not at all, so consent and sign-off fail closed when the
 * evidence cannot be stored. Rows hold ids and action names, never clinical content.
 */
@Service
public class AuditService {

    static final String GENESIS = "0".repeat(64);

    public record AuditEvent(long seq, String actor, String action, String resourceType, String resourceId,
                             String outcome, String correlationId, String occurredAt, String hash) {
    }

    public record Verification(boolean valid, int events, Long firstBrokenSeq) {
    }

    private record Row(long seq, String actor, String action, String resourceType, String resourceId,
                       String outcome, String correlationId, long occurredAtUs, String prevHash, String hash) {
    }

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String tenantId, String actor, String action, String resourceType, String resourceId) {
        // Serialise appends per tenant so the chain has exactly one successor per link.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:tenant))").param("tenant", tenantId)
                .query((rs, n) -> 1).list();
        String prev = jdbc.sql("SELECT hash FROM audit.audit_event WHERE tenant_id = :tenant ORDER BY seq DESC LIMIT 1")
                .param("tenant", tenantId).query(String.class).optional().orElse(GENESIS);
        long occurredAtUs = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
        String correlationId = CorrelationFilter.current();
        String hash = hash(prev, tenantId, actor, action, resourceType, resourceId, "SUCCESS", correlationId,
                occurredAtUs);
        jdbc.sql("""
                INSERT INTO audit.audit_event (tenant_id, actor, action, resource_type, resource_id, outcome,
                                               correlation_id, occurred_at_us, prev_hash, hash)
                VALUES (:tenant, :actor, :action, :type, :id, 'SUCCESS', :corr, :at, :prev, :hash)
                """)
                .param("tenant", tenantId).param("actor", actor).param("action", action)
                .param("type", resourceType).param("id", resourceId).param("corr", correlationId)
                .param("at", occurredAtUs).param("prev", prev).param("hash", hash)
                .update();
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forResource(String tenantId, String resourceId) {
        return rows("AND resource_id = :resource", tenantId, resourceId).stream().map(AuditService::view).toList();
    }

    /** Recomputes the tenant's chain; any edited, removed or reordered row breaks it. */
    @Transactional(readOnly = true)
    public Verification verify(String tenantId) {
        List<Row> rows = rows("", tenantId, null);
        String prev = GENESIS;
        for (Row row : rows) {
            String expected = hash(prev, tenantId, row.actor(), row.action(), row.resourceType(), row.resourceId(),
                    row.outcome(), row.correlationId(), row.occurredAtUs());
            if (!row.prevHash().equals(prev) || !row.hash().equals(expected)) {
                return new Verification(false, rows.size(), row.seq());
            }
            prev = row.hash();
        }
        return new Verification(true, rows.size(), null);
    }

    private List<Row> rows(String filter, String tenantId, String resourceId) {
        JdbcClient.StatementSpec spec = jdbc.sql("""
                SELECT seq, actor, action, resource_type, resource_id, outcome, correlation_id, occurred_at_us,
                       prev_hash, hash
                FROM audit.audit_event WHERE tenant_id = :tenant %s ORDER BY seq
                """.formatted(filter)).param("tenant", tenantId);
        if (resourceId != null) {
            spec = spec.param("resource", resourceId);
        }
        return spec.query((rs, n) -> new Row(rs.getLong("seq"), rs.getString("actor"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("outcome"),
                rs.getString("correlation_id"), rs.getLong("occurred_at_us"), rs.getString("prev_hash"),
                rs.getString("hash"))).list();
    }

    private static AuditEvent view(Row row) {
        String at = Instant.EPOCH.plus(row.occurredAtUs(), ChronoUnit.MICROS).toString();
        return new AuditEvent(row.seq(), row.actor(), row.action(), row.resourceType(), row.resourceId(),
                row.outcome(), row.correlationId(), at, row.hash());
    }

    private static String hash(String prev, String tenantId, String actor, String action, String resourceType,
                               String resourceId, String outcome, String correlationId, long occurredAtUs) {
        String material = String.join("|", prev, tenantId, actor, action, resourceType, resourceId, outcome,
                correlationId, Long.toString(occurredAtUs));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
