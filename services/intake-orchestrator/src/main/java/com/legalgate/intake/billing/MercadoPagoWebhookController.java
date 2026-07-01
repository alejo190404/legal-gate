package com.legalgate.intake.billing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MercadoPagoWebhookController {
    private final BillingService billing;
    private final MercadoPagoWebhookSignatureValidator validator;

    MercadoPagoWebhookController(BillingService billing, MercadoPagoWebhookSignatureValidator validator) {
        this.billing = billing;
        this.validator = validator;
    }

    @PostMapping("/api/webhooks/mercadopago")
    ResponseEntity<Map<String, Object>> receive(
            @RequestBody String rawBody,
            @RequestParam(name = "data.id", required = false) String dataId,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestHeader(name = "x-request-id", required = false) String requestId,
            HttpServletRequest request
    ) {
        boolean inserted = billing.acceptWebhook(rawBody, dataId, requestId, signature, validator);
        return ResponseEntity.ok(Map.of("status", inserted ? "accepted" : "duplicate"));
    }
}
