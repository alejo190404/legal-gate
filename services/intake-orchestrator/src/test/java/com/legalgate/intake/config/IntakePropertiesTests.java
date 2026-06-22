package com.legalgate.intake.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntakePropertiesTests {

    @Test
    void canonicalIntakeEmailUsesNormalizedConfiguredDomain() {
        IntakeProperties properties = new IntakeProperties(
                "memory",
                false,
                " Intake.Legal-Gate.CO ",
                null,
                null,
                null,
                null
        );

        assertThat(properties.canonicalIntakeEmail("firma-demo"))
                .isEqualTo("firma-demo@intake.legal-gate.co");
    }

    @Test
    void rejectsLocalIntakeEmailDomain() {
        assertThatThrownBy(() -> new IntakeProperties(
                "memory",
                false,
                "intake.legal-gate.local",
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use .local");
    }
}
