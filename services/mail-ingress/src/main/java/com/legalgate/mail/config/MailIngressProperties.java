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

    public record IntakeOrchestrator(URI baseUrl, String serviceToken) {
        public IntakeOrchestrator {
            if (serviceToken == null || serviceToken.isBlank()) {
                throw new IllegalStateException("LEGALGATE_INTERNAL_SERVICE_TOKEN must be configured.");
            }
        }
    }
}
