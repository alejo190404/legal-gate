package com.legalgate.mail.service;

import com.legalgate.mail.config.MailIngressProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies the Svix signature Resend puts on a webhook.
 *
 * <p>Shaped like {@link MailerSendSignatureVerifier} but not derived from it: Svix signs
 * {@code id.timestamp.body} rather than the body alone, keys the HMAC with the base64-decoded
 * secret rather than its characters, encodes the digest as base64 rather than hex, and carries a
 * replay window.
 */
@Component
public class ResendSignatureVerifier {

    /** Svix's own recommendation, and what its libraries use. */
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final String SECRET_PREFIX = "whsec_";

    private final MailIngressProperties properties;

    ResendSignatureVerifier(MailIngressProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(String rawBody, String svixId, String svixTimestamp, String svixSignature) {
        return isValid(rawBody, svixId, svixTimestamp, svixSignature, Instant.now());
    }

    boolean isValid(String rawBody, String svixId, String svixTimestamp, String svixSignature, Instant now) {
        MailIngressProperties.Resend resend = properties.resend();
        if (resend == null || resend.webhookSecret() == null || resend.webhookSecret().isBlank()) {
            return true;
        }
        if (svixId == null || svixTimestamp == null || svixSignature == null || svixSignature.isBlank()) {
            return false;
        }
        if (!withinTolerance(svixTimestamp, now)) {
            return false;
        }

        byte[] expected = sign(resend.webhookSecret().trim(), svixId + "." + svixTimestamp + "." + rawBody);
        // The header holds space-delimited "<version>,<signature>" entries; Svix rotates secrets
        // by sending more than one, so any matching v1 entry is enough.
        for (String entry : svixSignature.trim().split("\\s+")) {
            int comma = entry.indexOf(',');
            if (comma < 0 || !"v1".equals(entry.substring(0, comma))) {
                continue;
            }
            if (MessageDigest.isEqual(expected, decode(entry.substring(comma + 1)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A signature stays valid forever without this — an attacker who captured one replays the
     * message whenever they like.
     */
    private boolean withinTolerance(String svixTimestamp, Instant now) {
        try {
            Instant signedAt = Instant.ofEpochSecond(Long.parseLong(svixTimestamp.trim()));
            return Duration.between(signedAt, now).abs().compareTo(TOLERANCE) <= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private byte[] sign(String secret, String signedContent) {
        try {
            String base64Key = secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
            return mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to verify Resend webhook signature", ex);
        }
    }

    /** A malformed entry is a failed comparison, not an exception out of the webhook path. */
    private byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            return new byte[0];
        }
    }
}
