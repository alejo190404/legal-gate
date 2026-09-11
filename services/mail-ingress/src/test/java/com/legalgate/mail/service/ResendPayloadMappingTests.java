package com.legalgate.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.mail.model.ResendInboundWebhook;
import com.legalgate.mail.model.ResendReceivedEmail;
import org.junit.jupiter.api.Test;

/**
 * Both fixtures are captured verbatim from the live account — the webhook Resend posted and the
 * response its retrieve endpoint returned — with addresses anonymised. Mapping was the one part
 * of the adapter written against the plan's description rather than a real message, and it is
 * where the first smoke test broke.
 */
class ResendPayloadMappingTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String WEBHOOK = """
            {
              "created_at": "2026-09-11T16:58:10.000Z",
              "data": {
                "attachments": [],
                "bcc": [],
                "cc": [],
                "created_at": "2026-09-11T16:58:24.214Z",
                "email_id": "02a8929e-6fba-4025-9201-4df3e0ff98fd",
                "from": "juan@example.com",
                "message_id": "<CADdZXf0@mail.gmail.com>",
                "received_for": ["cualquiera@kronieostu.resend.app"],
                "subject": "Test",
                "to": ["cualquiera@kronieostu.resend.app"]
              },
              "type": "email.received"
            }
            """;

    private static final String RETRIEVED = """
            {
              "object": "email",
              "id": "02a8929e-6fba-4025-9201-4df3e0ff98fd",
              "to": ["cualquiera@kronieostu.resend.app"],
              "from": "juan@example.com",
              "created_at": "2026-09-11T16:58:24.214Z",
              "subject": "Test",
              "message_id": "<CADdZXf0@mail.gmail.com>",
              "bcc": [],
              "cc": [],
              "reply_to": [],
              "html": "<div dir=\\"ltr\\">Necesito asesoría.</div>",
              "html_format": "data_uri",
              "text": "Necesito asesoría.",
              "headers": {"return-path": "juan@example.com", "precedence": "bulk"},
              "received_for": ["cualquiera@kronieostu.resend.app"],
              "raw": {"download_url": "https://cdn.resend.app/receiving/raw/abc"},
              "attachments": []
            }
            """;

    @Test
    void theWebhookCarriesTheIdOfTheStoredMessage() throws Exception {
        ResendInboundWebhook webhook = objectMapper.readValue(WEBHOOK, ResendInboundWebhook.class);

        assertThat(webhook.type()).isEqualTo("email.received");
        assertThat(webhook.data().messageStoreId()).isEqualTo("02a8929e-6fba-4025-9201-4df3e0ff98fd");
    }

    /**
     * received_for is a list. Typed as a String it threw inside the RestClient's message
     * converter, which surfaces as a RestClientException and reads exactly like Resend being
     * unreachable — a 502 with nothing to distinguish it from an outage.
     */
    @Test
    void theRetrievedMessageMapsIncludingTheAddressItWasDeliveredTo() throws Exception {
        ResendReceivedEmail email = objectMapper.readValue(RETRIEVED, ResendReceivedEmail.class);

        assertThat(email.receivedFor()).containsExactly("cualquiera@kronieostu.resend.app");
        assertThat(email.text()).isEqualTo("Necesito asesoría.");
        assertThat(email.messageId()).isEqualTo("<CADdZXf0@mail.gmail.com>");
        assertThat(email.headers()).containsEntry("precedence", "bulk");
    }

    /** Fields the adapter does not model must not break the mapping. */
    @Test
    void unknownFieldsInTheRetrievedMessageAreIgnored() throws Exception {
        assertThat(objectMapper.readValue(RETRIEVED, ResendReceivedEmail.class)).isNotNull();
    }
}
