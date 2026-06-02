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
        ClassificationResult classification,
        NotificationStatus notifications,
        Instant createdAt
) {
}
