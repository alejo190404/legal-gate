package com.legalgate.intake.billing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BillingPropertiesTests {
    @Test
    void disabledBillingNeedsNoProviderSecrets() {
        assertThatCode(() -> new BillingProperties(
                false, false, null, null, null, null,
                null, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void enabledBillingFailsFastWithoutSecretsAndReturnUrl() {
        assertThatThrownBy(() -> new BillingProperties(
                true, false, null, null, null, null,
                Duration.ofSeconds(1), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MERCADOPAGO_ACCESS_TOKEN");
    }

    @Test
    void enforcementCannotBeEnabledAlone() {
        assertThatThrownBy(() -> new BillingProperties(
                false, true, null, null, null, null,
                null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGALGATE_BILLING_ENABLED");
    }
}
