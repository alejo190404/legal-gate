package com.legalgate.intake.repository;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.DiagnosticsMessage;
import com.legalgate.intake.model.DiagnosticsSession;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerAvailabilityWindow;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
@ConditionalOnProperty(name = "legalgate.intake.persistence", havingValue = "memory", matchIfMissing = true)
class InMemoryIntakeRepository implements IntakeRepository {

    private static final int MAX_NOTIFICATION_ATTEMPTS = 5;
    private static final long NOTIFICATION_LEASE_SECONDS = 300;
    private static final long DIAGNOSTICS_LEASE_SECONDS = 300;

    private final ConcurrentMap<String, TenantSettingsResponse> tenantSettings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ConsultationResponse>> consultationsByTenant = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, EventResponse>> eventsByTenant = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, NotificationOutboxItem> notificationsByDedupeKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TenantProvisioning> tenantsByOwner = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TenantProvisioning> tenantsByOrganization = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DiagnosticsSession> diagnosticsSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<DiagnosticsMessage>> diagnosticsTranscripts = new ConcurrentHashMap<>();

    @Override
    public Optional<TenantProvisioning> tenantForOrganization(String organizationId) {
        return Optional.ofNullable(tenantsByOrganization.get(organizationId));
    }

    @Override
    public Optional<TenantProvisioning> tenantForProvisioningOwner(String ownerId) {
        return Optional.ofNullable(tenantsByOwner.get(ownerId));
    }

    @Override
    public TenantProvisioning startTenantProvisioning(String ownerId, String displayName, String slug, String intakeEmail) {
        return tenantsByOwner.computeIfAbsent(ownerId, ignored -> {
            TenantProvisioning tenant = new TenantProvisioning(
                    java.util.UUID.randomUUID().toString(), slug, displayName, null, "PENDING", ownerId);
            tenantSettings.put(slug, new TenantSettingsResponse(
                    slug, List.of(), List.of(), List.of(), null, intakeEmail, List.of(), List.of()));
            consultationsByTenant.putIfAbsent(slug, new ArrayList<>());
            eventsByTenant.putIfAbsent(slug, new ConcurrentHashMap<>());
            return tenant;
        });
    }

    @Override
    public TenantProvisioning activateTenantProvisioning(String tenantId, String slug, String organizationId) {
        TenantProvisioning current = tenantsByOwner.values().stream()
                .filter(tenant -> tenant.id().equals(tenantId))
                .findFirst()
                .orElseThrow();
        TenantProvisioning active = new TenantProvisioning(
                current.id(), current.slug(), current.displayName(), organizationId, "ACTIVE", current.ownerId());
        tenantsByOwner.put(current.ownerId(), active);
        tenantsByOrganization.put(organizationId, active);
        return active;
    }

    @Override
    public void failTenantProvisioning(String tenantId, String slug, String reason) {
        tenantsByOwner.replaceAll((owner, tenant) -> tenant.id().equals(tenantId)
                ? new TenantProvisioning(tenant.id(), tenant.slug(), tenant.displayName(),
                        tenant.organizationId(), "FAILED", tenant.ownerId())
                : tenant);
    }

