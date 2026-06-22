package com.legalgate.mail.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.mail-ingress")
public record MailIngressProperties(
        BasicAuth basicAuth,
        MailerSend mailersend,
        IntakeOrchestrator intakeOrchestrator
) {
    public record BasicAuth(String username, String password) {
    }

    public record MailerSend(String webhookSecret) {
    }

    public record IntakeOrchestrator(URI baseUrl) {
    }
}
