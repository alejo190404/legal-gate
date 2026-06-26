package com.legalgate.intake.repository;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.util.List;
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

    default ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation) {
        return saveConsultation(tenantSlug, consultation, List.of());
    }

    default ConsultationResponse saveConsultation(String tenantSlug, ConsultationResponse consultation, List<NotificationOutboxItem> notifications) {
        return saveConsultation(tenantSlug, consultation, List.of(), notifications);
    }

    ConsultationResponse saveConsultation(
            String tenantSlug,
            ConsultationResponse consultation,
            List<EventResponse> eventsToUpdate,
            List<NotificationOutboxItem> notifications
    );

    ConsultationListResponse consultationsForTenant(String tenantSlug);

    Optional<ConsultationResponse> consultationForEventId(String tenantSlug, String eventId);

    List<LawyerProfile> lawyersForTenant(String tenantSlug);

    List<EventResponse> eventsForLawyer(String tenantSlug, String lawyerId);

    void updateEvents(String tenantSlug, List<EventResponse> events);

    void queueNotifications(String tenantSlug, List<NotificationOutboxItem> notifications);

    List<NotificationOutboxItem> claimPendingNotifications(int limit);

    void markNotificationSent(String notificationId, String providerMessageId);

    void markNotificationFailed(String notificationId, String errorMessage);
}
