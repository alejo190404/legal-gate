package com.legalgate.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.legalgate.mail.config.MailIngressProperties;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.NormalizedInboundEmail;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The stripper's own shapes are covered in {@link QuotedReplyStripperTests}. These cover what only
 * the ingestion service decides: that quoted history is cut before boilerplate, and that the kill
 * switch reaches the body.
 */
class InboundEmailIngestionServiceQuotedReplyTests {

    private static final String REPLY = "Sí, es un contrato de arrendamiento.";

    /** An Outlook reply with no gateway trailer — only the switch decides what happens to it. */
    private static final String QUOTED_ONLY = REPLY + """


            De: Vargas & Asociados <firma-demo@intake.legal-gate.co>
            Enviado el: martes, 9 de septiembre de 2026 10:14
            Para: Juan Pérez <juan@example.com>

            ¿Qué tipo de contrato es?
            """;

    /** The same reply from a corporate sender, whose gateway appends its notice at the bottom. */
    private static final String QUOTED_AND_BOILERPLATE = QUOTED_ONLY + """

            AVISO LEGAL: este mensaje es confidencial y de uso exclusivo del destinatario.
            """;

    private final TenantLookupService tenantLookupService = mock(TenantLookupService.class);
    private final InboundEmailClient inboundEmailClient = mock(InboundEmailClient.class);

    private InboundEmailIngestionService serviceWithStrippingEnabled(boolean enabled) {
        when(tenantLookupService.tenantForIntakeEmail(any())).thenReturn(Optional.of("tenant-1"));
        return new InboundEmailIngestionService(
                tenantLookupService,
                inboundEmailClient,
                new EmailBoilerplateStripper(),
                new QuotedReplyStripper(),
                new MailIngressProperties(null, null,
                        new MailIngressProperties.IntakeOrchestrator(
                                URI.create("http://localhost:8081"), "service-token"),
                        enabled));
    }

    private String ingestedPlain(InboundEmailIngestionService service, String body) {
        service.ingest(new NormalizedInboundEmail(
                List.of("firma-demo@intake.legal-gate.co"),
                "juan@example.com",
                "Juan Pérez <juan@example.com>",
                "Re: Su consulta",
                "<msg-1@example.com>",
                body,
                null,
                false));
        ArgumentCaptor<InboundEmailReceived> event = ArgumentCaptor.forClass(InboundEmailReceived.class);
        verify(inboundEmailClient).send(event.capture());
        return event.getValue().plain();
    }

    @Test
    void quotedHistoryIsCutOnTheWayThrough() {
        assertThat(ingestedPlain(serviceWithStrippingEnabled(true), QUOTED_ONLY)).isEqualTo(REPLY);
    }

    @Test
    void quotedHistoryAndBoilerplateBothGo() {
        assertThat(ingestedPlain(serviceWithStrippingEnabled(true), QUOTED_AND_BOILERPLATE))
                .isEqualTo(REPLY);
    }

    @Test
    void theKillSwitchLeavesQuotedHistoryInPlace() {
        assertThat(ingestedPlain(serviceWithStrippingEnabled(false), QUOTED_ONLY))
                .isEqualTo(QUOTED_ONLY);
    }
}
