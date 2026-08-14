package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
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
    private final FirmNameResolver firmNameResolver;

    CloudMailinOutboundEmailClient(IntakeProperties intakeProperties, RestClient.Builder restClientBuilder,
            FirmNameResolver firmNameResolver) {
        this.intakeProperties = intakeProperties;
        this.firmNameResolver = firmNameResolver;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    /**
     * @param threadAnchor the Consultation's original inbound Message-ID, or null to send unthreaded.
     */
    String send(NotificationOutboxItem notification, String threadAnchor) {
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
        payload.put("from", fromHeader(notification));
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

    /** The Consultation Thread is anchored flat at the potential client's first email; see ADR 0004. */
    private Map<String, String> mailHeaders(NotificationOutboxItem notification, String threadAnchor) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!isBlank(notification.id())) {
            // Every message carries its own identity, threaded or not, on the sending address's
            // own domain so it does not read as forged.
            headers.put("Message-ID", angleBracketed(notification.id() + "@" + senderDomain(notification)));
        }
        // The thread is the conversation with the potential client; staff mail is not part of it.
        if (!"CLIENT".equals(notification.recipientRole()) || isBlank(threadAnchor)) {
            return headers;
        }
        String anchor = angleBracketed(threadAnchor);
        if ("<>".equals(anchor)) {
            return headers;
        }
        headers.put("In-Reply-To", anchor);
        headers.put("References", anchor);
        return headers;
    }

    private String senderDomain(NotificationOutboxItem notification) {
        String address = fromEmail(notification);
        int at = address.lastIndexOf('@');
        return at < 0 ? intakeProperties.emailDomain() : address.substring(at + 1);
    }

    /**
     * Inbound Message-IDs are stored as providers hand them over, with or without brackets. The value
     * comes from a potential client's email, so anything that could forge a header is stripped first.
     */
    private String angleBracketed(String messageId) {
        String bare = messageId.replaceAll("[\\p{Cntrl}\\s<>]+", "").trim();
        return "<" + bare + ">";
    }

    String fromHeader(NotificationOutboxItem notification) {
        // Diagnostics messages carry a token-bearing From address so the client's reply lands
        // back on the right consultation; everything else sends from the shared agenda address.
        String fromEmail = fromEmail(notification);
        // A potential client wrote to a firm and hears back from that firm. Lawyers and firm staff
        // are the LegalGate customer, so their mail keeps LegalGate branding unchanged.
        if (!"CLIENT".equals(notification.recipientRole())) {
            return intakeProperties.notificationsFromName() + " <" + fromEmail + ">";
        }
        return quoted(firmNameResolver.firmNameFor(notification.tenantId())) + " <" + fromEmail + ">";
    }

    private String fromEmail(NotificationOutboxItem notification) {
        return isBlank(notification.fromEmail())
                ? intakeProperties.notificationsFromEmail()
                : notification.fromEmail().trim();
    }

    /** Firm names are tenant-supplied, so they are quoted and stripped of anything that could forge a header. */
    private String quoted(String displayName) {
        String sanitized = displayName.replaceAll("\\p{Cntrl}+", " ").trim()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "\"" + sanitized + "\"";
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
