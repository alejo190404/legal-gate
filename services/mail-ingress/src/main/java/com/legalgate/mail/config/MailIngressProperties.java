package com.legalgate.mail.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.mail-ingress")
public record MailIngressProperties(
        BasicAuth basicAuth,
        MailerSend mailersend,
        IntakeOrchestrator intakeOrchestrator,
        // ponytail: scaffolding for the CloudMailin -> Resend migration window, so a false
        // positive in production is an env flip rather than a redeploy. Delete it with the
        // CloudMailin cleanup, once the stripper has run on real mail for a while.
        Boolean stripQuotedReply
) {
    public MailIngressProperties {
        stripQuotedReply = stripQuotedReply == null || stripQuotedReply;
    }

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
