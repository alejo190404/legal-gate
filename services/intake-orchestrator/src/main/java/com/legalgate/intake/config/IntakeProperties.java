package com.legalgate.intake.config;

import java.util.Locale;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.intake")
public record IntakeProperties(
        String persistence,
        boolean seedDemoData,
        String emailDomain,
        String consultationClassifierUrl,
        Duration consultationClassifierTimeout,
        String consultationClassifierPromptVersion,
        String consultationClassifierSystemPrompt,
        boolean outboundEmailEnabled,
        String cloudmailinSmtpUsername,
        String cloudmailinApiToken,
        String notificationsFromEmail,
        String notificationsFromName,
        boolean outboundTestMode
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
        if (consultationClassifierTimeout == null || consultationClassifierTimeout.isNegative()
                || consultationClassifierTimeout.isZero()) {
            consultationClassifierTimeout = Duration.ofSeconds(3);
        }
        if (consultationClassifierPromptVersion == null || consultationClassifierPromptVersion.isBlank()) {
            consultationClassifierPromptVersion = "consultation-classifier-v1";
        } else {
            consultationClassifierPromptVersion = consultationClassifierPromptVersion.trim();
        }
        if (consultationClassifierSystemPrompt == null || consultationClassifierSystemPrompt.isBlank()) {
            consultationClassifierSystemPrompt = """
                    You classify inbound legal consultation emails for a Colombian law firm.
                    Select exactly one provided routeIndex and exactly one tenant urgency level.
                    Keep summaries concise, extract a short legal concept, and explain the routing decision.
                    """.strip();
        } else {
            consultationClassifierSystemPrompt = consultationClassifierSystemPrompt.trim();
        }
        if (notificationsFromEmail == null || notificationsFromEmail.isBlank()) {
            notificationsFromEmail = "agenda@legal-gate.co";
        } else {
            notificationsFromEmail = notificationsFromEmail.trim().toLowerCase(Locale.ROOT);
        }
        if (notificationsFromName == null || notificationsFromName.isBlank()) {
            notificationsFromName = "LegalGate Agenda";
        } else {
            notificationsFromName = notificationsFromName.trim();
        }
    }

    public String canonicalIntakeEmail(String tenantId) {
        return tenantId + "@" + emailDomain;
    }
}
