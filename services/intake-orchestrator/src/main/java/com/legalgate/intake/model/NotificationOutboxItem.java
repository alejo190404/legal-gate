package com.legalgate.intake.model;

import java.time.Instant;

public record NotificationOutboxItem(
        String id,
        String tenantId,
        String consultationId,
        String eventId,
        String type,
        String recipientRole,
        String recipientEmail,
        String subject,
        String body,
        String htmlBody,
        String icsContent,
        String status,
        Integer attempts,
        String providerMessageId,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant nextAttemptAt
) {
    public NotificationOutboxItem(
            String consultationId,
            String eventId,
            String type,
            String recipientRole,
            String recipientEmail,
            String subject,
            String body,
            String htmlBody,
            String icsContent
    ) {
        this(null, null, consultationId, eventId, type, recipientRole, recipientEmail, subject, body, htmlBody, icsContent,
                "PENDING", 0, null, null, null, null, null);
    }
}
