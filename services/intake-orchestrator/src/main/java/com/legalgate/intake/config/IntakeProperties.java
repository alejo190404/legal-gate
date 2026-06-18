package com.legalgate.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.intake")
public record IntakeProperties(
        String persistence,
        boolean seedDemoData,
        String emailDomain
) {
    public String canonicalIntakeEmail(String tenantId) {
        return tenantId + "@" + emailDomain;
    }
}
