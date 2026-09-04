package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "legalgate.intake.email-provider", havingValue = "resend")
class ResendOutboundEmailClient implements OutboundEmailClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResendOutboundEmailClient.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    /** Resend has no test-mode flag; this address is its own sink and never reaches a real inbox. */
    private static final String TEST_MODE_RECIPIENT = "delivered@resend.dev";

    private final IntakeProperties intakeProperties;
    private final RestClient restClient;
    private final OutboundMailEnvelope envelope;

    ResendOutboundEmailClient(IntakeProperties intakeProperties, RestClient.Builder restClientBuilder,
            OutboundMailEnvelope envelope) {
        this.intakeProperties = intakeProperties;
        this.envelope = envelope;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    @Override
    public String send(NotificationOutboxItem notification, String threadAnchor) {
        if (!isEnabled()) {
            throw new IllegalStateException("Resend outbound email delivery is disabled.");
        }
        if (isBlank(intakeProperties.resendApiKey())) {
            throw new IllegalStateException("RESEND_API_KEY is not configured.");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("https://api.resend.com/emails")
                .headers(headers -> headers.setBearerAuth(intakeProperties.resendApiKey().trim()))
                // The outbox re-claims a SENDING row after five minutes, so a crash between a
                // successful send and markNotificationSent would otherwise mail the client twice.
                .header("Idempotency-Key", notification.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload(notification, threadAnchor))
                .retrieve()
                .body(Map.class);

        Object id = response == null ? null : response.get("id");
        return id == null ? null : id.toString();
    }

    Map<String, Object> payload(NotificationOutboxItem notification, String threadAnchor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", envelope.fromHeader(notification));
        payload.put("to", recipient(notification));
        payload.put("subject", notification.subject());
        payload.put("text", notification.body());
        if (!isBlank(notification.htmlBody())) {
            payload.put("html", notification.htmlBody());
        }
        payload.put("tags", tags(notification));
        if (!isBlank(notification.icsContent())) {
            payload.put("attachments", List.of(Map.of(
                    "filename", "legalgate-consultation.ics",
                    "content", Base64.getEncoder().encodeToString(notification.icsContent().getBytes(StandardCharsets.UTF_8)),
                    // Without this Outlook renders the invite as a plain file; Gmail tolerates its
                    // absence. Proven against the live account — never drop it.
                    "content_type", "text/calendar; method=REQUEST; charset=UTF-8"
            )));
        }
        // No Message-ID: Resend takes the header at HTTP 200 and silently substitutes an SES id,
        // so sending one would leave the code asserting an identity the wire discarded. ADR 0004.
        Map<String, String> headers = envelope.threadHeaders(notification, threadAnchor);
        if (!headers.isEmpty()) {
            payload.put("headers", headers);
        }
        return payload;
    }

    private String recipient(NotificationOutboxItem notification) {
        if (!intakeProperties.outboundTestMode()) {
            return notification.recipientEmail();
        }
        LOGGER.info("Outbound test mode: notification id={} redirected to {} instead of {}.",
                notification.id(), TEST_MODE_RECIPIENT, notification.recipientEmail());
        return TEST_MODE_RECIPIENT;
    }

    /** Tags are diagnostics, not delivery: a value that will not survive sanitising is dropped. */
    private List<Map<String, String>> tags(NotificationOutboxItem notification) {
        Map<String, String> named = new LinkedHashMap<>();
        named.put("app", "legalgate");
        named.put("tenant", notification.tenantId());
        named.put("type", notification.type());
        named.put("role", notification.recipientRole());
        List<Map<String, String>> tags = new ArrayList<>();
        named.forEach((name, value) -> {
            if (!isBlank(value)) {
                // Resend allows ASCII letters, digits, underscores and hyphens; tenant slugs are
                // tenant-supplied, so everything else is folded rather than sent raw.
                tags.add(Map.of("name", name, "value", value.trim().replaceAll("[^A-Za-z0-9_-]+", "_")));
            }
        });
        return tags;
    }

    @Override
    public boolean isEnabled() {
        return intakeProperties.outboundEmailEnabled();
    }

    private boolean isBlank(String value) {
        return OutboundMailEnvelope.isBlank(value);
    }
}