    @Override
    public TenantSettingsResponse saveSettings(String tenantSlug, TenantSettingsResponse settings) {
        if (settings.intakeEmail() != null) {
            tenantSettings.forEach((existingTenantSlug, existingSettings) -> {
                if (!tenantSlug.equals(existingTenantSlug)
                        && settings.intakeEmail().equalsIgnoreCase(existingSettings.intakeEmail())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "intake_email_already_configured");
                }
            });
        }
        tenantSettings.put(tenantSlug, settings);
        return settings;
    }

    @Override
    public TenantSettingsResponse settingsFor(String tenantSlug, TenantSettingsResponse defaultSettings) {
        return tenantSettings.getOrDefault(tenantSlug, defaultSettings);
    }

    @Override
    public Optional<String> tenantSlugForIntakeEmail(String intakeEmail) {
        if (intakeEmail == null || intakeEmail.isBlank()) {
            return Optional.empty();
        }
        return tenantSettings.values().stream()
                .filter(settings -> settings.intakeEmail() != null)
                .filter(settings -> settings.intakeEmail().equalsIgnoreCase(intakeEmail.trim()))
                .map(TenantSettingsResponse::tenantId)
                .findFirst();
    }

    @Override
    public Optional<ConsultationResponse> consultationForSourceMessageId(String tenantSlug, String sourceMessageId) {
        if (sourceMessageId == null || sourceMessageId.isBlank()) {
            return Optional.empty();
        }
        return consultationsByTenant.getOrDefault(tenantSlug, List.of()).stream()
                .filter(consultation -> sourceMessageId.equals(consultation.sourceMessageId()))
                .findFirst();
    }

    @Override
    public ConsultationResponse saveConsultation(
            String tenantSlug,
            ConsultationResponse consultation,
            List<EventResponse> eventsToUpdate,
            List<NotificationOutboxItem> notifications
    ) {
        Optional<ConsultationResponse> existing = consultationForSourceMessageId(tenantSlug, consultation.sourceMessageId());
        if (existing.isPresent()) {
            return existing.get();
        }
        consultationsByTenant.computeIfAbsent(tenantSlug, ignored -> new ArrayList<>()).add(consultation);
        if (consultation.event() != null) {
            eventsByTenant.computeIfAbsent(tenantSlug, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(consultation.event().id(), consultation.event());
        }
        updateEvents(tenantSlug, eventsToUpdate);
        queueNotifications(tenantSlug, notifications);
        return consultation;
    }

    @Override
    public ConsultationResponse updateConsultation(
            String tenantSlug,
            ConsultationResponse consultation,
            List<EventResponse> eventsToUpdate,
            List<NotificationOutboxItem> notifications
    ) {
        List<ConsultationResponse> consultations = consultationsByTenant.computeIfAbsent(tenantSlug, ignored -> new ArrayList<>());
        for (int index = 0; index < consultations.size(); index++) {
            if (consultations.get(index).id().equals(consultation.id())) {
                consultations.set(index, consultation);
            }
        }
        if (consultation.event() != null) {
            eventsByTenant.computeIfAbsent(tenantSlug, ignored -> new ConcurrentHashMap<>())
                    .put(consultation.event().id(), consultation.event());
        }
        updateEvents(tenantSlug, eventsToUpdate);
        queueNotifications(tenantSlug, notifications);
        return consultation;
    }

    @Override
    public ConsultationListResponse consultationsForTenant(String tenantSlug) {
        return new ConsultationListResponse(tenantSlug, List.copyOf(consultationsByTenant.getOrDefault(tenantSlug, List.of())));
    }

    @Override
    public Optional<ConsultationResponse> consultationById(String tenantSlug, String consultationId) {
        if (consultationId == null || consultationId.isBlank()) {
            return Optional.empty();
        }
        return consultationsByTenant.getOrDefault(tenantSlug, List.of()).stream()
                .filter(consultation -> consultationId.equals(consultation.id()))
                .findFirst();
    }

    @Override
    public DiagnosticsSession saveDiagnosticsSession(
            String tenantSlug,
            DiagnosticsSession session,
            List<DiagnosticsMessage> messages,
            List<NotificationOutboxItem> notifications
    ) {
        String id = session.id() == null ? java.util.UUID.randomUUID().toString() : session.id();
        DiagnosticsSession stored = new DiagnosticsSession(
                id, tenantSlug, session.consultationId(), session.replyToken(), session.status(), session.verdict(),
                session.reason(), session.extractedSummary(), session.promptSnapshot(), session.originalEmail(),
                session.rounds(), session.attempts(), session.nextAttemptAt(), session.awaitingReplySince(),
                session.lastError(), session.resolvedAt(),
                session.createdAt() == null ? Instant.now() : session.createdAt());
        diagnosticsSessions.put(id, stored);
        if (messages != null && !messages.isEmpty()) {
            List<DiagnosticsMessage> transcript = diagnosticsTranscripts.computeIfAbsent(id, ignored -> new ArrayList<>());
            for (DiagnosticsMessage message : messages) {
                transcript.add(new DiagnosticsMessage(
                        java.util.UUID.randomUUID().toString(), message.role(), message.body(), Instant.now()));
            }
        }
        queueNotifications(tenantSlug, notifications);
        return stored;
    }

    @Override
    public Optional<DiagnosticsSession> diagnosticsSessionForConsultation(String tenantSlug, String consultationId) {
        return diagnosticsSessions.values().stream()
                .filter(session -> session.tenantId().equals(tenantSlug))
                .filter(session -> session.consultationId().equals(consultationId))
                .findFirst();
    }

    @Override
    public Optional<DiagnosticsSession> diagnosticsSessionForReplyToken(String replyToken) {
        if (replyToken == null || replyToken.isBlank()) {
            return Optional.empty();
        }
        return diagnosticsSessions.values().stream()
                .filter(session -> replyToken.equals(session.replyToken()))
                .findFirst();
    }

    @Override
    public List<DiagnosticsSession> claimDueDiagnosticsSessions(int limit) {
        Instant now = Instant.now();
        List<DiagnosticsSession> claimed = diagnosticsSessions.values().stream()
                .filter(DiagnosticsSession::isPending)
                .filter(session -> session.nextAttemptAt() != null && !session.nextAttemptAt().isAfter(now))
                .limit(Math.max(1, limit))
                .toList();
        for (DiagnosticsSession session : claimed) {
            diagnosticsSessions.put(session.id(), session.dueNow(now.plusSeconds(DIAGNOSTICS_LEASE_SECONDS)));
        }
        return claimed;
    }

    @Override
    public List<DiagnosticsSession> silentDiagnosticsSessions(Instant threshold, int limit) {
        return diagnosticsSessions.values().stream()
                .filter(DiagnosticsSession::isPending)
                .filter(session -> session.awaitingReplySince() != null && session.awaitingReplySince().isBefore(threshold))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<DiagnosticsMessage> diagnosticsMessages(String tenantSlug, String sessionId) {
        return List.copyOf(diagnosticsTranscripts.getOrDefault(sessionId, List.of()));
    }

    @Override
    public int purgeDiagnosticsTranscripts(Instant cutoff) {
        int purged = 0;
        for (DiagnosticsSession session : diagnosticsSessions.values()) {
            boolean terminal = List.of(DiagnosticsSession.REJECTED, DiagnosticsSession.ABANDONED).contains(session.status());
            if (terminal && session.resolvedAt() != null && session.resolvedAt().isBefore(cutoff)
                    && diagnosticsTranscripts.containsKey(session.id())) {
                diagnosticsTranscripts.remove(session.id());
                diagnosticsSessions.put(session.id(), session.withTranscriptPurged());
                purged++;
            }
        }
        return purged;
    }

    @Override
    public Optional<ConsultationResponse> consultationForEventId(String tenantSlug, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        return consultationsByTenant.getOrDefault(tenantSlug, List.of()).stream()
                .filter(consultation -> eventId.equals(consultation.eventId()))
                .findFirst();
    }

    @Override
    public List<LawyerProfile> lawyersForTenant(String tenantSlug) {
        TenantSettingsResponse settings = tenantSettings.get(tenantSlug);
        return settings == null || settings.lawyers() == null ? List.of() : settings.lawyers();
    }

    @Override
    public List<EventResponse> eventsForLawyer(String tenantSlug, String lawyerId) {
        ConcurrentMap<String, EventResponse> events = eventsByTenant.get(tenantSlug);
        if (events == null) {
            return List.of();
        }
        return events.values().stream()
                .filter(event -> lawyerId != null && lawyerId.equals(event.lawyerId()))
                .toList();
    }

    @Override
    public void updateEvents(String tenantSlug, List<EventResponse> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        ConcurrentMap<String, EventResponse> stored = eventsByTenant.computeIfAbsent(tenantSlug, ignored -> new ConcurrentHashMap<>());
        for (EventResponse event : events) {
            stored.computeIfPresent(event.id(), (ignored, existing) -> event);
            List<ConsultationResponse> consultations = consultationsByTenant.getOrDefault(tenantSlug, List.of());
            for (int index = 0; index < consultations.size(); index++) {
                ConsultationResponse consultation = consultations.get(index);
                if (event.id().equals(consultation.eventId())) {
                    consultations.set(index, new ConsultationResponse(
                            consultation.id(), consultation.tenantId(), consultation.clientName(), consultation.clientEmail(),
                            consultation.summary(), consultation.preferredWindow(), consultation.status(), consultation.urgency(),
                            consultation.consultationType(), consultation.assignedLawyerEmail(), consultation.classification(),
                            consultation.notifications(), consultation.sourceEventId(), consultation.sourceMessageId(),
                            consultation.createdAt(), consultation.eventId(), event
                    ));
                }
            }
        }
    }

    @Override
    public void queueNotifications(String tenantSlug, List<NotificationOutboxItem> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (NotificationOutboxItem notification : notifications) {
            String id = notification.id() == null || notification.id().isBlank()
                    ? java.util.UUID.randomUUID().toString()
                    : notification.id();
            // Scheduling notifications dedupe by event; a diagnostics message has no event and
            // is one per round, so it keys on its own id and is never suppressed.
            String dedupeKey = notification.eventId() == null
                    ? id
                    : tenantSlug + ":" + notification.consultationId() + ":" + notification.eventId()
                            + ":" + notification.type() + ":" + notification.recipientRole();
            NotificationOutboxItem queued = new NotificationOutboxItem(
                    id,
                    tenantSlug,
                    notification.consultationId(),
                    notification.eventId(),
                    notification.type(),
                    notification.recipientRole(),
                    notification.recipientEmail(),
                    notification.fromEmail(),
                    notification.subject(),
                    notification.body(),
                    notification.htmlBody(),
                    notification.icsContent(),
                    "PENDING",
                    0,
                    null,
                    null,
                    now,
                    now,
                    now
            );
            notificationsByDedupeKey.compute(dedupeKey, (ignored, existing) -> shouldSuppressDuplicate(existing) ? existing : queued);
        }
    }

    @Override
    public List<NotificationOutboxItem> claimPendingNotifications(int limit) {
        Instant now = Instant.now();
        return notificationsByDedupeKey.entrySet().stream()
                .filter(entry -> isClaimable(entry.getValue()))
                .filter(entry -> entry.getValue().nextAttemptAt() == null || !entry.getValue().nextAttemptAt().isAfter(now))
                .limit(Math.max(1, limit))
                .map(entry -> {
                    NotificationOutboxItem notification = entry.getValue();
                    NotificationOutboxItem claimed = new NotificationOutboxItem(
                            notification.id(), notification.tenantId(), notification.consultationId(), notification.eventId(),
                            notification.type(), notification.recipientRole(), notification.recipientEmail(), notification.fromEmail(), notification.subject(), notification.body(),
                            notification.htmlBody(), notification.icsContent(), "SENDING", notification.attempts(), notification.providerMessageId(),
                            notification.lastError(), notification.createdAt(), now, now.plusSeconds(NOTIFICATION_LEASE_SECONDS)
                    );
                    entry.setValue(claimed);
                    return claimed;
                })
                .toList();
    }

    @Override
    public void markNotificationSent(String notificationId, String providerMessageId) {
        notificationsByDedupeKey.replaceAll((key, notification) -> notificationId.equals(notification.id())
                ? new NotificationOutboxItem(
                        notification.id(), notification.tenantId(), notification.consultationId(), notification.eventId(),
                        notification.type(), notification.recipientRole(), notification.recipientEmail(), notification.fromEmail(), notification.subject(), notification.body(),
                        notification.htmlBody(), notification.icsContent(), "SENT", notification.attempts(), providerMessageId, null,
                        notification.createdAt(), Instant.now(), notification.nextAttemptAt()
                )
                : notification);
    }

    @Override
    public void markNotificationFailed(String notificationId, String errorMessage) {
        Instant now = Instant.now();
        notificationsByDedupeKey.replaceAll((key, notification) -> {
            if (!notificationId.equals(notification.id())) {
                return notification;
            }
            int attempts = notification.attempts() + 1;
            boolean exhausted = attempts >= MAX_NOTIFICATION_ATTEMPTS;
            return new NotificationOutboxItem(
                    notification.id(), notification.tenantId(), notification.consultationId(), notification.eventId(),
                    notification.type(), notification.recipientRole(), notification.recipientEmail(), notification.fromEmail(), notification.subject(), notification.body(),
                    notification.htmlBody(), notification.icsContent(), exhausted ? "DEAD" : "FAILED", attempts, notification.providerMessageId(),
                    errorMessage, notification.createdAt(), now, exhausted ? now : now.plusSeconds(retryDelaySeconds(attempts))
            );
        });
    }

    private boolean shouldSuppressDuplicate(NotificationOutboxItem existing) {
        return existing != null && List.of("PENDING", "SENDING", "FAILED").contains(existing.status());
    }

    private boolean isClaimable(NotificationOutboxItem notification) {
        return List.of("PENDING", "FAILED", "SENDING").contains(notification.status())
                && notification.attempts() < MAX_NOTIFICATION_ATTEMPTS;
    }

    private long retryDelaySeconds(int attempts) {
        return Math.min(3600, (long) Math.pow(2, Math.min(10, attempts)) * 60);
    }

    private List<LawyerAvailabilityWindow> defaultAvailability() {
        List<LawyerAvailabilityWindow> windows = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            windows.add(new LawyerAvailabilityWindow(day, "09:00", "17:00", "America/Bogota"));
        }
        return List.copyOf(windows);
    }
}

