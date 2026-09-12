package com.legalgate.intake.model;

import java.time.Instant;

public record ConsultationResponse(
        String id,
        String tenantId,
        String clientName,
        String clientEmail,
        String summary,
        String preferredWindow,
        String status,
        String urgency,
        String consultationType,
        String assignedLawyerEmail,
        ClassificationResult classification,
        NotificationStatus notifications,
        String sourceEventId,
        String sourceMessageId,
        Instant createdAt,
        String eventId,
        EventResponse event,
        /** The diagnostics summary of the matter, when one has been extracted. Read-only: it is
         * joined in from the diagnostics session, never written back with the consultation. */
        String diagnosticsSummary
) {
    public ConsultationResponse(
            String id,
            String tenantId,
            String clientName,
            String clientEmail,
            String summary,
            String preferredWindow,
            String status,
            String urgency,
            String consultationType,
            String assignedLawyerEmail,
            ClassificationResult classification,
            NotificationStatus notifications,
            String sourceEventId,
            String sourceMessageId,
            Instant createdAt
    ) {
        this(id, tenantId, clientName, clientEmail, summary, preferredWindow, status, urgency, consultationType,
                assignedLawyerEmail, classification, notifications, sourceEventId, sourceMessageId, createdAt, null, null, null);
    }

    public ConsultationResponse(
            String id,
            String tenantId,
            String clientName,
            String clientEmail,
            String summary,
            String preferredWindow,
            String status,
            String urgency,
            String consultationType,
            String assignedLawyerEmail,
            ClassificationResult classification,
            NotificationStatus notifications,
            String sourceEventId,
            String sourceMessageId,
            Instant createdAt,
            String eventId,
            EventResponse event
    ) {
        this(id, tenantId, clientName, clientEmail, summary, preferredWindow, status, urgency, consultationType,
                assignedLawyerEmail, classification, notifications, sourceEventId, sourceMessageId, createdAt,
                eventId, event, null);
    }

    public ConsultationResponse withStatus(String newStatus) {
        return new ConsultationResponse(id, tenantId, clientName, clientEmail, summary, preferredWindow, newStatus,
                urgency, consultationType, assignedLawyerEmail, classification, notifications, sourceEventId,
                sourceMessageId, createdAt, eventId, event, diagnosticsSummary);
    }
}
