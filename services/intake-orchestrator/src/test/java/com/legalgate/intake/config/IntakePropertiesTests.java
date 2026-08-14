package com.legalgate.intake.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntakePropertiesTests {

    @Test
    void canonicalIntakeEmailUsesNormalizedConfiguredDomain() {
        IntakeProperties properties = properties(" Intake.Legal-Gate.CO ", "test-service-token");

        assertThat(properties.canonicalIntakeEmail("firma-demo"))
                .isEqualTo("firma-demo@intake.legal-gate.co");
    }

    @Test
    void rejectsLocalIntakeEmailDomain() {
        assertThatThrownBy(() -> properties("intake.legal-gate.local", "test-service-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use .local");
    }

    @Test
    void rejectsBlankInternalServiceToken() {
        assertThatThrownBy(() -> properties("intake.legal-gate.co", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGALGATE_INTERNAL_SERVICE_TOKEN");
    }

    @Test
    void defaultsTheDiagnosticsPromptWhenNoneIsConfigured() {
        IntakeProperties properties = properties("intake.legal-gate.co", "test-service-token");

        assertThat(properties.consultationDiagnosticsPromptVersion()).isEqualTo("consultation-diagnostics-v2");
        assertThat(properties.consultationDiagnosticsSystemPrompt()).contains("accept");
        // v2 asks for the Acknowledgment, and still ranks the firm's own prompt above how it reads.
        assertThat(properties.consultationDiagnosticsSystemPrompt())
                .contains("acknowledgment")
                .contains("outranks");
    }

    private IntakeProperties properties(String emailDomain, String internalServiceToken) {
        return new IntakeProperties(
                "memory", false, emailDomain, null, null, null, null, null, null,
                false, null, null, null, null, false, internalServiceToken, "sk_test", "https://api.workos.com");
    }
}
