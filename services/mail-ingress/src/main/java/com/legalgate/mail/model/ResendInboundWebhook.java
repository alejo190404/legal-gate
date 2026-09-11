package com.legalgate.mail.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The Svix envelope Resend posts. Only the event type and the id of the stored message are used —
 * the message itself is fetched separately, because the webhook body does not carry it.
 */
public record ResendInboundWebhook(
        String type,
        Data data
) {
    public record Data(
            @JsonProperty("email_id") String emailId,
            String id
    ) {
        /** Resend has used both spellings; take whichever is present. */
        public String messageStoreId() {
            return emailId != null && !emailId.isBlank() ? emailId : id;
        }
    }
}
