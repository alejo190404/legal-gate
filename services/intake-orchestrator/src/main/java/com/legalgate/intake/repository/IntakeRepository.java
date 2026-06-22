package com.legalgate.intake.repository;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.util.Optional;

public interface IntakeRepository {
    RegistrationResponse registerFirmOwner(
            String firmSlug,
            String firmName,
            String email,
            String hashedPassword,
            String role,
            String intakeEmail
    );

    Optional<StoredUserCredentials> findActiveUserByEmail(String email);

    void recordSuccessfulLogin(String email);

    TenantSettingsResponse saveSettings(String tenantSlug, TenantSettingsResponse settings);

    TenantSettingsResponse settingsFor(String tenantSlug, TenantSettingsResponse defaultSettings);

    Optional<String> tenantSlugForIntakeEmail(String intakeEmail);

    Optional<ConsultationResponse> consultationForSourceMessageId(String tenantSlug, String sourceMessageId);

    ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation);

    ConsultationListResponse consultationsForTenant(String tenantSlug);
}
