package com.legalgate.intake.service;

import static com.legalgate.intake.service.OutboundMailEnvelopeTests.envelope;
import static com.legalgate.intake.service.OutboundMailEnvelopeTests.notification;
import static com.legalgate.intake.service.OutboundMailEnvelopeTests.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Resend's own payload shape. The envelope rules are in {@link OutboundMailEnvelopeTests}. */
class ResendOutboundEmailClientTests {

    @Test
    void payloadUsesResendsFieldNames() {
        Map<String, Object> payload = client(properties(false, "re_key")).payload(notification("CLIENT", null), null);

        assertThat(payload)
                .containsEntry("to", "cliente@example.com")
                .containsEntry("text", "Cuerpo")
                .containsEntry("from", "\"Vargas & Asociados\" <agenda@legal-gate.co>")
                .doesNotContainKeys("plain", "test_mode");
    }

    @Test
    void carriesNoMessageIdBecauseResendSilentlyReplacesIt() {
        Map<String, Object> payload = client(properties(false, "re_key"))
                .payload(notification("CLIENT", null), "<CAF=original@mail.gmail.com>");

        assertThat(headers(payload))
                .doesNotContainKey("Message-ID")
                .containsEntry("In-Reply-To", "<CAF=original@mail.gmail.com>")
                .containsEntry("References", "<CAF=original@mail.gmail.com>");
    }

    @Test
    void staffMailCarriesNoHeadersAtAll() {
        Map<String, Object> payload = client(properties(false, "re_key"))
                .payload(notification("LAWYER", null), "<CAF=original@mail.gmail.com>");

        assertThat(payload).doesNotContainKey("headers");
    }

    @Test
    void tagsAreNameValuePairsFoldedIntoResendsCharset() {
        Map<String, Object> payload = client(properties(false, "re_key"))
                .payload(tenant("Bufete Peña & Cía"), null);

        assertThat(tags(payload)).containsExactly(
                Map.of("name", "app", "value", "legalgate"),
                Map.of("name", "tenant", "value", "Bufete_Pe_a_C_a"),
                Map.of("name", "type", "value", "DIAGNOSTICS_QUESTION"),
                Map.of("name", "role", "value", "CLIENT"));
    }

    @Test
    void theCalendarInviteAlwaysCarriesItsContentTypeOrOutlookRendersItAsAFile() {
        Map<String, Object> payload = client(properties(false, "re_key")).payload(withIcs(), null);

        assertThat(attachment(payload))
                .containsEntry("filename", "legalgate-consultation.ics")
                .containsEntry("content_type", "text/calendar; method=REQUEST; charset=UTF-8");
    }

    @Test
    void testModeRedirectsTheRecipientAndChangesNothingElse() {
        Map<String, Object> payload = client(properties(true, "re_key")).payload(notification("CLIENT", null), null);

        assertThat(payload)
                .containsEntry("to", "delivered@resend.dev")
                .containsEntry("subject", "Asunto");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> headers(Map<String, Object> payload) {
        return (Map<String, String>) payload.get("headers");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> tags(Map<String, Object> payload) {
        return (List<Map<String, String>>) payload.get("tags");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> attachment(Map<String, Object> payload) {
        return ((List<Map<String, String>>) payload.get("attachments")).get(0);
    }

    private ResendOutboundEmailClient client(IntakeProperties properties) {
        return new ResendOutboundEmailClient(properties, RestClient.builder(), envelope("Vargas & Asociados"));
    }

    private NotificationOutboxItem tenant(String tenantId) {
        return rebuild(notification("CLIENT", null), tenantId, null);
    }

    private NotificationOutboxItem withIcs() {
        return rebuild(notification("CLIENT", null), "firma-demo", "BEGIN:VCALENDAR");
    }

    private NotificationOutboxItem rebuild(NotificationOutboxItem base, String tenantId, String icsContent) {
        return new NotificationOutboxItem(
                base.id(), tenantId, base.consultationId(), base.eventId(), base.type(), base.recipientRole(),
                base.recipientEmail(), base.fromEmail(), base.subject(), base.body(), null, icsContent,
                base.status(), base.attempts(), null, null, null, null, null);
    }
}
