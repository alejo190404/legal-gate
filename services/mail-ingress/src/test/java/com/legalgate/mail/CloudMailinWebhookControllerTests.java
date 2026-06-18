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
import com.legalgate.mail.service.InboundEmailPublisher;
import com.legalgate.mail.service.TenantLookupService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Autowired;
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
    private InboundEmailPublisher inboundEmailPublisher;

    @Test
    void rejectsRequestsWithoutBasicAuth() throws Exception {
        mockMvc.perform(post("/webhooks/cloudmailin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"LegalGate CloudMailin\""))
                .andExpect(jsonPath("$.error").value("unauthorized"));

        verify(inboundEmailPublisher, never()).publish(any());
    }

    @Test
    void publishesCloudMailinMessageForKnownRecipient() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.tenantId").value("firma-demo"))
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailPublisher).publish(eventCaptor.capture());
        InboundEmailReceived event = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.tenantId()).isEqualTo("firma-demo");
        org.assertj.core.api.Assertions.assertThat(event.recipients()).contains("intake@firma.test");
        org.assertj.core.api.Assertions.assertThat(event.subject()).isEqualTo("Consulta laboral");
        org.assertj.core.api.Assertions.assertThat(event.plain()).isEqualTo("Necesito orientacion.");
    }

    @Test
    void returnsNotFoundForUnknownRecipientWithoutPublishing() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_intake_recipient"));

        verify(inboundEmailPublisher, never()).publish(any());
    }

    @Test
    void returnsRetryableStatusWhenRabbitPublishFails() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("intake@firma.test")))
                .thenReturn(Optional.of("firma-demo"));
        doThrow(new AmqpConnectException(new RuntimeException("broker unavailable")))
                .when(inboundEmailPublisher)
                .publish(any());

        mockMvc.perform(post("/webhooks/cloudmailin")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(samplePayload()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("rabbitmq_publish_failed"));
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

        verify(inboundEmailPublisher, never()).publish(any());
    }

    @Test
    void rejectsMailerSendPayloadWithInvalidSecret() throws Exception {
        mockMvc.perform(post("/webhooks/mailersend")
                        .header("X-MailerSend-Webhook-Secret", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mailerSendInboundPayload()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_mailersend_signature"));

        verify(inboundEmailPublisher, never()).publish(any());
    }

    @Test
    void publishesMailerSendInboundMessageForKnownRecipient() throws Exception {
        when(tenantLookupService.tenantForIntakeEmail(eq("firma-demo@intake.legal-gate.co")))
                .thenReturn(Optional.of("firma-demo"));

        mockMvc.perform(post("/webhooks/mailersend")
                        .header("X-MailerSend-Webhook-Secret", "mailersend-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mailerSendInboundPayload()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.tenantId").value("firma-demo"))
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        ArgumentCaptor<InboundEmailReceived> eventCaptor = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailPublisher).publish(eventCaptor.capture());
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
