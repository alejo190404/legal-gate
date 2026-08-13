package com.legalgate.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.service.InboundEmailClient;
import com.legalgate.mail.service.TenantLookupService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClientException;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "legalgate.mail-ingress.basic-auth.username=cloudmailin",
        "legalgate.mail-ingress.basic-auth.password=secret",
        "legalgate.mail-ingress.mailersend.webhook-secret=mailersend-secret",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class CloudMailinWebhookControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantLookupService tenantLookupService;

    @MockBean
    private InboundEmailClient inboundEmailClient;

    @Test
    void rejectsRequestsWithoutBasicAuth() throws Exception {
        mockMvc.perform(post("/webhooks/cloudmailin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"LegalGate CloudMailin\""))
                .andExpect(jsonPath("$.error").value("unauthorized"));

        verify(inboundEmailClient, never()).send(any());
    }

    @Test
    void sendsCloudMailinMessageForKnownRecipient() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("received"))
                .andExpect(jsonPath("$.tenantId").value("firma-demo"))
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(eventCaptor.capture());
        InboundEmailReceived event = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.tenantId()).isEqualTo("firma-demo");
        org.assertj.core.api.Assertions.assertThat(event.recipients()).contains("intake@firma.test");
        org.assertj.core.api.Assertions.assertThat(event.subject()).isEqualTo("Consulta laboral");
        org.assertj.core.api.Assertions.assertThat(event.plain()).isEqualTo("Necesito orientacion.");
    }

    @Test
    void returnsNotFoundForUnknownRecipientWithoutSending() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_intake_recipient"));

        verify(inboundEmailClient, never()).send(any());
    }

    @Test
    void returnsRetryableStatusWhenIntakeIsUnavailable() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.of("firma-demo"));
        doThrow(new RestClientException("intake unavailable"))
                .when(inboundEmailClient)
                .send(any());

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("intake_orchestrator_unavailable"));
    }

    @Test
    void acceptsMailerSendWebhookTestPayload() throws Exception {
        mockMvc.perform(post("/webhooks/mailersend")
                        .header("X-MailerSend-Webhook-Secret", "mailersend-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "webhook.test",
                                  "data": {}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(inboundEmailClient, never()).send(any());
    }

    @Test
    void rejectsMailerSendPayloadWithInvalidSecret() throws Exception {
        mockMvc.perform(post("/webhooks/mailersend")
                        .header("X-MailerSend-Webhook-Secret", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mailerSendInboundPayload()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_mailersend_signature"));

        verify(inboundEmailClient, never()).send(any());
    }

    @Test
    void sendsMailerSendInboundMessageForKnownRecipient() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("firma-demo@intake.legal-gate.co")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/mailersend")
                        .header("X-MailerSend-Webhook-Secret", "mailersend-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mailerSendInboundPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("received"))
                .andExpect(jsonPath("$.tenantId").value("firma-demo"))
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(eventCaptor.capture());
        InboundEmailReceived event = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.tenantId()).isEqualTo("firma-demo");
        org.assertj.core.api.Assertions.assertThat(event.envelopeTo()).isEqualTo("firma-demo@intake.legal-gate.co");
        org.assertj.core.api.Assertions.assertThat(event.recipients()).containsExactly("firma-demo@intake.legal-gate.co");
        org.assertj.core.api.Assertions.assertThat(event.envelopeFrom()).isEqualTo("maria@example.com");
        org.assertj.core.api.Assertions.assertThat(event.headerFrom()).isEqualTo("Maria Perez <maria@example.com>");
        org.assertj.core.api.Assertions.assertThat(event.subject()).isEqualTo("Consulta familia");
        org.assertj.core.api.Assertions.assertThat(event.messageId()).isEqualTo("ms-message-123");
        org.assertj.core.api.Assertions.assertThat(event.plain()).isEqualTo("Necesito orientacion por custodia.");
    }

    // Golden example pinning the reply-correlation contract. The same literal address is
    // asserted in the intake service (DiagnosticsContractTests) to yield this exact token,
    // so casing or character-set drift on either side breaks a test rather than correlation.
    @Test
    void resolvesTenantFromAPlusTaggedReplyAddressAndForwardsRecipientsUnchanged() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("firma-demo@intake.legal-gate.co")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replyPayload("firma-demo+d9f2c7a1e4b8d0356af71c2e5d8093b4a@intake.legal-gate.co")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("firma-demo"));

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().recipients())
                .containsExactly("firma-demo+d9f2c7a1e4b8d0356af71c2e5d8093b4a@intake.legal-gate.co");
    }

    @Test
    void flagsAutomatedResponderMail() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "headers": {
                                    "from": "Maria Perez <maria@example.com>",
                                    "subject": "Out of office",
                                    "auto_submitted": "auto-replied"
                                  },
                                  "envelope": {
                                    "to": "intake@firma.test",
                                    "recipients": ["intake@firma.test"],
                                    "from": "maria@example.com"
                                  },
                                  "plain": "Estoy de vacaciones."
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().autoResponder()).isTrue();
    }

    @Test
    void ordinaryMailIsNotFlaggedAsAnAutomatedResponder() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isOk());

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().autoResponder()).isFalse();
    }

    // Email Boilerplate: a corporate gateway appends its notice below the sender's own words, so
    // what the intake service receives must stop where the sender stopped writing.
    @Test
    void removesACorporateLegalNoticeFromThePlainBody() throws Exception {
        assertPlainBodyForwardedAs(
                """
                El contrato era a termino fijo y me despidieron sin justa causa.

                AVISO LEGAL: El presente correo electronico no representa la opinion oficial de la
                PONTIFICIA UNIVERSIDAD JAVERIANA. Este mensaje es confidencial.""",
                "El contrato era a termino fijo y me despidieron sin justa causa.");
    }

    // The markers are stored unaccented, so a gateway that drops tildes matches on case alone.
    // The case worth pinning is the other one: a notice that keeps its tildes must still match.
    @Test
    void removesANoticeThatKeepsItsTildesAndMixedCase() throws Exception {
        assertPlainBodyForwardedAs(
                """
                Trabajé tres años en esa empresa.

                La Información contenida en este mensaje es privilegiada.""",
                "Trabajé tres años en esa empresa.");
    }

    // Both guards below turn a mis-detection into a no-op: nothing the potential client wrote is
    // ever lost, at the price of leaving boilerplate in place.
    @Test
    void keepsTheOriginalBodyWhenRemovalWouldLeaveNothing() throws Exception {
        String onlyBoilerplate = "\n\nAviso legal: este mensaje es confidencial.";
        assertPlainBodyForwardedAs(onlyBoilerplate, onlyBoilerplate);
    }

    @Test
    void keepsTheOriginalBodyWhenTheNoticeIsAtTheTop() throws Exception {
        String noticeFirst = """
                Aviso legal: este mensaje es confidencial.

                Buenas tardes, necesito ayuda con un despido injustificado.""";
        assertPlainBodyForwardedAs(noticeFirst, noticeFirst);
    }

    @Test
    void keepsAConsultationThatMentionsAvisoLegalMidSentence() throws Exception {
        String consultation = "Recibí un aviso legal de la universidad y no sé qué responder.";
        assertPlainBodyForwardedAs(consultation, consultation);
    }

    private void assertPlainBodyForwardedAs(String plain, String expected) throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithPlain(plain)))
                .andExpect(status().isOk());

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().plain()).isEqualTo(expected);
    }

    private String payloadWithPlain(String plain) {
        return """
                {
                  "headers": {
                    "from": "Maria Perez <maria@example.com>",
                    "subject": "Re: Consulta laboral",
                    "message_id": "<message-123@example.com>"
                  },
                  "envelope": {
                    "to": "intake@firma.test",
                    "recipients": ["intake@firma.test"],
                    "from": "maria@example.com"
                  },
                  "plain": "%s"
                }
                """.formatted(plain.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"));
    }

    private String replyPayload(String recipient) {
        return """
                {
                  "headers": {
                    "from": "Maria Perez <maria@example.com>",
                    "subject": "Re: Consulta laboral",
                    "message_id": "<reply-456@example.com>"
                  },
                  "envelope": {
                    "to": "%s",
                    "recipients": ["%s"],
                    "from": "maria@example.com"
                  },
                  "plain": "El contrato era a termino fijo."
                }
                """.formatted(recipient, recipient);
    }

    private String basicAuth() {
        String credentials = "cloudmailin:secret";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String samplePayload() {
        return """
                {
                  "headers": {
                    "from": "Maria Perez <maria@example.com>",
                    "subject": "Consulta laboral",
                    "message_id": "<message-123@example.com>"
                  },
                  "envelope": {
                    "to": "intake@firma.test",
                    "recipients": ["intake@firma.test"],
                    "from": "maria@example.com",
                    "remote_ip": "127.0.0.1"
                  },
                  "plain": "Necesito orientacion.",
                  "html": "<p>Necesito orientacion.</p>",
                  "attachments": []
                }
                """;
    }

    private String mailerSendInboundPayload() {
        return """
                {
                  "type": "inbound.message",
                  "data": {
                    "from": {
                      "email": "maria@example.com",
                      "name": "Maria Perez"
                    },
                    "recipients": [
                      {
                        "email": "firma-demo@intake.legal-gate.co",
                        "name": "LegalGate"
                      }
                    ],
                    "subject": "Consulta familia",
                    "message_id": "ms-message-123",
                    "text": {
                      "plain": "Necesito orientacion por custodia.",
                      "html": "<p>Necesito orientacion por custodia.</p>"
                    }
                  }
                }
                """;
    }
}
