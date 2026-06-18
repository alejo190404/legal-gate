package com.legalgate.mail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.mail-ingress")
public record MailIngressProperties(
        BasicAuth basicAuth,
        MailerSend mailersend,
        String exchange,
        String routingKey
) {
    public record BasicAuth(String username, String password) {
    }

    public record MailerSend(String webhookSecret) {
    }
}
