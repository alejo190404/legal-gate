package com.legalgate.mail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** The stored message behind an {@code email.received} event. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResendReceivedEmail(
        @JsonProperty("message_id") String messageId,
        String from,
        String subject,
        List<String> to,
        List<String> cc,
        @JsonProperty("received_for") List<String> receivedFor,
        String text,
        String html,
        Map<String, Object> headers
) {
}
