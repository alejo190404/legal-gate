package com.legalgate.intake.repository;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerAvailabilityWindow;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.model.UrgencyDefinition;
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

    private final ConcurrentMap<String, TenantSettingsResponse> tenantSettings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ConsultationResponse>> consultationsByTenant = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, EventResponse>> eventsByTenant = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, NotificationOutboxItem> notificationsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StoredUserCredentials> usersByEmail = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> tenantsBySlug = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> lastLoginAtByEmail = new ConcurrentHashMap<>();

    @Override
    public RegistrationResponse registerFirmOwner(
            String firmSlug,
            String firmName,
            String email,
            String hashedPassword,
            String role,
            String intakeEmail
    ) {
        if (tenantsBySlug.putIfAbsent(firmSlug, Boolean.TRUE) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_slug_already_registered");
        }
        StoredUserCredentials user = new StoredUserCredentials(email, firmSlug, firmName + " admin", role, hashedPassword);
        StoredUserCredentials existing = usersByEmail.putIfAbsent(email, user);
        if (existing != null) {
            tenantsBySlug.remove(firmSlug);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email_already_registered");
        }
        LawyerProfile lawyer = new LawyerProfile(
                java.util.UUID.nameUUIDFromBytes(("lawyer:" + firmSlug + ":" + email).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                firmName + " abogado",
                email,
                true,
                60,
                defaultAvailability()
        );
        TenantRoutingRule rule = new TenantRoutingRule(
                "Default intake route",
                null,
                List.of("audiencia", "captura", "tutela", "vencimiento"),
                List.of(),
                List.of("NORMAL", "URGENT"),
                lawyer.id(),
                List.of(
                        new UrgencyDefinition("NORMAL", 1, 5, true),
                        new UrgencyDefinition("URGENT", 2, 1, true)
                ),
                email
        );
        tenantSettings.put(firmSlug, new TenantSettingsResponse(
                firmSlug,
                rule.urgentKeywords(),
                rule.consultationWindows(),
                rule.urgencyLevels(),
                rule.destinationEmail(),
                intakeEmail,
                List.of(rule),
                List.of(lawyer)
        ));
        consultationsByTenant.putIfAbsent(firmSlug, new ArrayList<>());
        eventsByTenant.putIfAbsent(firmSlug, new ConcurrentHashMap<>());
        return user.toSession();
    }

    @Override
    public Optional<StoredUserCredentials> findActiveUserByEmail(String email) {
        return Optional.ofNullable(usersByEmail.get(email));
    }

    @Override
    public void recordSuccessfulLogin(String email) {
        lastLoginAtByEmail.put(email, Instant.now());
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
    public ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation, List<NotificationOutboxItem> notifications) {
        Optional<ConsultationResponse> existing = consultationForSourceMessageId(tenantSlug, consultation.sourceMessageId());
        if (existing.isPresent()) {
            return existing.get();
        }
        consultationsByTenant.computeIfAbsent(tenantSlug, ignored -> new ArrayList<>()).add(consultation);
        if (consultation.event() != null) {
            eventsByTenant.computeIfAbsent(tenantSlug, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(consultation.event().id(), consultation.event());
        }
        queueNotifications(tenantSlug, notifications);
        return consultation;
    }

    @Override
    public ConsultationListResponse consultationsForTenant(String tenantSlug) {
        return new ConsultationListResponse(tenantSlug, List.copyOf(consultationsByTenant.getOrDefault(tenantSlug, List.of())));
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
            String dedupeKey = tenantSlug + ":" + notification.consultationId() + ":" + notification.eventId()
                    + ":" + notification.type() + ":" + notification.recipientRole();
            notificationsById.putIfAbsent(dedupeKey, new NotificationOutboxItem(
                    id,
                    tenantSlug,
                    notification.consultationId(),
                    notification.eventId(),
                    notification.type(),
                    notification.recipientRole(),
                    notification.recipientEmail(),
                    notification.subject(),
                    notification.body(),
                    notification.icsContent(),
                    "PENDING",
                    0,
                    null,
                    null,
                    now,
                    now,
                    now
            ));
        }
    }

    @Override
    public List<NotificationOutboxItem> claimPendingNotifications(int limit) {
        Instant now = Instant.now();
        return notificationsById.entrySet().stream()
                .filter(entry -> "PENDING".equals(entry.getValue().status()) || "FAILED".equals(entry.getValue().status()))
                .filter(entry -> entry.getValue().nextAttemptAt() == null || !entry.getValue().nextAttemptAt().isAfter(now))
                .limit(Math.max(1, limit))
                .map(entry -> {
                    NotificationOutboxItem notification = entry.getValue();
                    NotificationOutboxItem claimed = new NotificationOutboxItem(
                            notification.id(), notification.tenantId(), notification.consultationId(), notification.eventId(),
                            notification.type(), notification.recipientRole(), notification.recipientEmail(), notification.subject(), notification.body(),
                            notification.icsContent(), "SENDING", notification.attempts(), notification.providerMessageId(),
                            notification.lastError(), notification.createdAt(), now, notification.nextAttemptAt()
                    );
                    entry.setValue(claimed);
                    return claimed;
                })
                .toList();
    }

    @Override
    public void markNotificationSent(String notificationId, String providerMessageId) {
        notificationsById.replaceAll((key, notification) -> notificationId.equals(notification.id())
                ? new NotificationOutboxItem(
                        notification.id(), notification.tenantId(), notification.consultationId(), notification.eventId(),
                        notification.type(), notification.recipientRole(), notification.recipientEmail(), notification.subject(), notification.body(),
                        notification.icsContent(), "SENT", notification.attempts(), providerMessageId, null,
                        notification.createdAt(), Instant.now(), notification.nextAttemptAt()
                )
                : notification);
    }

    @Override
    public void markNotificationFailed(String notificationId, String errorMessage) {
        Instant now = Instant.now();
        notificationsById.replaceAll((key, notification) -> notificationId.equals(notification.id())
                ? new NotificationOutboxItem(
                        notification.id(), notification.tenantId(), notification.consultationId(), notification.eventId(),
                        notification.type(), notification.recipientRole(), notification.recipientEmail(), notification.subject(), notification.body(),
                        notification.icsContent(), "FAILED", notification.attempts() + 1, notification.providerMessageId(),
                        errorMessage, notification.createdAt(), now, now.plusSeconds(Math.min(3600, (long) Math.pow(2, Math.min(10, notification.attempts() + 1)) * 60))
                )
                : notification);
    }

    private List<LawyerAvailabilityWindow> defaultAvailability() {
        List<LawyerAvailabilityWindow> windows = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            windows.add(new LawyerAvailabilityWindow(day, "09:00", "17:00", "America/Bogota"));
        }
        return List.copyOf(windows);
    }
}

