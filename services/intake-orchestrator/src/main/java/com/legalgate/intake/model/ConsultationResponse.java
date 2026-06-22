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
        Instant createdAt
) {
}
