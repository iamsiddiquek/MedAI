package ae.afia.core.patient;

import ae.afia.core.audit.AuditService;
import ae.afia.core.platform.ApiException;
import ae.afia.core.platform.CurrentUser;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Patient references. Afia stores the hospital's identifier and its issuer, not a master index. */
@Service
public class PatientService {

    public record Patient(UUID id, String mrn, String mrnIssuer, String fullName, String dateOfBirth, String sex) {
    }

    public record NewPatient(String mrn, String mrnIssuer, String fullName, String dateOfBirth, String sex) {
    }

    private static final RowMapper<Patient> MAPPER = (rs, n) -> new Patient(rs.getObject("id", UUID.class),
            rs.getString("mrn"), rs.getString("mrn_issuer"), rs.getString("full_name"),
            rs.getDate("date_of_birth").toLocalDate().toString(), rs.getString("sex"));
    private static final String COLUMNS = "id, mrn, mrn_issuer, full_name, date_of_birth, sex";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PatientService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public Patient create(CurrentUser user, NewPatient in) {
        user.requireAny("clinician", "nurse", "tenant-admin");
        if (in == null || blank(in.mrn()) || blank(in.mrnIssuer()) || blank(in.fullName()) || blank(in.dateOfBirth())) {
            throw ApiException.badRequest("patient_fields_required");
        }
        LocalDate dob;
        try {
            dob = LocalDate.parse(in.dateOfBirth());
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("invalid_date_of_birth");
        }
        if (dob.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("invalid_date_of_birth");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO patient.patient (id, tenant_id, mrn, mrn_issuer, full_name, date_of_birth, sex)
                    VALUES (:id, :tenant, :mrn, :issuer, :name, :dob, :sex)
                    """)
                    .param("id", id).param("tenant", user.tenant()).param("mrn", in.mrn().trim())
                    .param("issuer", in.mrnIssuer().trim()).param("name", in.fullName().trim())
                    .param("dob", Date.valueOf(dob)).param("sex", in.sex())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("patient_identifier_exists");
        }
        audit.record(user.tenant(), user.subject(), "PATIENT_CREATED", "Patient", id.toString());
        return new Patient(id, in.mrn().trim(), in.mrnIssuer().trim(), in.fullName().trim(), dob.toString(), in.sex());
    }

    @Transactional(readOnly = true)
    public List<Patient> search(CurrentUser user, String query) {
        user.requireAny("clinician", "nurse", "tenant-admin");
        String term = query == null ? "" : query.trim().toLowerCase();
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM patient.patient
                        WHERE tenant_id = :tenant AND (lower(full_name) LIKE :like OR lower(mrn) = :term)
                        ORDER BY full_name LIMIT 50
                        """)
                .param("tenant", user.tenant()).param("like", "%" + term + "%").param("term", term)
                .query(MAPPER).list();
    }

    @Transactional(readOnly = true)
    public Optional<Patient> find(String tenantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM patient.patient WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId).param("id", id).query(MAPPER).optional();
    }

    @Transactional(readOnly = true)
    public Map<UUID, Patient> findAll(String tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM patient.patient WHERE tenant_id = :tenant AND id IN (:ids)")
                .param("tenant", tenantId).param("ids", List.copyOf(ids)).query(MAPPER).list().stream()
                .collect(Collectors.toMap(Patient::id, Function.identity()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
