package com.legalgate.intake.repository;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.DiagnosticsMessage;
import com.legalgate.intake.model.DiagnosticsSession;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.model.TenantSettingsResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IntakeRepository {
    Optional<TenantProvisioning> tenantForOrganization(String organizationId);

    Optional<TenantProvisioning> tenantForProvisioningOwner(String ownerId);

    TenantProvisioning startTenantProvisioning(String ownerId, String displayName, String slug, String intakeEmail);

    TenantProvisioning activateTenantProvisioning(String tenantId, String slug, String organizationId);

    void failTenantProvisioning(String tenantId, String slug, String reason);

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

    /** Applies an already-persisted Consultation's new state, e.g. when Diagnostics resolves. */
    ConsultationResponse updateConsultation(
            String tenantSlug,
            ConsultationResponse consultation,
            List<EventResponse> eventsToUpdate,
            List<NotificationOutboxItem> notifications
    );

    ConsultationListResponse consultationsForTenant(String tenantSlug);

    Optional<ConsultationResponse> consultationById(String tenantSlug, String consultationId);

    Optional<ConsultationResponse> consultationForEventId(String tenantSlug, String eventId);

    DiagnosticsSession saveDiagnosticsSession(
            String tenantSlug,
            DiagnosticsSession session,
            List<DiagnosticsMessage> messages,
            List<NotificationOutboxItem> notifications
    );

    Optional<DiagnosticsSession> diagnosticsSessionForConsultation(String tenantSlug, String consultationId);

    /** Resolves a client's reply to its Consultation. Not tenant-scoped: the token is the scope. */
    Optional<DiagnosticsSession> diagnosticsSessionForReplyToken(String replyToken);

    /** Claims sessions due for diagnostics work, leasing them so a second worker skips them. */
    List<DiagnosticsSession> claimDueDiagnosticsSessions(int limit);

    /** Pending sessions whose potential client has been silent since before {@code threshold}. */
    List<DiagnosticsSession> silentDiagnosticsSessions(Instant threshold, int limit);

    List<DiagnosticsMessage> diagnosticsMessages(String tenantSlug, String sessionId);

    /** Drops transcripts of terminal sessions resolved before {@code cutoff}, keeping the verdict. */
    int purgeDiagnosticsTranscripts(Instant cutoff);

    List<LawyerProfile> lawyersForTenant(String tenantSlug);

    List<EventResponse> eventsForLawyer(String tenantSlug, String lawyerId);

    void updateEvents(String tenantSlug, List<EventResponse> events);

    void queueNotifications(String tenantSlug, List<NotificationOutboxItem> notifications);

    List<NotificationOutboxItem> claimPendingNotifications(int limit);

    void markNotificationSent(String notificationId, String providerMessageId);

    void markNotificationFailed(String notificationId, String errorMessage);
}
