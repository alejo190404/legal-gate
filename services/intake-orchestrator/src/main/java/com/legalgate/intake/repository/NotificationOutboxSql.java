package com.legalgate.intake.repository;

import com.legalgate.intake.model.NotificationOutboxItem;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Queues outbox rows inside the caller's transaction, so a state change and the mail it causes
 * commit together. Shared by every JDBC repository that sends mail.
 */
public final class NotificationOutboxSql {

    private NotificationOutboxSql() {
    }

    public static void insert(JdbcTemplate jdbc, UUID tenantId, String tenantSlug, List<NotificationOutboxItem> notifications) {
        if (notifications == null) {
            return;
        }
        for (NotificationOutboxItem notification : notifications) {
            jdbc.update("""
                    insert into notification_outbox (
                      tenant_id, tenant_slug, consultation_id, event_id, notification_type, recipient_role, recipient_email,
                      from_email, subject, body, html_body, ics_content, correspondence_id, reply_to, activity_id,
                      status, attempts, next_attempt_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, now(), now(), now())
                    on conflict (tenant_id, consultation_id, event_id, notification_type, recipient_role)
                    where status in ('PENDING', 'SENDING', 'FAILED') and event_id is not null
                    do nothing
                    """,
                    tenantId,
                    tenantSlug,
                    uuidOrNull(notification.consultationId()),
                    uuidOrNull(notification.eventId()),
                    notification.type(),
                    notification.recipientRole(),
                    notification.recipientEmail(),
                    notification.fromEmail(),
                    notification.subject(),
                    notification.body(),
                    notification.htmlBody(),
                    notification.icsContent(),
                    uuidOrNull(notification.correspondenceId()),
                    notification.replyTo(),
                    uuidOrNull(notification.activityId()));
        }
    }

    private static UUID uuidOrNull(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
