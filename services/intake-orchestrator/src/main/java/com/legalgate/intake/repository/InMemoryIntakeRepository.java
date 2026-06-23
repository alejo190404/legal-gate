package com.legalgate.intake.repository;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerAvailabilityWindow;
import com.legalgate.intake.model.LawyerProfile;
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
    private final ConcurrentMap<String, List<EventResponse>> eventsByTenant = new ConcurrentHashMap<>();
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
        eventsByTenant.putIfAbsent(firmSlug, new ArrayList<>());
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
    public ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation) {
        Optional<ConsultationResponse> existing = consultationForSourceMessageId(tenantSlug, consultation.sourceMessageId());
        if (existing.isPresent()) {
            return existing.get();
        }
        consultationsByTenant.computeIfAbsent(tenantSlug, ignored -> new ArrayList<>()).add(consultation);
        if (consultation.event() != null) {
            eventsByTenant.computeIfAbsent(tenantSlug, ignored -> new ArrayList<>()).add(consultation.event());
        }
        return consultation;
    }

    @Override
    public ConsultationListResponse consultationsForTenant(String tenantSlug) {
        return new ConsultationListResponse(tenantSlug, List.copyOf(consultationsByTenant.getOrDefault(tenantSlug, List.of())));
    }

    @Override
    public List<LawyerProfile> lawyersForTenant(String tenantSlug) {
        TenantSettingsResponse settings = tenantSettings.get(tenantSlug);
        return settings == null || settings.lawyers() == null ? List.of() : settings.lawyers();
    }

    @Override
    public List<EventResponse> eventsForLawyer(String tenantSlug, String lawyerId) {
        return eventsByTenant.getOrDefault(tenantSlug, List.of()).stream()
                .filter(event -> lawyerId != null && lawyerId.equals(event.lawyerId()))
                .toList();
    }

    @Override
    public void updateEvents(String tenantSlug, List<EventResponse> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<EventResponse> stored = eventsByTenant.computeIfAbsent(tenantSlug, ignored -> new ArrayList<>());
        for (EventResponse event : events) {
            for (int index = 0; index < stored.size(); index++) {
                if (stored.get(index).id().equals(event.id())) {
                    stored.set(index, event);
                    break;
                }
            }
        }
    }

    private List<LawyerAvailabilityWindow> defaultAvailability() {
        List<LawyerAvailabilityWindow> windows = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            windows.add(new LawyerAvailabilityWindow(day, "09:00", "17:00", "America/Bogota"));
        }
        return List.copyOf(windows);
    }
}

