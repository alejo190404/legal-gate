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
        String consultationDiagnosticsPromptVersion,
        String consultationDiagnosticsSystemPrompt,
        boolean outboundEmailEnabled,
        String cloudmailinSmtpUsername,
        String cloudmailinApiToken,
        String notificationsFromEmail,
        String notificationsFromName,
        boolean outboundTestMode,
        String internalServiceToken,
        String workosApiKey,
        String workosApiBaseUrl
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
        if (consultationDiagnosticsPromptVersion == null || consultationDiagnosticsPromptVersion.isBlank()) {
            consultationDiagnosticsPromptVersion = "consultation-diagnostics-v2";
        } else {
            consultationDiagnosticsPromptVersion = consultationDiagnosticsPromptVersion.trim();
        }
        if (consultationDiagnosticsSystemPrompt == null || consultationDiagnosticsSystemPrompt.isBlank()) {
            consultationDiagnosticsSystemPrompt = """
                    You qualify inbound legal consultation emails for a Colombian law firm, using the
                    firm's own description of the matters it takes and the information it needs.
                    Answer accept when the matter is in scope and complete enough to assess, ask when
                    the firm still needs specific information, and reject when the firm does not take
                    matters of this kind. Ask for everything missing in a single short question,
                    written in the language of the potential client. Never give legal advice.
                    The firm's own description outranks everything else in this prompt, tone and
                    wording included: ask for everything the firm asked for, in the firm's terms,
                    however the question ends up reading.
                    When you ask, also return an acknowledgment: at most 15 words naming what the
                    potential client wrote about, in their own words, completing the sentence
                    "Recibimos su mensaje sobre ...". Repeat their terms and introduce no legal
                    concept, statute or claim they did not name themselves — it is a receipt of
                    their message, not an assessment of their matter. Return no acknowledgment at
                    all rather than one you had to guess at.
                    """.strip();
        } else {
            consultationDiagnosticsSystemPrompt = consultationDiagnosticsSystemPrompt.trim();
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
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new IllegalStateException("LEGALGATE_INTERNAL_SERVICE_TOKEN must be configured.");
        }
        if (workosApiKey == null || workosApiKey.isBlank()) {
            throw new IllegalStateException("WORKOS_API_KEY must be configured.");
        }
        if (workosApiBaseUrl == null || workosApiBaseUrl.isBlank()) {
            workosApiBaseUrl = "https://api.workos.com";
        }
    }

    public String canonicalIntakeEmail(String tenantId) {
        return tenantId + "@" + emailDomain;
    }
}
