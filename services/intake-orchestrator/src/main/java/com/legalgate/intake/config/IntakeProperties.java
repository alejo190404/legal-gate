package com.legalgate.intake.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.intake")
public record IntakeProperties(
        String persistence,
        boolean seedDemoData,
        String emailDomain
) {
    public IntakeProperties {
        if (emailDomain == null || emailDomain.isBlank()) {
            throw new IllegalStateException("legalgate.intake.email-domain must be configured.");
        }
        emailDomain = emailDomain.trim().toLowerCase(Locale.ROOT);
        if (emailDomain.endsWith(".local")) {
            throw new IllegalStateException(
                    "legalgate.intake.email-domain must not use .local; set LEGALGATE_INTAKE_EMAIL_DOMAIN=intake.legal-gate.co."
            );
        }
    }

    public String canonicalIntakeEmail(String tenantId) {
        return tenantId + "@" + emailDomain;
    }
}
