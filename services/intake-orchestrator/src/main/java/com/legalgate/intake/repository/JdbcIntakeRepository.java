package com.legalgate.intake.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
@ConditionalOnProperty(name = "legalgate.intake.persistence", havingValue = "jdbc")
class JdbcIntakeRepository implements IntakeRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<TenantRoutingRule>> ROUTING_RULE_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    JdbcIntakeRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public RegistrationResponse registerFirmOwner(
            String firmSlug,
            String firmName,
            String email,
            String hashedPassword,
            String role,
            String intakeEmail
    ) {
        try {
            return transactionTemplate.execute(status -> {
                setTenantContext(firmSlug);
                UUID firmId = createTenant(firmSlug, firmName);
                jdbcTemplate.update("""
                        insert into tenant_settings (tenant_id, intake_email, routing_rules, updated_at)
                        values (?, ?, '[]'::jsonb, now())
                        """, firmId, intakeEmail);
                jdbcTemplate.update("""
                        insert into users (firm_id, email, full_name, role, hashed_password, is_active)
                        values (?, ?, ?, ?, ?, true)
                        """, firmId, email, firmName + " admin", role, hashedPassword);
                return new RegistrationResponse(email, firmSlug, firmName + " admin", role);
            });
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, duplicateRegistrationReason(ex), ex);
        }
    }

    @Override
    public Optional<StoredUserCredentials> findActiveUserByEmail(String email) {
        return transactionTemplate.execute(status -> jdbcTemplate.query("""
                        select email, tenant_id, display_name, role, hashed_password
                        from app_find_active_user_for_login(?)
                        """, (rs, rowNum) -> new StoredUserCredentials(
                        rs.getString("email"),
                        rs.getString("tenant_id"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getString("hashed_password")
                ), email).stream().findFirst());
    }

    @Override
    public void recordSuccessfulLogin(String email) {
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("select app_record_user_login(?)", (PreparedStatementCallback<Void>) ps -> {
                    // PostgreSQL returns a row for "select some_void_function(...)", so execute() is required here.
                    ps.setString(1, email);
                    ps.execute();
                    return null;
                }));
    }

    @Override
    public TenantSettingsResponse saveSettings(String tenantSlug, TenantSettingsResponse settings) {
        try {
            return transactionTemplate.execute(status -> {
                setTenantContext(tenantSlug);
                UUID tenantId = ensureTenant(tenantSlug, displayName(tenantSlug));
                jdbcTemplate.update("""
                        insert into tenant_settings (
                          tenant_id, urgent_keywords, consultation_windows, urgency_levels,
                          destination_email, intake_email, routing_rules, updated_at
                        )
                        values (?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, cast(? as jsonb), now())
                        on conflict (tenant_id) do update set
                          urgent_keywords = excluded.urgent_keywords,
                          consultation_windows = excluded.consultation_windows,
                          urgency_levels = excluded.urgency_levels,
                          destination_email = excluded.destination_email,
                          intake_email = excluded.intake_email,
                          routing_rules = excluded.routing_rules,
                          updated_at = now()
                        """,
                        tenantId,
                        toJson(settings.urgentKeywords()),
                        toJson(settings.consultationWindows()),
                        toJson(settings.urgencyLevels()),
                        settings.destinationEmail(),
                        settings.intakeEmail(),
                        toJson(settings.routingRules()));
                return settings;
            });
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "intake_email_already_configured", ex);
        }
    }

    @Override
    public TenantSettingsResponse settingsFor(String tenantSlug, TenantSettingsResponse defaultSettings) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            List<TenantSettingsResponse> settings = jdbcTemplate.query("""
                    select t.slug, s.urgent_keywords, s.consultation_windows, s.urgency_levels,
                           s.destination_email, s.intake_email, s.routing_rules
                    from tenants t
                    join tenant_settings s on s.tenant_id = t.id
                    where t.slug = ?
                    """, (rs, rowNum) -> mapSettings(rs), tenantSlug);
            return settings.isEmpty() ? defaultSettings : settings.get(0);
        });
    }

    @Override
    public Optional<String> tenantSlugForIntakeEmail(String intakeEmail) {
        if (intakeEmail == null || intakeEmail.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> jdbcTemplate.query("""
                        select app_find_tenant_for_intake_email(?) as slug
                        """, (rs, rowNum) -> rs.getString("slug"), intakeEmail.trim())
                .stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst());
    }

    @Override
    public Optional<ConsultationResponse> consultationForSourceMessageId(String tenantSlug, String sourceMessageId) {
        if (sourceMessageId == null || sourceMessageId.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            return jdbcTemplate.query("""
                            select c.id, t.slug as tenant_slug, c.client_name, c.client_email, c.summary,
                                   c.preferred_window, c.status, c.urgency, c.consultation_type,
                                   c.assigned_lawyer_email, c.classification, c.notifications,
                                   c.source_event_id, c.source_message_id, c.created_at
                            from consultations c
                            join tenants t on t.id = c.tenant_id
                            where t.slug = ? and c.source_message_id = ?
                            """, (rs, rowNum) -> mapConsultation(rs), tenantSlug, sourceMessageId)
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation) {
        try {
            return transactionTemplate.execute(status -> {
                setTenantContext(tenantSlug);
                UUID tenantId = ensureTenant(tenantSlug, displayName(tenantSlug));
                jdbcTemplate.update("""
                        insert into consultations (
                          id, tenant_id, client_name, client_email, summary, preferred_window, status, urgency,
                          consultation_type, assigned_lawyer_email, classification, notifications,
                          source_event_id, source_message_id, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?)
                        """,
                        UUID.fromString(consultation.id()),
                        tenantId,
                        consultation.clientName(),
                        consultation.clientEmail(),
                        consultation.summary(),
                        consultation.preferredWindow(),
                        consultation.status(),
                        consultation.urgency(),
                        consultation.consultationType(),
                        consultation.assignedLawyerEmail(),
                        toJson(consultation.classification()),
                        toJson(consultation.notifications()),
                        consultation.sourceEventId(),
                        consultation.sourceMessageId(),
                        Timestamp.from(consultation.createdAt()));
                return consultation;
            });
        } catch (DuplicateKeyException ex) {
            return consultationForSourceMessageId(tenantSlug, consultation.sourceMessageId())
                    .orElseThrow(() -> ex);
        }
    }

    @Override
    public ConsultationListResponse consultationsForTenant(String tenantSlug) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            List<ConsultationResponse> consultations = jdbcTemplate.query("""
                    select c.id, t.slug as tenant_slug, c.client_name, c.client_email, c.summary,
                           c.preferred_window, c.status, c.urgency, c.consultation_type,
                           c.assigned_lawyer_email, c.classification, c.notifications,
                           c.source_event_id, c.source_message_id, c.created_at
                    from consultations c
                    join tenants t on t.id = c.tenant_id
                    where t.slug = ?
                    order by c.created_at asc
                    """, (rs, rowNum) -> mapConsultation(rs), tenantSlug);
            return new ConsultationListResponse(tenantSlug, consultations);
        });
    }

    private UUID ensureTenant(String tenantSlug, String displayName) {
        // TODO(workos): Persist workos_organization_id once the gateway derives tenant context from WorkOS org claims.
        return jdbcTemplate.queryForObject("""
                insert into tenants (slug, display_name)
                values (?, ?)
                on conflict (slug) do update set display_name = excluded.display_name
                returning id
                """, UUID.class, tenantSlug, displayName);
    }

    private UUID createTenant(String tenantSlug, String displayName) {
        // TODO(workos): Persist workos_organization_id once the gateway derives tenant context from WorkOS org claims.
        return jdbcTemplate.queryForObject("""
                insert into tenants (slug, display_name)
                values (?, ?)
                returning id
                """, UUID.class, tenantSlug, displayName);
    }

    private void setTenantContext(String tenantSlug) {
        jdbcTemplate.queryForObject("select set_config('app.tenant_slug', ?, true)", String.class, tenantSlug);
    }

    private TenantSettingsResponse mapSettings(ResultSet rs) throws SQLException {
        return new TenantSettingsResponse(
                rs.getString("slug"),
                fromJson(rs.getString("urgent_keywords"), STRING_LIST),
                fromJson(rs.getString("consultation_windows"), STRING_LIST),
                fromJson(rs.getString("urgency_levels"), STRING_LIST),
                rs.getString("destination_email"),
                rs.getString("intake_email"),
                fromJson(rs.getString("routing_rules"), ROUTING_RULE_LIST)
        );
    }

    private ConsultationResponse mapConsultation(ResultSet rs) throws SQLException {
        return new ConsultationResponse(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("tenant_slug"),
                rs.getString("client_name"),
                rs.getString("client_email"),
                rs.getString("summary"),
                rs.getString("preferred_window"),
                rs.getString("status"),
                rs.getString("urgency"),
                rs.getString("consultation_type"),
                rs.getString("assigned_lawyer_email"),
                fromJson(rs.getString("classification"), ClassificationResult.class),
                fromJson(rs.getString("notifications"), NotificationStatus.class),
                rs.getString("source_event_id"),
                rs.getString("source_message_id"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String displayName(String tenantSlug) {
        return tenantSlug.replace('-', ' ');
    }

    private String duplicateRegistrationReason(DuplicateKeyException ex) {
        String message = ex.getMostSpecificCause() == null ? ex.getMessage() : ex.getMostSpecificCause().getMessage();
        if (message != null && message.contains("tenants_slug")) {
            return "tenant_slug_already_registered";
        }
        if (message != null && message.contains("users_email")) {
            return "email_already_registered";
        }
        return "tenant_or_email_already_registered";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize intake data", ex);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize intake data", ex);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize intake data", ex);
        }
    }
}
