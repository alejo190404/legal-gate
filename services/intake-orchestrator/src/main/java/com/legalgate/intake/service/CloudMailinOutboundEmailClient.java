package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
class CloudMailinOutboundEmailClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    private final IntakeProperties intakeProperties;
    private final RestClient restClient;

    CloudMailinOutboundEmailClient(IntakeProperties intakeProperties, RestClient.Builder restClientBuilder) {
        this.intakeProperties = intakeProperties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    String send(NotificationOutboxItem notification) {
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
                .body(Map.of(
                        "from", intakeProperties.notificationsFromName() + " <" + intakeProperties.notificationsFromEmail() + ">",
                        "to", notification.recipientEmail(),
                        "test_mode", intakeProperties.outboundTestMode(),
                        "subject", notification.subject(),
                        "plain", notification.body(),
                        "tags", List.of("legalgate", "consultation", "tenant:" + tagValue(notification.tenantId()), notification.type(), notification.recipientRole()),
                        "attachments", List.of(Map.of(
                                "file_name", "legalgate-consultation.ics",
                                "content", Base64.getEncoder().encodeToString(notification.icsContent().getBytes(StandardCharsets.UTF_8)),
                                "content_type", "text/calendar; method=REQUEST; charset=UTF-8"
                        ))
                ))
                .retrieve()
                .body(Map.class);

        Object id = response == null ? null : response.get("id");
        return id == null ? null : id.toString();
    }

    boolean isEnabled() {
        return intakeProperties.outboundEmailEnabled();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String tagValue(String value) {
        return isBlank(value) ? "unknown" : value.trim();
    }
}
