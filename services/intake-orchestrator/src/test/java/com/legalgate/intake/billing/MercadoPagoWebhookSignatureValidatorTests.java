package com.legalgate.intake.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MercadoPagoWebhookSignatureValidatorTests {
    @Test
    void delegatesTheOfficialMercadoPagoSignatureAlgorithm() throws Exception {
        String secret = "webhook-secret";
        String dataId = "payment-123";
        String requestId = "request-123";
        String timestamp = "1704908010";
        String manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + timestamp + ";";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hash = HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));

        MercadoPagoWebhookSignatureValidator validator = new MercadoPagoWebhookSignatureValidator(
                new BillingProperties(
                        true, false, "token", secret, "https://api.example.test",
                        "https://app.example.test", Duration.ofSeconds(3), Duration.ofMinutes(5),
                        Duration.ofHours(24), Duration.ofDays(7)));

        assertThat(validator.isValid(dataId, requestId, "ts=" + timestamp + ",v1=" + hash)).isTrue();
        assertThat(validator.isValid(dataId, requestId, "ts=" + timestamp + ",v1=deadbeef")).isFalse();
    }
}
