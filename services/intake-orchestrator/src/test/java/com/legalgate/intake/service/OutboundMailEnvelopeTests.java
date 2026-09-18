package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The ADR 0004 guarantees that hold whichever provider carries the message. */
class OutboundMailEnvelopeTests {

    /** The plus-addressed sender a Diagnostics message is queued with; the Reply Token is its tag. */
    static final String TAGGED_ADDRESS = "firma-demo+d0f1e2d3c4b5a69788796a5b4c3d2e1f0@intake.legal-gate.co";

    @Test
    void clientFacingMailSendsUnderTheFirmName() {
        String from = envelope("Vargas & Asociados").fromHeader(notification("CLIENT", TAGGED_ADDRESS));

        assertThat(from).isEqualTo("\"Vargas & Asociados\" <firma-demo@intake.legal-gate.co>");
    }

    @Test
    void theReplyTokenTravelsInReplyToSoFromStaysTheFirmsStableAddress() {
        OutboundMailEnvelope envelope = envelope("Vargas & Asociados");
        NotificationOutboxItem notification = notification("CLIENT", TAGGED_ADDRESS);

        assertThat(envelope.fromEmail(notification)).isEqualTo("firma-demo@intake.legal-gate.co");
        assertThat(envelope.clientHeaders(notification, "<CAF=original@mail.gmail.com>"))
                .containsEntry("Reply-To", TAGGED_ADDRESS);
    }

    @Test
    void aConsultationWithNoAnchorStillSaysWhereTheReplyGoes() {
        assertThat(envelope("Vargas & Asociados").clientHeaders(notification("CLIENT", TAGGED_ADDRESS), null))
                .containsExactly(entry("Reply-To", TAGGED_ADDRESS));
    }

    @Test
    void staffMailGetsNoReplyTo() {
        assertThat(envelope("Vargas & Asociados").clientHeaders(notification("LAWYER", TAGGED_ADDRESS), null))
                .isEmpty();
    }

    @Test
    void staffMailKeepsLegalGateBranding() {
        String from = envelope("Vargas & Asociados").fromHeader(notification("LAWYER", null));

        assertThat(from).isEqualTo("LegalGate Agenda <agenda@legal-gate.co>");
    }

    @Test
    void quotesAndEscapesFirmNamesThatWouldBreakTheFromHeader() {
        String from = envelope("Vargas, \"Los\" Abogados\r\nBcc: leak@example.com")
                .fromHeader(notification("CLIENT", null));

        assertThat(from).isEqualTo("\"Vargas, \\\"Los\\\" Abogados Bcc: leak@example.com\" <agenda@legal-gate.co>");
    }

    @Test
    void mailAboutAConsultationThreadsUnderThePotentialClientsOriginalMessage() {
        assertThat(envelope("Vargas & Asociados")
                .clientHeaders(notification("CLIENT", null), "<CAF=original@mail.gmail.com>"))
                .containsEntry("In-Reply-To", "<CAF=original@mail.gmail.com>")
                .containsEntry("References", "<CAF=original@mail.gmail.com>");
    }

    @Test
    void wrapsAStoredAnchorThatArrivedWithoutAngleBrackets() {
        assertThat(envelope("Vargas & Asociados")
                .clientHeaders(notification("CLIENT", null), "CAF=original@mail.gmail.com"))
                .containsEntry("In-Reply-To", "<CAF=original@mail.gmail.com>")
                .containsEntry("References", "<CAF=original@mail.gmail.com>");
    }

    @Test
    void staffMailStaysOutOfTheConsultationThread() {
        assertThat(envelope("Vargas & Asociados")
                .clientHeaders(notification("LAWYER", null), "<CAF=original@mail.gmail.com>"))
                .isEmpty();
    }

    @Test
    void aConsultationWithNoAnchorSendsWithNoThreadingHeaders() {
        assertThat(envelope("Vargas & Asociados").clientHeaders(notification("CLIENT", null), null)).isEmpty();
    }

    @Test
    void stripsAnAnchorThatWouldForgeAnExtraHeader() {
        assertThat(envelope("Vargas & Asociados")
                .clientHeaders(notification("CLIENT", null), "<original@mail.gmail.com>\r\nBcc: leak@example.com"))
                .containsEntry("In-Reply-To", "<original@mail.gmail.comBcc:leak@example.com>");
    }

    @Test
    void theSendingDomainComesFromTheAddressTheMessageIsSentFrom() {
        OutboundMailEnvelope envelope = envelope("Vargas & Asociados");

        assertThat(envelope.senderDomain(notification("CLIENT", TAGGED_ADDRESS)))
                .isEqualTo("intake.legal-gate.co");
        assertThat(envelope.senderDomain(notification("LAWYER", null))).isEqualTo("legal-gate.co");
    }

    static OutboundMailEnvelope envelope(String tenantDisplayName) {
        IntakeRepository repository = mock(IntakeRepository.class);
        when(repository.tenantDisplayName(anyString())).thenReturn(Optional.ofNullable(tenantDisplayName));
        IntakeProperties properties = properties(false, null);
        return new OutboundMailEnvelope(properties, new FirmNameResolver(repository, properties));
    }

    static IntakeProperties properties(boolean outboundTestMode, String resendApiKey) {
        return new IntakeProperties(
                "memory", false, "intake.legal-gate.co", null, null, null, null, null, null,
                false, null, null, null, null, outboundTestMode, "test-service-token", "sk_test",
                "https://api.workos.com", null, resendApiKey);
    }

    static NotificationOutboxItem notification(String recipientRole, String fromEmail) {
        return new NotificationOutboxItem(
                "notification-1", "firma-demo", "consultation-1", null, "DIAGNOSTICS_QUESTION", recipientRole,
                "cliente@example.com", fromEmail, "Asunto", "Cuerpo", null, null,
                "PENDING", 0, null, null, null, null, null);
    }
}
