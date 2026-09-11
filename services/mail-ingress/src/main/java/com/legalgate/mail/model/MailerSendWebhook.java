package com.legalgate.mail.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record MailerSendWebhook(
        String type,
        String event,
        Data data
) {
    public String eventType() {
        return type == null || type.isBlank() ? event : type;
    }

    public record Data(
            From from,
            List<Recipient> recipients,
            List<Recipient> to,
            Recipient recipient,
            String subject,
            @JsonProperty("message_id") String messageId,
            Text text,
            String html,
            String plain,
            Map<String, Object> headers
    ) {
    }

    public record From(String email, String name) {
    }

    public record Recipient(String email, String name) {
    }

    public record Text(String plain, String html) {
    }
}
