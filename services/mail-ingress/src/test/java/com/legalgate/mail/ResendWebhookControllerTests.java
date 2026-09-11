package com.legalgate.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.ResendReceivedEmail;
import com.legalgate.mail.service.InboundEmailClient;
import com.legalgate.mail.service.ResendReceivedEmailClient;
import com.legalgate.mail.service.TenantLookupService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "legalgate.mail-ingress.basic-auth.username=cloudmailin",
        "legalgate.mail-ingress.basic-auth.password=secret",
        "legalgate.mail-ingress.resend.webhook-secret=whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw",
        "legalgate.mail-ingress.resend.api-key=re_test_key",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class ResendWebhookControllerTests {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String RECEIVED_BODY =
            "{\"type\":\"email.received\",\"data\":{\"email_id\":\"5a2b1c\"}}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantLookupService tenantLookupService;

    @MockBean
    private InboundEmailClient inboundEmailClient;

    @MockBean
    private ResendReceivedEmailClient resendReceivedEmailClient;

    /**
     * Signs the way Svix does, written out rather than called through the production verifier —
     * a test that reuses the code under test to build its input proves only self-consistency.
     */
    private static String sign(String id, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET.substring("whsec_".length())), "HmacSHA256"));
        byte[] digest = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(digest);
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String body, String timestamp)
            throws Exception {
        String id = "msg_test";
        return mockMvc.perform(post("/webhooks/resend")
                .header("svix-id", id)
                .header("svix-timestamp", timestamp)
                .header("svix-signature", sign(id, timestamp, body))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private void stubStoredMessage(String receivedFor) {
        when(resendReceivedEmailClient.fetch("5a2b1c")).thenReturn(new ResendReceivedEmail(
                "<msg-1@example.com>",
                "Juan Pérez <juan@example.com>",
                "Consulta laboral",
                List.of("Firma Demo <firma-demo@intake.legal-gate.co>"),
                null,
                receivedFor,
                "Necesito asesoría sobre un despido.",
                "<p>Necesito asesoría sobre un despido.</p>",
                Map.of("Message-ID", "<msg-1@example.com>")));
    }

    @Test
    void aSignedReceivedEventIsIngested() throws Exception {
        stubStoredMessage("firma-demo+d0123456789abcdef@intake.legal-gate.co");
        when(tenantLookupService.tenantForIntakeEmail("firma-demo@intake.legal-gate.co"))
                .thenReturn(Optional.of("firma-demo"));
        when(inboundEmailClient.send(any())).thenReturn("received");

        postWebhook(RECEIVED_BODY, now())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("received"))
                .andExpect(jsonPath("$.tenantId").value("firma-demo"));
    }

    /** The Reply Token rides on received_for, so it must reach intake unstripped. */
    @Test
    void thePlusAddressedRecipientIsForwardedUntouched() throws Exception {
        stubStoredMessage("firma-demo+d0123456789abcdef@intake.legal-gate.co");
        when(tenantLookupService.tenantForIntakeEmail("firma-demo@intake.legal-gate.co"))
                .thenReturn(Optional.of("firma-demo"));
        when(inboundEmailClient.send(any())).thenReturn("received");

        postWebhook(RECEIVED_BODY, now()).andExpect(status().isOk());

        ArgumentCaptor<InboundEmailReceived> event = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().recipients())
                .first().isEqualTo("firma-demo+d0123456789abcdef@intake.legal-gate.co");
    }

    /** What the Stage 3 gate expects: verified and fetched, but no tenant owns that address. */
    @Test
    void anAddressMatchingNoTenantIsRejectedAfterTheBodyIsFetched() throws Exception {
        stubStoredMessage("anything@inbound.resend.app");
        when(tenantLookupService.tenantForIntakeEmail(any())).thenReturn(Optional.empty());

        postWebhook(RECEIVED_BODY, now()).andExpect(status().isNotFound());

        verify(resendReceivedEmailClient).fetch("5a2b1c");
    }

    @Test
    void aBadSignatureIsRejectedBeforeAnythingIsFetched() throws Exception {
        mockMvc.perform(post("/webhooks/resend")
                        .header("svix-id", "msg_test")
                        .header("svix-timestamp", now())
                        .header("svix-signature", "v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECEIVED_BODY))
                .andExpect(status().isUnauthorized());

        verify(resendReceivedEmailClient, never()).fetch(any());
    }

    @Test
    void aStaleTimestampIsRejectedEvenWithAValidSignature() throws Exception {
        String stale = String.valueOf(Instant.now().minusSeconds(10 * 60).getEpochSecond());

        postWebhook(RECEIVED_BODY, stale).andExpect(status().isUnauthorized());

        verify(resendReceivedEmailClient, never()).fetch(any());
    }

    @Test
    void aNonReceivedEventIsAcknowledgedAndDropped() throws Exception {
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"5a2b1c\"}}";

        postWebhook(body, now())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored_event_type"));

        verify(resendReceivedEmailClient, never()).fetch(any());
    }

    /** A failed fetch must be a 5xx so Resend retries, not a 200 that loses the message. */
    @Test
    void aFailedBodyFetchSurfacesAsAServerError() throws Exception {
        when(resendReceivedEmailClient.fetch("5a2b1c")).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "resend_retrieve_failed"));

        postWebhook(RECEIVED_BODY, now()).andExpect(status().is5xxServerError());

        verify(inboundEmailClient, never()).send(any());
    }
}
