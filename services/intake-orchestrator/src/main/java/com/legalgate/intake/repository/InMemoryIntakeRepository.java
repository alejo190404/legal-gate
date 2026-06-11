package com.legalgate.intake.repository;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
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

    private final ConcurrentMap<String, TenantSettingsResponse> tenantSettings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ConsultationResponse>> consultationsByTenant = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StoredUserCredentials> usersByEmail = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> lastLoginAtByEmail = new ConcurrentHashMap<>();

    @Override
    public RegistrationResponse registerFirmOwner(String firmSlug, String firmName, String email, String hashedPassword, String role) {
        StoredUserCredentials user = new StoredUserCredentials(
                email,
                firmSlug,
                firmName + " admin",
                role,
                hashedPassword
        );
        StoredUserCredentials existing = usersByEmail.putIfAbsent(email, user);
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email_already_registered");
        }
        consultationsByTenant.putIfAbsent(firmSlug, new ArrayList<>());
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
        tenantSettings.put(tenantSlug, settings);
        return settings;
    }

    @Override
    public TenantSettingsResponse settingsFor(String tenantSlug, TenantSettingsResponse defaultSettings) {
        return tenantSettings.getOrDefault(tenantSlug, defaultSettings);
    }

    @Override
    public ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation) {
        consultationsByTenant.computeIfAbsent(tenantSlug, ignored -> new ArrayList<>()).add(consultation);
        return consultation;
    }

    @Override
    public ConsultationListResponse consultationsForTenant(String tenantSlug) {
        List<ConsultationResponse> consultations = consultationsByTenant.getOrDefault(tenantSlug, List.of());
        return new ConsultationListResponse(tenantSlug, List.copyOf(consultations));
    }
}
