package com.legalgate.intake.billing;

import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.webhook.WebhookSignatureValidator;
import org.springframework.stereotype.Component;

@Component
public class MercadoPagoWebhookSignatureValidator {
    private final BillingProperties properties;

    public MercadoPagoWebhookSignatureValidator(BillingProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(String dataId, String requestId, String signature) {
        if (!properties.enabled() || blank(signature) || blank(dataId)) return false;
        String effectiveRequestId = blank(requestId) ? "" : requestId;
        try {
            WebhookSignatureValidator.validate(
                    signature, effectiveRequestId, dataId, properties.mercadoPagoWebhookSecret());
            return true;
        } catch (MPInvalidWebhookSignatureException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
