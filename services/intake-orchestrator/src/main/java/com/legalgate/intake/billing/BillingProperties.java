package com.legalgate.intake.billing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "legalgate.billing")
public record BillingProperties(
        boolean enabled,
        boolean enforcementEnabled,
        String mercadoPagoAccessToken,
        String mercadoPagoWebhookSecret,
        String mercadoPagoApiUrl,
        String returnUrl,
        Duration providerTimeout,
        Duration reconciliationInterval,
        Duration pendingCheckoutTtl,
        Duration gracePeriod
) {
    public BillingProperties {
        mercadoPagoAccessToken = textOrDefault(mercadoPagoAccessToken, null);
        mercadoPagoWebhookSecret = textOrDefault(mercadoPagoWebhookSecret, null);
        mercadoPagoApiUrl = textOrDefault(mercadoPagoApiUrl, "https://api.mercadopago.com");
        returnUrl = textOrDefault(returnUrl, null);
        providerTimeout = durationOrDefault(providerTimeout, Duration.ofSeconds(8));
        reconciliationInterval = durationOrDefault(reconciliationInterval, Duration.ofMinutes(5));
        pendingCheckoutTtl = durationOrDefault(pendingCheckoutTtl, Duration.ofHours(24));
        gracePeriod = durationOrDefault(gracePeriod, Duration.ofDays(7));
        if (enabled) {
            require(mercadoPagoAccessToken, "MERCADOPAGO_ACCESS_TOKEN");
            require(mercadoPagoWebhookSecret, "MERCADOPAGO_WEBHOOK_SECRET");
            require(returnUrl, "LEGALGATE_BILLING_RETURN_URL");
        }
        if (enforcementEnabled && !enabled) {
            throw new IllegalStateException(
                    "LEGALGATE_BILLING_ENFORCEMENT_ENABLED requires LEGALGATE_BILLING_ENABLED=true.");
        }
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Duration durationOrDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " must be configured when billing is enabled.");
        }
    }
}
