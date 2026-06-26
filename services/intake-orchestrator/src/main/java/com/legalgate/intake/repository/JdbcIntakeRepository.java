package com.legalgate.intake.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerAvailabilityWindow;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Time;
import java.time.Instant;
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
    public RegistrationResponse registerFirmOwner(String firmSlug, String firmName, String email, String hashedPassword, String role, String intakeEmail) {
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
                saveLawyers(tenantId, settings.lawyers());
                return settings;
            });
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "intake_email_or_lawyer_already_configured", ex);
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
            return jdbcTemplate.query(consultationSelect() + " where t.slug = ? and c.source_message_id = ?", this::mapConsultation, tenantSlug, sourceMessageId)
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation, List<NotificationOutboxItem> notifications) {
        try {
            return transactionTemplate.execute(status -> {
                setTenantContext(tenantSlug);
                UUID tenantId = ensureTenant(tenantSlug, displayName(tenantSlug));
                UUID consultationId = UUID.fromString(consultation.id());
                jdbcTemplate.update("""
                        insert into consultations (
                          id, tenant_id, client_name, client_email, summary, preferred_window, status, urgency,
                          consultation_type, assigned_lawyer_email, classification, notifications,
                          source_event_id, source_message_id, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?)
                        """,
                        consultationId,
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
                if (consultation.event() != null) {
                    EventResponse event = consultation.event();
                    UUID eventId = UUID.fromString(event.id());
                    jdbcTemplate.update("""
                            insert into events (
                              id, tenant_id, lawyer_id, consultation_id, route_name, route_id_snapshot,
                              urgency_name, sla_days, sla_deadline, priority_score, scheduled_start,
                              scheduled_end, meeting_url, scheduled_within_sla, status, source, created_at, updated_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                            """,
                            eventId,
                            tenantId,
                            uuidOrNull(event.lawyerId()),
                            consultationId,
                            event.routeName(),
                            consultation.consultationType(),
                            event.urgencyName(),
                            event.slaDays(),
                            Timestamp.from(event.slaDeadline()),
                            event.priorityScore(),
                            timestampOrNull(event.scheduledStart()),
                            timestampOrNull(event.scheduledEnd()),
                            event.meetingUrl(),
                            event.scheduledWithinSla(),
                            event.status(),
                            event.source());
                    jdbcTemplate.update("update consultations set event_id = ? where id = ?", eventId, consultationId);
                }
                insertNotifications(tenantId, tenantSlug, notifications);
                return consultation;
            });
        } catch (DuplicateKeyException ex) {
            return consultationForSourceMessageId(tenantSlug, consultation.sourceMessageId()).orElseThrow(() -> ex);
        }
    }

    @Override
    public ConsultationListResponse consultationsForTenant(String tenantSlug) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            List<ConsultationResponse> consultations = jdbcTemplate.query(
                    consultationSelect() + " where t.slug = ? order by c.created_at asc",
                    this::mapConsultation,
                    tenantSlug
            );
            return new ConsultationListResponse(tenantSlug, consultations);
        });
    }

    @Override
    public Optional<ConsultationResponse> consultationForEventId(String tenantSlug, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            return jdbcTemplate.query(consultationSelect() + " where t.slug = ? and c.event_id = ?", this::mapConsultation, tenantSlug, UUID.fromString(eventId))
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public List<LawyerProfile> lawyersForTenant(String tenantSlug) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            return lawyersForCurrentTenant();
        });
    }

    @Override
    public List<EventResponse> eventsForLawyer(String tenantSlug, String lawyerId) {
        if (lawyerId == null || lawyerId.isBlank()) {
            return List.of();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            return jdbcTemplate.query("""
                    select e.id as event_id_read, e.lawyer_id, l.display_name as lawyer_display_name, l.email as lawyer_email,
                           e.route_name, e.urgency_name, e.sla_days, e.sla_deadline, e.priority_score,
                           e.scheduled_start, e.scheduled_end, e.meeting_url, e.scheduled_within_sla,
                           e.status as event_status, e.source as event_source
                    from events e
                    left join lawyers l on l.id = e.lawyer_id
                    join tenants t on t.id = e.tenant_id
                    where t.slug = ? and e.lawyer_id = ?
                    order by e.scheduled_start asc nulls last, e.sla_deadline asc
                    """, this::mapEvent, tenantSlug, UUID.fromString(lawyerId));
        });
    }

    @Override
    public void updateEvents(String tenantSlug, List<EventResponse> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, displayName(tenantSlug));
            for (EventResponse event : events) {
                jdbcTemplate.update("""
                        update events
                        set scheduled_start = ?, scheduled_end = ?, status = ?, priority_score = ?,
                            scheduled_within_sla = ?, updated_at = now()
                        where id = ?
                        """,
                        timestampOrNull(event.scheduledStart()),
                        timestampOrNull(event.scheduledEnd()),
                        event.status(),
                        event.priorityScore(),
                        event.scheduledWithinSla(),
                        UUID.fromString(event.id()));
            }
        });
    }

    @Override
    public void queueNotifications(String tenantSlug, List<NotificationOutboxItem> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            setTenantContext(tenantSlug);
            UUID tenantId = ensureTenant(tenantSlug, displayName(tenantSlug));
            insertNotifications(tenantId, tenantSlug, notifications);
        });
    }

    @Override
    public List<NotificationOutboxItem> claimPendingNotifications(int limit) {
        return transactionTemplate.execute(status -> {
            setTenantContext("__worker__");
            List<NotificationOutboxItem> notifications = jdbcTemplate.query("""
                    update notification_outbox
                    set status = 'SENDING', updated_at = now()
                    where id in (
                        select id
                        from notification_outbox
                        where status in ('PENDING', 'FAILED') and next_attempt_at <= now()
                        order by created_at asc
                        limit ?
                        for update skip locked
                    )
                    returning id, tenant_slug, consultation_id, event_id, notification_type, recipient_role, recipient_email,
                              subject, body, ics_content, status, attempts, provider_message_id, last_error,
                              created_at, updated_at, next_attempt_at
                    """, this::mapNotification, Math.max(1, limit));
            return notifications;
        });
    }

    @Override
    public void markNotificationSent(String notificationId, String providerMessageId) {
        transactionTemplate.executeWithoutResult(status -> {
            setTenantContext("__worker__");
            jdbcTemplate.update("""
                    update notification_outbox
                    set status = 'SENT', provider_message_id = ?, last_error = null, updated_at = now()
                    where id = ?
                    """, providerMessageId, UUID.fromString(notificationId));
        });
    }

    @Override
    public void markNotificationFailed(String notificationId, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            setTenantContext("__worker__");
            jdbcTemplate.update("""
                    update notification_outbox
                    set status = 'FAILED',
                        attempts = attempts + 1,
                        last_error = ?,
                        next_attempt_at = now() + (least(3600, power(2, least(10, attempts + 1))::int * 60) || ' seconds')::interval,
                        updated_at = now()
                    where id = ?
                    """, truncate(errorMessage, 2000), UUID.fromString(notificationId));
        });
    }

    private void saveLawyers(UUID tenantId, List<LawyerProfile> lawyers) {
        if (lawyers == null) {
            return;
        }
        List<UUID> lawyerIds = lawyers.stream()
                .map(lawyer -> UUID.fromString(lawyer.id()))
                .toList();
        if (lawyerIds.isEmpty()) {
            jdbcTemplate.update("delete from lawyers where tenant_id = ?", tenantId);
        } else {
            String placeholders = String.join(",", lawyerIds.stream().map(ignored -> "?").toList());
            Object[] args = new Object[lawyerIds.size() + 1];
            args[0] = tenantId;
            for (int index = 0; index < lawyerIds.size(); index++) {
                args[index + 1] = lawyerIds.get(index);
            }
            jdbcTemplate.update("delete from lawyers where tenant_id = ? and id not in (" + placeholders + ")", args);
        }
        for (LawyerProfile lawyer : lawyers) {
            UUID lawyerId = UUID.fromString(lawyer.id());
            jdbcTemplate.update("""
                    insert into lawyers (id, tenant_id, display_name, email, meeting_url, active, default_event_duration_minutes, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, now(), now())
                    on conflict (id) do update set
                      display_name = excluded.display_name,
                      email = excluded.email,
                      meeting_url = excluded.meeting_url,
                      active = excluded.active,
                      default_event_duration_minutes = excluded.default_event_duration_minutes,
                      updated_at = now()
                    """,
                    lawyerId,
                    tenantId,
                    lawyer.displayName(),
                    lawyer.email(),
                    lawyer.meetingUrl(),
                    lawyer.active() == null || lawyer.active(),
                    lawyer.defaultEventDurationMinutes());
            jdbcTemplate.update("delete from lawyer_availability_windows where lawyer_id = ?", lawyerId);
            for (LawyerAvailabilityWindow window : lawyer.availabilityWindows() == null ? List.<LawyerAvailabilityWindow>of() : lawyer.availabilityWindows()) {
                jdbcTemplate.update("""
                        insert into lawyer_availability_windows (lawyer_id, weekday, start_time, end_time, timezone)
                        values (?, ?, ?, ?, ?)
                        """,
                        lawyerId,
                        window.weekday(),
                        Time.valueOf(window.startTime() + ":00"),
                        Time.valueOf(window.endTime() + ":00"),
                        window.timezone() == null || window.timezone().isBlank() ? "America/Bogota" : window.timezone());
            }
        }
    }

    private List<LawyerProfile> lawyersForCurrentTenant() {
        List<LawyerProfile> lawyers = jdbcTemplate.query("""
                select l.id, l.display_name, l.email, l.meeting_url, l.active, l.default_event_duration_minutes
                from lawyers l
                join tenants t on t.id = l.tenant_id
                where t.slug = current_setting('app.tenant_slug', true)
                order by l.display_name asc
                """, (rs, rowNum) -> new LawyerProfile(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("meeting_url"),
                rs.getBoolean("active"),
                rs.getInt("default_event_duration_minutes"),
                List.of()
        ));
        return lawyers.stream()
                .map(lawyer -> new LawyerProfile(
                        lawyer.id(),
                        lawyer.displayName(),
                        lawyer.email(),
                        lawyer.meetingUrl(),
                        lawyer.active(),
                        lawyer.defaultEventDurationMinutes(),
                        availabilityFor(lawyer.id())
                ))
                .toList();
    }

    private List<LawyerAvailabilityWindow> availabilityFor(String lawyerId) {
        return jdbcTemplate.query("""
                select weekday, start_time, end_time, timezone
                from lawyer_availability_windows
                where lawyer_id = ?
                order by weekday asc, start_time asc
                """, (rs, rowNum) -> new LawyerAvailabilityWindow(
                rs.getInt("weekday"),
                rs.getTime("start_time").toLocalTime().toString().substring(0, 5),
                rs.getTime("end_time").toLocalTime().toString().substring(0, 5),
                rs.getString("timezone")
        ), UUID.fromString(lawyerId));
    }

    private String consultationSelect() {
        return """
                select c.id, t.slug as tenant_slug, c.client_name, c.client_email, c.summary,
                       c.preferred_window, c.status, c.urgency, c.consultation_type,
                       c.assigned_lawyer_email, c.classification, c.notifications,
                       c.source_event_id, c.source_message_id, c.created_at, c.event_id,
                       e.id as event_id_read, e.lawyer_id, l.display_name as lawyer_display_name, l.email as lawyer_email,
                       e.route_name, e.urgency_name, e.sla_days, e.sla_deadline, e.priority_score,
                       e.scheduled_start, e.scheduled_end, e.meeting_url, e.scheduled_within_sla,
                       e.status as event_status, e.source as event_source
                from consultations c
                join tenants t on t.id = c.tenant_id
                left join events e on e.id = c.event_id
                left join lawyers l on l.id = e.lawyer_id
                """;
    }

    private TenantSettingsResponse mapSettings(ResultSet rs) throws SQLException {
        return new TenantSettingsResponse(
                rs.getString("slug"),
                fromJson(rs.getString("urgent_keywords"), STRING_LIST),
                fromJson(rs.getString("consultation_windows"), STRING_LIST),
                fromJson(rs.getString("urgency_levels"), STRING_LIST),
                rs.getString("destination_email"),
                rs.getString("intake_email"),
                fromJson(rs.getString("routing_rules"), ROUTING_RULE_LIST),
                lawyersForCurrentTenant()
        );
    }

    private ConsultationResponse mapConsultation(ResultSet rs, int rowNum) throws SQLException {
        String eventId = rs.getObject("event_id_read") == null ? null : rs.getObject("event_id_read", UUID.class).toString();
        EventResponse event = eventId == null ? null : mapEvent(rs, rowNum);
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
                rs.getTimestamp("created_at").toInstant(),
                eventId,
                event
        );
    }

    private EventResponse mapEvent(ResultSet rs, int rowNum) throws SQLException {
        Timestamp scheduledStart = rs.getTimestamp("scheduled_start");
        Timestamp scheduledEnd = rs.getTimestamp("scheduled_end");
        return new EventResponse(
                rs.getObject("event_id_read") == null ? rs.getObject("id", UUID.class).toString() : rs.getObject("event_id_read", UUID.class).toString(),
                rs.getObject("lawyer_id") == null ? null : rs.getObject("lawyer_id", UUID.class).toString(),
                rs.getString("lawyer_display_name"),
                rs.getString("lawyer_email"),
                rs.getString("route_name"),
                rs.getString("urgency_name"),
                rs.getInt("sla_days"),
                rs.getTimestamp("sla_deadline").toInstant(),
                rs.getInt("priority_score"),
                scheduledStart == null ? null : scheduledStart.toInstant(),
                scheduledEnd == null ? null : scheduledEnd.toInstant(),
                rs.getString("meeting_url"),
                rs.getObject("scheduled_within_sla") == null ? null : rs.getBoolean("scheduled_within_sla"),
                rs.getString("event_status"),
                rs.getString("event_source")
        );
    }

    private void insertNotifications(UUID tenantId, String tenantSlug, List<NotificationOutboxItem> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        for (NotificationOutboxItem notification : notifications) {
            jdbcTemplate.update("""
                    insert into notification_outbox (
                      tenant_id, tenant_slug, consultation_id, event_id, notification_type, recipient_role, recipient_email,
                      subject, body, ics_content, status, attempts, next_attempt_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, now(), now(), now())
                    on conflict (tenant_id, consultation_id, event_id, notification_type, recipient_role) do nothing
                    """,
                    tenantId,
                    tenantSlug,
                    UUID.fromString(notification.consultationId()),
                    UUID.fromString(notification.eventId()),
                    notification.type(),
                    notification.recipientRole(),
                    notification.recipientEmail(),
                    notification.subject(),
                    notification.body(),
                    notification.icsContent());
        }
    }

    private NotificationOutboxItem mapNotification(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationOutboxItem(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("tenant_slug"),
                rs.getObject("consultation_id", UUID.class).toString(),
                rs.getObject("event_id", UUID.class).toString(),
                rs.getString("notification_type"),
                rs.getString("recipient_role"),
                rs.getString("recipient_email"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("ics_content"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("provider_message_id"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("next_attempt_at").toInstant()
        );
    }

    private UUID ensureTenant(String tenantSlug, String displayName) {
        return jdbcTemplate.queryForObject("""
                insert into tenants (slug, display_name)
                values (?, ?)
                on conflict (slug) do update set display_name = excluded.display_name
                returning id
                """, UUID.class, tenantSlug, displayName);
    }

    private UUID createTenant(String tenantSlug, String displayName) {
        return jdbcTemplate.queryForObject("""
                insert into tenants (slug, display_name)
                values (?, ?)
                returning id
                """, UUID.class, tenantSlug, displayName);
    }

    private void setTenantContext(String tenantSlug) {
        jdbcTemplate.queryForObject("select set_config('app.tenant_slug', ?, true)", String.class, tenantSlug);
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

    private UUID uuidOrNull(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize intake data", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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

