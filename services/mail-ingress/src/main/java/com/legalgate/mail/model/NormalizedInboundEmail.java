package com.legalgate.mail.model;

import java.util.List;

public record NormalizedInboundEmail(
        List<String> recipients,
        String envelopeFrom,
        String headerFrom,
        String subject,
        String messageId,
        String plain,
        String html,
        boolean autoResponder
) {
    public NormalizedInboundEmail(
            List<String> recipients,
            String envelopeFrom,
            String headerFrom,
            String subject,
            String messageId,
            String plain,
            String html
    ) {
        this(recipients, envelopeFrom, headerFrom, subject, messageId, plain, html, false);
    }
}
