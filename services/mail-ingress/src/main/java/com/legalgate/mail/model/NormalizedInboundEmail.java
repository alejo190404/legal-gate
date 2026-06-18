package com.legalgate.mail.model;

import java.util.List;

public record NormalizedInboundEmail(
        List<String> recipients,
        String envelopeFrom,
        String headerFrom,
        String subject,
        String messageId,
        String plain,
        String html
) {
}
