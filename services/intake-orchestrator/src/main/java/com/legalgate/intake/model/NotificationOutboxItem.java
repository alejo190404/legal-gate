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
        String fromEmail,
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
        Instant nextAttemptAt,
        /** Client Correspondence this message answers or forwards; the thread anchor for Client mail. */
        String correspondenceId,
        /** An explicit Reply-To, e.g. a forward a lawyer answers straight to the Client. */
        String replyTo,
        /** The Activity a reminder is about, so deleting its Client deletes the reminder too. */
        String activityId
) {
    public NotificationOutboxItem(
            String id, String tenantId, String consultationId, String eventId, String type, String recipientRole,
            String recipientEmail, String fromEmail, String subject, String body, String htmlBody, String icsContent,
            String status, Integer attempts, String providerMessageId, String lastError, Instant createdAt,
            Instant updatedAt, Instant nextAttemptAt
    ) {
        this(id, tenantId, consultationId, eventId, type, recipientRole, recipientEmail, fromEmail, subject, body,
                htmlBody, icsContent, status, attempts, providerMessageId, lastError, createdAt, updatedAt,
                nextAttemptAt, null, null, null);
    }

    /** Mail about a Client's email; no Consultation, no Event, no calendar invite. */
    public static NotificationOutboxItem forCorrespondence(String correspondenceId, String type, String recipientRole,
            String recipientEmail, String fromEmail, String replyTo, String subject, String body, String htmlBody) {
        return new NotificationOutboxItem(null, null, null, null, type, recipientRole, recipientEmail, fromEmail,
                subject, body, htmlBody, null, "PENDING", 0, null, null, null, null, null, correspondenceId, replyTo, null);
    }

    /** A reminder to a lawyer about an Activity. */
    public static NotificationOutboxItem forActivity(String activityId, String type, String recipientEmail,
            String subject, String body, String htmlBody) {
        return new NotificationOutboxItem(null, null, null, null, type, "LAWYER", recipientEmail, null,
                subject, body, htmlBody, null, "PENDING", 0, null, null, null, null, null, null, null, activityId);
    }

    /** The same message in a new delivery state. */
    public NotificationOutboxItem withDelivery(String id, String tenantId, String status, Integer attempts,
            String providerMessageId, String lastError, Instant createdAt, Instant updatedAt, Instant nextAttemptAt) {
        return new NotificationOutboxItem(id, tenantId, consultationId, eventId, type, recipientRole, recipientEmail,
                fromEmail, subject, body, htmlBody, icsContent, status, attempts, providerMessageId, lastError,
                createdAt, updatedAt, nextAttemptAt, correspondenceId, replyTo, activityId);
    }

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
        this(consultationId, eventId, type, recipientRole, recipientEmail, null, subject, body, htmlBody, icsContent);
    }

    public NotificationOutboxItem(
            String consultationId,
            String eventId,
            String type,
            String recipientRole,
            String recipientEmail,
            String fromEmail,
            String subject,
            String body,
            String htmlBody,
            String icsContent
    ) {
        this(null, null, consultationId, eventId, type, recipientRole, recipientEmail, fromEmail, subject, body,
                htmlBody, icsContent, "PENDING", 0, null, null, null, null, null);
    }
}
