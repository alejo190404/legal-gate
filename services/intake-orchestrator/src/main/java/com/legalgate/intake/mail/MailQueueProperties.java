package com.legalgate.intake.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.intake.mail")
public record MailQueueProperties(
        boolean enabled,
        String exchange,
        String incomingQueue,
        String deadLetterQueue,
        String routingKey,
        String deadLetterRoutingKey
) {
}
