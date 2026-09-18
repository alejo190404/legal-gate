package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
@ConditionalOnProperty(name = "legalgate.intake.email-provider", havingValue = "cloudmailin", matchIfMissing = true)
class CloudMailinOutboundEmailClient implements OutboundEmailClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    private final IntakeProperties intakeProperties;
    private final RestClient restClient;
    private final OutboundMailEnvelope envelope;

    CloudMailinOutboundEmailClient(IntakeProperties intakeProperties, RestClient.Builder restClientBuilder,
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
            throw new IllegalStateException("CloudMailin outbound email delivery is disabled.");
        }
        if (isBlank(intakeProperties.cloudmailinSmtpUsername()) || isBlank(intakeProperties.cloudmailinApiToken())) {
            throw new IllegalStateException("CloudMailin outbound credentials are not configured.");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("https://api.cloudmailin.com/api/v0.1/{username}/messages",
                        UriUtils.encodePathSegment(intakeProperties.cloudmailinSmtpUsername().trim(), StandardCharsets.UTF_8))
                .headers(headers -> headers.setBearerAuth(intakeProperties.cloudmailinApiToken().trim()))
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
        payload.put("to", notification.recipientEmail());
        payload.put("test_mode", intakeProperties.outboundTestMode());
        payload.put("subject", notification.subject());
        payload.put("plain", notification.body());
        if (notification.htmlBody() != null && !notification.htmlBody().isBlank()) {
            payload.put("html", notification.htmlBody());
        }
        payload.put("tags", List.of("legalgate", "consultation", "tenant:" + tagValue(notification.tenantId()), notification.type(), notification.recipientRole()));
        if (!isBlank(notification.icsContent())) {
            payload.put("attachments", List.of(Map.of(
                    "file_name", "legalgate-consultation.ics",
                    "content", Base64.getEncoder().encodeToString(notification.icsContent().getBytes(StandardCharsets.UTF_8)),
                    "content_type", "text/calendar; method=REQUEST; charset=UTF-8"
            )));
        }
        Map<String, String> headers = mailHeaders(notification, threadAnchor);
        if (!headers.isEmpty()) {
            payload.put("headers", headers);
        }
        return payload;
    }

    private Map<String, String> mailHeaders(NotificationOutboxItem notification, String threadAnchor) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!isBlank(notification.id())) {
            // Every message carries its own identity, threaded or not, on the sending address's
            // own domain so it does not read as forged. CloudMailin honours this header; Resend
            // silently replaces it, which is why it lives here and not in the envelope.
            headers.put("Message-ID", envelope.angleBracketed(notification.id() + "@" + envelope.senderDomain(notification)));
        }
        headers.putAll(envelope.clientHeaders(notification, threadAnchor));
        return headers;
    }

    @Override
    public boolean isEnabled() {
        return intakeProperties.outboundEmailEnabled();
    }

    private boolean isBlank(String value) {
        return OutboundMailEnvelope.isBlank(value);
    }

    private String tagValue(String value) {
        return isBlank(value) ? "unknown" : value.trim();
    }
}
