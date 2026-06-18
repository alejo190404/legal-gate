package com.legalgate.mail.service;

import com.legalgate.mail.config.MailIngressProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class MailerSendSignatureVerifier {

    private final MailIngressProperties properties;

    MailerSendSignatureVerifier(MailIngressProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(String rawBody, String signatureHeader, String secretHeader) {
        MailIngressProperties.MailerSend mailerSend = properties.mailersend();
        if (mailerSend == null || mailerSend.webhookSecret() == null || mailerSend.webhookSecret().isBlank()) {
            return true;
        }

        String expectedSecret = mailerSend.webhookSecret();
        if (secretHeader != null && !secretHeader.isBlank()) {
            return constantEquals(expectedSecret, secretHeader.trim());
        }

        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        String expectedSignature = hmacSha256Hex(rawBody, expectedSecret);
        String normalizedHeader = signatureHeader.trim();
        if (normalizedHeader.startsWith("sha256=")) {
            normalizedHeader = normalizedHeader.substring("sha256=".length());
        }
        return constantEquals(expectedSignature, normalizedHeader);
    }

    private String hmacSha256Hex(String rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to verify MailerSend webhook signature", ex);
        }
    }

    private boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
