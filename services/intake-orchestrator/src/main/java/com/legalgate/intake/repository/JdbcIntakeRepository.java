package com.legalgate.intake.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.DiagnosticsMessage;
import com.legalgate.intake.model.DiagnosticsSession;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerAvailabilityWindow;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.NotificationStatus;
import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Time;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
@ConditionalOnProperty(name = "legalgate.intake.persistence", havingValue = "jdbc")
class JdbcIntakeRepository implements IntakeRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<TenantRoutingRule>> ROUTING_RULE_LIST = new TypeReference<>() { };
    private static final int MAX_NOTIFICATION_ATTEMPTS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    JdbcIntakeRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TenantProvisioning> tenantForOrganization(String organizationId) {
        return queryTenant("select * from app_find_tenant_by_workos_organization(?)", organizationId);
    }

    @Override
    public Optional<TenantProvisioning> tenantForProvisioningOwner(String ownerId) {
        return queryTenant("select * from app_find_tenant_by_provisioning_owner(?)", ownerId);
    }

    @Override
    public Optional<String> tenantDisplayName(String tenantSlug) {
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            return jdbcTemplate.query("select display_name from tenants where slug = ?",
                            (rs, rowNum) -> rs.getString("display_name"), tenantSlug)
                    .stream()
                    .filter(Objects::nonNull)
                    .findFirst();
        });
    }

    @Override
    public TenantProvisioning startTenantProvisioning(String ownerId, String displayName, String slug, String intakeEmail) {
        Optional<TenantProvisioning> existing = tenantForProvisioningOwner(ownerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return transactionTemplate.execute(status -> {
                setTenantContext(slug);
                UUID id = jdbcTemplate.queryForObject("""
                        insert into tenants (slug, display_name, provisioning_owner_id, provisioning_status)
                        values (?, ?, ?, 'PENDING')
                        returning id
                        """, UUID.class, slug, displayName, ownerId);
                jdbcTemplate.update("""
                        insert into tenant_settings (tenant_id, intake_email, routing_rules, updated_at)
                        values (?, ?, '[]'::jsonb, now())
                        """, id, intakeEmail);
                return new TenantProvisioning(id.toString(), slug, displayName, null, "PENDING", ownerId);
            });
        } catch (DuplicateKeyException ex) {
            return tenantForProvisioningOwner(ownerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "firm_already_exists", ex));
        }
    }

    @Override
    public TenantProvisioning activateTenantProvisioning(String tenantId, String slug, String organizationId) {
        return transactionTemplate.execute(status -> {
            setTenantContext(slug);
            jdbcTemplate.update("""
                    update tenants
                    set workos_organization_id = ?, provisioning_status = 'ACTIVE', provisioning_error = null
                    where id = ?
                    """, organizationId, UUID.fromString(tenantId));
            return tenantForOrganization(organizationId).orElseThrow();
        });
    }

    @Override
    public void failTenantProvisioning(String tenantId, String slug, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            setTenantContext(slug);
            jdbcTemplate.update("""
                    update tenants set provisioning_status = 'FAILED', provisioning_error = ?
                    where id = ?
                    """, truncate(reason, 1000), UUID.fromString(tenantId));
        });
    }

    @Override
    public TenantSettingsResponse saveSettings(String tenantSlug, TenantSettingsResponse settings) {
        try {
            return transactionTemplate.execute(status -> {
                setTenantContext(tenantSlug);
                UUID tenantId = ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
                jdbcTemplate.update("""
                        insert into tenant_settings (
                          tenant_id, urgent_keywords, consultation_windows, urgency_levels,
                          destination_email, intake_email, routing_rules, diagnostics_prompt,
                          non_engagement_notice, updated_at
                        )
                        values (?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, cast(? as jsonb), ?, ?, now())
                        on conflict (tenant_id) do update set
                          urgent_keywords = excluded.urgent_keywords,
                          consultation_windows = excluded.consultation_windows,
                          urgency_levels = excluded.urgency_levels,
                          destination_email = excluded.destination_email,
                          intake_email = excluded.intake_email,
                          routing_rules = excluded.routing_rules,
                          diagnostics_prompt = excluded.diagnostics_prompt,
                          non_engagement_notice = excluded.non_engagement_notice,
                          updated_at = now()
                        """,
                        tenantId,
                        toJson(settings.urgentKeywords()),
                        toJson(settings.consultationWindows()),
                        toJson(settings.urgencyLevels()),
                        settings.destinationEmail(),
                        settings.intakeEmail(),
                        toJson(settings.routingRules()),
                        settings.diagnosticsPrompt(),
                        settings.nonEngagementNotice());
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
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            List<TenantSettingsResponse> settings = jdbcTemplate.query("""
                    select t.slug, s.urgent_keywords, s.consultation_windows, s.urgency_levels,
                           s.destination_email, s.intake_email, s.routing_rules,
                           s.diagnostics_prompt, s.non_engagement_notice
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
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            return jdbcTemplate.query(consultationSelect() + " where t.slug = ? and c.source_message_id = ?", this::mapConsultation, tenantSlug, sourceMessageId)
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public ConsultationResponse saveConsultation(
            String tenantSlug,
            ConsultationResponse consultation,
            List<EventResponse> eventsToUpdate,
            List<NotificationOutboxItem> notifications
    ) {
        try {
            return transactionTemplate.execute(status -> {
                setTenantContext(tenantSlug);
                UUID tenantId = ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
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
                    insertEvent(tenantId, consultationId, consultation, consultation.event());
                    jdbcTemplate.update("update consultations set event_id = ? where id = ?",
                            UUID.fromString(consultation.event().id()), consultationId);
                }
                updateEventsInCurrentTransaction(eventsToUpdate);
                insertNotifications(tenantId, tenantSlug, notifications);
                return consultation;
            });
        } catch (DuplicateKeyException ex) {
            return consultationForSourceMessageId(tenantSlug, consultation.sourceMessageId()).orElseThrow(() -> ex);
        }
    }

    @Override
    public ConsultationResponse updateConsultation(
            String tenantSlug,
            ConsultationResponse consultation,
            List<EventResponse> eventsToUpdate,
            List<NotificationOutboxItem> notifications
    ) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            UUID tenantId = ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            UUID consultationId = UUID.fromString(consultation.id());
            if (consultation.event() != null) {
                insertEvent(tenantId, consultationId, consultation, consultation.event());
            }
            jdbcTemplate.update("""
                    update consultations
                    set status = ?, urgency = ?, consultation_type = ?, assigned_lawyer_email = ?,
                        summary = ?, classification = cast(? as jsonb), notifications = cast(? as jsonb),
                        event_id = ?
                    where id = ?
                    """,
                    consultation.status(),
                    consultation.urgency(),
                    consultation.consultationType(),
                    consultation.assignedLawyerEmail(),
                    consultation.summary(),
                    toJson(consultation.classification()),
                    toJson(consultation.notifications()),
                    uuidOrNull(consultation.eventId()),
                    consultationId);
            updateEventsInCurrentTransaction(eventsToUpdate);
            insertNotifications(tenantId, tenantSlug, notifications);
            return consultation;
        });
    }

    @Override
    public Optional<ConsultationResponse> consultationById(String tenantSlug, String consultationId) {
        if (consultationId == null || consultationId.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            return jdbcTemplate.query(consultationSelect() + " where t.slug = ? and c.id = ?",
                            this::mapConsultation, tenantSlug, UUID.fromString(consultationId))
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public DiagnosticsSession saveDiagnosticsSession(
            String tenantSlug,
            DiagnosticsSession session,
            List<DiagnosticsMessage> messages,
            List<NotificationOutboxItem> notifications
    ) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            UUID tenantId = ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            UUID sessionId = session.id() == null ? UUID.randomUUID() : UUID.fromString(session.id());
            jdbcTemplate.update("""
                    insert into diagnostics_sessions (
                      id, tenant_id, tenant_slug, consultation_id, reply_token, status, verdict, reason,
                      extracted_summary, prompt_snapshot, original_email, rounds, attempts,
                      next_attempt_at, awaiting_reply_since, last_error, resolved_at, unfiltered_cause,
                      created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, now(), now())
                    on conflict (id) do update set
                      status = excluded.status,
                      verdict = excluded.verdict,
                      reason = excluded.reason,
                      extracted_summary = excluded.extracted_summary,
                      rounds = excluded.rounds,
                      attempts = excluded.attempts,
                      next_attempt_at = excluded.next_attempt_at,
                      awaiting_reply_since = excluded.awaiting_reply_since,
                      last_error = excluded.last_error,
                      resolved_at = excluded.resolved_at,
                      unfiltered_cause = excluded.unfiltered_cause,
                      updated_at = now()
                    """,
                    sessionId,
                    tenantId,
                    tenantSlug,
                    UUID.fromString(session.consultationId()),
                    session.replyToken(),
                    session.status(),
                    session.verdict(),
                    truncate(session.reason(), 4000),
                    session.extractedSummary(),
                    session.promptSnapshot(),
                    session.originalEmail() == null ? null : toJson(session.originalEmail()),
                    session.rounds(),
                    session.attempts(),
                    timestampOrNull(session.nextAttemptAt()),
                    timestampOrNull(session.awaitingReplySince()),
                    truncate(session.lastError(), 2000),
                    timestampOrNull(session.resolvedAt()),
                    session.unfilteredCause());
            for (DiagnosticsMessage message : messages == null ? List.<DiagnosticsMessage>of() : messages) {
                jdbcTemplate.update("""
                        insert into diagnostics_messages (tenant_id, session_id, role, body, created_at)
                        values (?, ?, ?, ?, now())
                        """, tenantId, sessionId, message.role(), message.body());
            }
            insertNotifications(tenantId, tenantSlug, notifications);
            return diagnosticsSessionById(sessionId).orElseThrow();
        });
    }

    @Override
    public Optional<DiagnosticsSession> diagnosticsSessionForConsultation(String tenantSlug, String consultationId) {
        if (consultationId == null || consultationId.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            return jdbcTemplate.query(diagnosticsSessionSelect() + " where consultation_id = ?",
                            this::mapDiagnosticsSession, UUID.fromString(consultationId))
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public Optional<DiagnosticsSession> diagnosticsSessionForReplyToken(String replyToken) {
        if (replyToken == null || replyToken.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext("__worker__");
            return jdbcTemplate.query(diagnosticsSessionSelect() + " where reply_token = ?",
                            this::mapDiagnosticsSession, replyToken)
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public List<DiagnosticsSession> claimDueDiagnosticsSessions(int limit) {
        return transactionTemplate.execute(status -> {
            setTenantContext("__worker__");
            return jdbcTemplate.query("""
                    -- Lease matches InMemoryIntakeRepository.DIAGNOSTICS_LEASE_SECONDS.
                    update diagnostics_sessions
                    set next_attempt_at = now() + interval '5 minutes', updated_at = now()
                    where id in (
                        select id
                        from diagnostics_sessions
                        where status = 'PENDING'
                          and next_attempt_at is not null
                          and next_attempt_at <= now()
                        order by next_attempt_at asc
                        limit ?
                        for update skip locked
                    )
                    returning
                    """ + DIAGNOSTICS_SESSION_COLUMNS,
                    this::mapDiagnosticsSession, Math.max(1, limit));
        });
    }

    @Override
    public List<DiagnosticsSession> silentDiagnosticsSessions(Instant threshold, int limit) {
        return transactionTemplate.execute(status -> {
            setTenantContext("__worker__");
            return jdbcTemplate.query(diagnosticsSessionSelect() + """
                     where status = 'PENDING' and awaiting_reply_since is not null and awaiting_reply_since < ?
                     order by awaiting_reply_since asc limit ?
                    """, this::mapDiagnosticsSession, Timestamp.from(threshold), Math.max(1, limit));
        });
    }

    @Override
    public List<DiagnosticsMessage> diagnosticsMessages(String tenantSlug, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            return jdbcTemplate.query("""
                    select id, role, body, created_at
                    from diagnostics_messages
                    where session_id = ?
                    order by created_at asc, id asc
                    """, (rs, rowNum) -> new DiagnosticsMessage(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("role"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant()
            ), UUID.fromString(sessionId));
        });
    }

    @Override
    public int purgeDiagnosticsTranscripts(Instant cutoff) {
        return transactionTemplate.execute(status -> {
            setTenantContext("__worker__");
            jdbcTemplate.update("""
                    delete from diagnostics_messages
                    where session_id in (
                        select id from diagnostics_sessions
                        where status in ('REJECTED', 'ABANDONED')
                          and resolved_at is not null and resolved_at < ?
                          and transcript_purged_at is null
                    )
                    """, Timestamp.from(cutoff));
            return jdbcTemplate.update("""
                    update diagnostics_sessions
                    set original_email = null, transcript_purged_at = now(), updated_at = now()
                    where status in ('REJECTED', 'ABANDONED')
                      and resolved_at is not null and resolved_at < ?
                      and transcript_purged_at is null
                    """, Timestamp.from(cutoff));
        });
    }

    private Optional<DiagnosticsSession> diagnosticsSessionById(UUID sessionId) {
        return jdbcTemplate.query(diagnosticsSessionSelect() + " where id = ?", this::mapDiagnosticsSession, sessionId)
                .stream()
                .findFirst();
    }

    /**
     * Every column {@link #mapDiagnosticsSession} reads, in one place. The claim query returns
     * these rather than selecting them, and a list that drifts from the mapper fails only against
     * Postgres — so the two queries share this instead of each spelling it out.
     */
    private static final String DIAGNOSTICS_SESSION_COLUMNS = """
            id, tenant_slug, consultation_id, reply_token, status, verdict, reason,
            extracted_summary, prompt_snapshot, original_email, rounds, attempts,
            next_attempt_at, awaiting_reply_since, last_error, resolved_at, created_at,
            unfiltered_cause""";

    private String diagnosticsSessionSelect() {
        return "select " + DIAGNOSTICS_SESSION_COLUMNS + "\nfrom diagnostics_sessions\n";
    }

    private DiagnosticsSession mapDiagnosticsSession(ResultSet rs, int rowNum) throws SQLException {
        String originalEmail = rs.getString("original_email");
        return new DiagnosticsSession(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("tenant_slug"),
                rs.getObject("consultation_id", UUID.class).toString(),
                rs.getString("reply_token"),
                rs.getString("status"),
                rs.getString("verdict"),
                rs.getString("reason"),
                rs.getString("extracted_summary"),
                rs.getString("prompt_snapshot"),
                originalEmail == null ? null : fromJson(originalEmail, ConsultationClassifierRequest.InboundEmail.class),
                rs.getInt("rounds"),
                rs.getInt("attempts"),
                instantOrNull(rs.getTimestamp("next_attempt_at")),
                instantOrNull(rs.getTimestamp("awaiting_reply_since")),
                rs.getString("last_error"),
                instantOrNull(rs.getTimestamp("resolved_at")),
                instantOrNull(rs.getTimestamp("created_at")),
                rs.getString("unfiltered_cause")
        );
    }

    private Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    @Override
    public ConsultationListResponse consultationsForTenant(String tenantSlug) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
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
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            return jdbcTemplate.query(consultationSelect() + " where t.slug = ? and c.event_id = ?", this::mapConsultation, tenantSlug, UUID.fromString(eventId))
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public List<LawyerProfile> lawyersForTenant(String tenantSlug) {
        return transactionTemplate.execute(status -> {
            setTenantContext(tenantSlug);
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
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
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
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
            ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            updateEventsInCurrentTransaction(events);
        });
    }

    @Override
    public void queueNotifications(String tenantSlug, List<NotificationOutboxItem> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            setTenantContext(tenantSlug);
            UUID tenantId = ensureTenant(tenantSlug, fallbackDisplayName(tenantSlug));
            insertNotifications(tenantId, tenantSlug, notifications);
        });
    }

    @Override
    public List<NotificationOutboxItem> claimPendingNotifications(int limit) {
        return transactionTemplate.execute(status -> {
            setTenantContext("__worker__");
            List<NotificationOutboxItem> notifications = jdbcTemplate.query("""
                    update notification_outbox
                    set status = 'SENDING',
                        next_attempt_at = now() + interval '5 minutes',
                        updated_at = now()
                    where id in (
                        select id
                        from notification_outbox
                        where status in ('PENDING', 'FAILED', 'SENDING')
                          and attempts < ?
                          and next_attempt_at <= now()
                        order by created_at asc
                        limit ?
                        for update skip locked
                    )
                    returning id, tenant_slug, consultation_id, event_id, notification_type, recipient_role, recipient_email,
                              from_email, subject, body, html_body, ics_content, status, attempts, provider_message_id,
                              last_error, created_at, updated_at, next_attempt_at
                    """, this::mapNotification, MAX_NOTIFICATION_ATTEMPTS, Math.max(1, limit));
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
                    set status = case when attempts + 1 >= ? then 'DEAD' else 'FAILED' end,
                        attempts = attempts + 1,
                        last_error = ?,
                        next_attempt_at = case
                            when attempts + 1 >= ? then now()
                            else now() + (least(3600, power(2, least(10, attempts + 1))::int * 60) || ' seconds')::interval
                        end,
                        updated_at = now()
                    where id = ?
                    """, MAX_NOTIFICATION_ATTEMPTS, truncate(errorMessage, 2000), MAX_NOTIFICATION_ATTEMPTS, UUID.fromString(notificationId));
        });
    }

    private void insertEvent(UUID tenantId, UUID consultationId, ConsultationResponse consultation, EventResponse event) {
        jdbcTemplate.update("""
                insert into events (
                  id, tenant_id, lawyer_id, consultation_id, route_name, route_id_snapshot,
                  urgency_name, sla_days, sla_deadline, priority_score, scheduled_start,
                  scheduled_end, meeting_url, scheduled_within_sla, status, source, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (id) do nothing
                """,
                UUID.fromString(event.id()),
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
    }

    private void updateEventsInCurrentTransaction(List<EventResponse> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
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
                lawyersForCurrentTenant(),
                rs.getString("diagnostics_prompt"),
                rs.getString("non_engagement_notice")
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
                      from_email, subject, body, html_body, ics_content, status, attempts, next_attempt_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, now(), now(), now())
                    on conflict (tenant_id, consultation_id, event_id, notification_type, recipient_role)
                    where status in ('PENDING', 'SENDING', 'FAILED') and event_id is not null
                    do nothing
                    """,
                    tenantId,
                    tenantSlug,
                    UUID.fromString(notification.consultationId()),
                    uuidOrNull(notification.eventId()),
                    notification.type(),
                    notification.recipientRole(),
                    notification.recipientEmail(),
                    notification.fromEmail(),
                    notification.subject(),
                    notification.body(),
                    notification.htmlBody(),
                    notification.icsContent());
        }
    }

    private NotificationOutboxItem mapNotification(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationOutboxItem(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("tenant_slug"),
                rs.getObject("consultation_id", UUID.class).toString(),
                rs.getObject("event_id") == null ? null : rs.getObject("event_id", UUID.class).toString(),
                rs.getString("notification_type"),
                rs.getString("recipient_role"),
                rs.getString("recipient_email"),
                rs.getString("from_email"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("html_body"),
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

    private Optional<TenantProvisioning> queryTenant(String sql, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return transactionTemplate.execute(status -> jdbcTemplate.query(sql, (rs, rowNum) ->
                new TenantProvisioning(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getString("slug"),
                        rs.getString("display_name"),
                        rs.getString("workos_organization_id"),
                        rs.getString("provisioning_status"),
                        rs.getString("provisioning_owner_id")
                ), value).stream().findFirst());
    }

    private UUID ensureTenant(String tenantSlug, String displayName) {
        // The seeded name is only for rows this call creates: an existing display_name is the firm's
        // own name, shown to potential clients, and must survive every write that passes through here.
        return jdbcTemplate.queryForObject("""
                insert into tenants (slug, display_name)
                values (?, ?)
                on conflict (slug) do update set display_name = tenants.display_name
                returning id
                """, UUID.class, tenantSlug, displayName);
    }

    private void setTenantContext(String tenantSlug) {
        jdbcTemplate.queryForObject("select set_config('app.tenant_slug', ?, true)", String.class, tenantSlug);
    }

    /** Seed name for a tenant row created before onboarding named the firm. */
    private String fallbackDisplayName(String tenantSlug) {
        return tenantSlug.replace('-', ' ');
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

