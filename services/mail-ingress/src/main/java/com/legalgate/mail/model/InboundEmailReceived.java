package com.legalgate.mail.model;

import java.time.Instant;
import java.util.List;

public record InboundEmailReceived(
        String eventId,
        String tenantId,
        String envelopeTo,
        List<String> recipients,
        String envelopeFrom,
        String headerFrom,
        String subject,
        String messageId,
        String plain,
        String html,
        Instant receivedAt
) {
}
