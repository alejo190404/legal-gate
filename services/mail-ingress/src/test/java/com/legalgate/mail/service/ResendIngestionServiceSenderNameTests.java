package com.legalgate.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.legalgate.mail.model.NormalizedInboundEmail;
import com.legalgate.mail.model.ResendReceivedEmail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Resend's top-level {@code from} is the bare address; the display name lives only in the raw From
 * header. Dropping it made every Resend consultation read "Unknown client" in the console.
 */
class ResendIngestionServiceSenderNameTests {

    private final ResendReceivedEmailClient client = mock(ResendReceivedEmailClient.class);
    private final InboundEmailIngestionService ingestion = mock(InboundEmailIngestionService.class);
    private final ResendIngestionService service = new ResendIngestionService(
            client, new RecipientAddressExtractor(), ingestion, new AutoResponderDetector());

    @Test
    void theDisplayNameComesFromTheFromHeader() {
        NormalizedInboundEmail normalized = ingest(Map.of(
                "from", "\"alejandro barragan\" <alejo190404@gmail.com>"));

        assertThat(normalized.headerFrom()).isEqualTo("\"alejandro barragan\" <alejo190404@gmail.com>");
        assertThat(normalized.envelopeFrom()).isEqualTo("alejo190404@gmail.com");
    }

    /** A sender with no display name, or a provider that omits the header, still routes. */
    @Test
    void withoutTheHeaderTheBareAddressStandsIn() {
        assertThat(ingest(Map.of("return-path", "alejo190404@gmail.com")).headerFrom())
                .isEqualTo("alejo190404@gmail.com");
    }

    private NormalizedInboundEmail ingest(Map<String, Object> headers) {
        when(client.fetch("msg-1")).thenReturn(new ResendReceivedEmail(
                "<CADdZXf2@mail.gmail.com>", "alejo190404@gmail.com", "Consulta jurídica",
                List.of("aba-servicio-juridico-2f57e6e4@intake.legal-gate.co"), List.of(),
                List.of("aba-servicio-juridico-2f57e6e4@intake.legal-gate.co"),
                "Buenas tardes", "<p>Buenas tardes</p>", headers));

        service.ingest("msg-1");

        ArgumentCaptor<NormalizedInboundEmail> captor = ArgumentCaptor.forClass(NormalizedInboundEmail.class);
        verify(ingestion).ingest(captor.capture());
        return captor.getValue();
    }
}
