package com.legalgate.intake.service;

import static com.legalgate.intake.service.OutboundMailEnvelopeTests.envelope;
import static com.legalgate.intake.service.OutboundMailEnvelopeTests.notification;
import static com.legalgate.intake.service.OutboundMailEnvelopeTests.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.model.NotificationOutboxItem;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** CloudMailin's own payload shape. The envelope rules are in {@link OutboundMailEnvelopeTests}. */
class CloudMailinOutboundEmailClientTests {

    @Test
    void everyMessageCarriesItsOwnIdentityOnTheSendingDomain() {
        Map<String, Object> payload = client().payload(notification("CLIENT", null), null);

        assertThat(headers(payload)).containsEntry("Message-ID", "<notification-1@legal-gate.co>");
    }

    @Test
    void payloadUsesCloudMailinsFieldNames() {
        Map<String, Object> payload = client().payload(withIcs(), null);

        assertThat(payload)
                .containsEntry("to", "cliente@example.com")
                .containsEntry("plain", "Cuerpo")
                .containsEntry("test_mode", false)
                .containsEntry("tags", java.util.List.of(
                        "legalgate", "consultation", "tenant:firma-demo", "DIAGNOSTICS_QUESTION", "CLIENT"));
        assertThat(attachment(payload))
                .containsEntry("file_name", "legalgate-consultation.ics")
                .containsEntry("content_type", "text/calendar; method=REQUEST; charset=UTF-8");
    }

    @Test
    void theEnvelopesThreadHeadersTravelWithThePayload() {
        Map<String, Object> payload = client().payload(notification("CLIENT", null), "<CAF=original@mail.gmail.com>");

        assertThat(headers(payload))
                .containsEntry("In-Reply-To", "<CAF=original@mail.gmail.com>")
                .containsEntry("References", "<CAF=original@mail.gmail.com>");
    }

    @Test
    void staffMailStaysOutOfTheConsultationThread() {
        Map<String, Object> payload = client().payload(notification("LAWYER", null), "<CAF=original@mail.gmail.com>");

        assertThat(headers(payload)).containsOnlyKeys("Message-ID");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> headers(Map<String, Object> payload) {
        return (Map<String, String>) payload.get("headers");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> attachment(Map<String, Object> payload) {
        return ((java.util.List<Map<String, String>>) payload.get("attachments")).get(0);
    }

    private CloudMailinOutboundEmailClient client() {
        return new CloudMailinOutboundEmailClient(
                properties(false, null), RestClient.builder(), envelope("Vargas & Asociados"));
    }

    private NotificationOutboxItem withIcs() {
        NotificationOutboxItem base = notification("CLIENT", null);
        return new NotificationOutboxItem(
                base.id(), base.tenantId(), base.consultationId(), base.eventId(), base.type(), base.recipientRole(),
                base.recipientEmail(), base.fromEmail(), base.subject(), base.body(), null, "BEGIN:VCALENDAR",
                base.status(), base.attempts(), null, null, null, null, null);
    }
}
