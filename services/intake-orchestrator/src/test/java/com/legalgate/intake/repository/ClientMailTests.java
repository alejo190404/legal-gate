package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ClientMailIntentRequest;
import com.legalgate.intake.classifier.ClientMailIntentResponse;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.classifier.ConsultationClassifierResponse;
import com.legalgate.intake.classifier.ConsultationDiagnosticsRequest;
import com.legalgate.intake.classifier.ConsultationDiagnosticsResponse;
import com.legalgate.intake.clients.ClientMailService;
import com.legalgate.intake.clients.ClientsModels;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.clients.ClientsService;
import com.legalgate.intake.clients.InMemoryClientsRepository;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.mail.InboundMailRouter;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.service.DiagnosticsService;
import com.legalgate.intake.service.EmailTemplateRenderer;
import com.legalgate.intake.service.FirmNameResolver;
import com.legalgate.intake.service.IntakeService;

/**
 * Seam 1 for Client mail: assertions are on what was queued and what Client Correspondence says.
 */
class ClientMailTests extends ClientsSeam {

    @Test
    void mailFromAContactAddressNeverBecomesAConsultationWithADiagnosticsPrompt() {
        firm(PROMPT);

        router.receive(mail("GC@Acme.co", "Acme <gc@acme.co>", "<c-1@acme.co>"));
        clientMail.processDueClientMail();

        assertThat(intake.consultationsForTenant(TENANT, null, null, null, 50).items()).isEmpty();
        assertThat(queued()).extracting(NotificationOutboxItem::type)
                .containsExactlyInAnyOrder("CLIENT_MAIL_FORWARD", "CLIENT_RECEIPT")
                .doesNotContain("NON_ENGAGEMENT_NOTICE");
    }

    @Test
    void mailFromAContactAddressNeverBecomesAConsultationWithoutADiagnosticsPrompt() {
        firm(null);

        router.receive(mail("ceo@acme.co", "CEO <ceo@acme.co>", "<c-2@acme.co>"));

        assertThat(intake.consultationsForTenant(TENANT, null, null, null, 50).items()).isEmpty();
        assertThat(clients.correspondence(TENANT, acme.id())).hasSize(1);
    }

    @Test
    void aPendingDiagnosticsReplyTokenWinsOverAContactAddress() {
        firm(PROMPT);
        ConsultationResponse pending = router.receive(mail("maria@example.com", "Maria <maria@example.com>", "<d-1@x.co>"))
                .consultation();
        String token = intake.diagnosticsSessionForConsultation(TENANT, pending.id()).orElseThrow().replyToken();
        // Engaged partway: her address is now on file for a Client.
        clients.create(TENANT, "user_admin", new ClientRequest("Maria Perez", ClientsModels.PERSON, null, null, null,
                ClientsFixtures.ANA, List.of("maria@example.com")));

        String replyAddress = TENANT + "+d" + token + "@intake.legal-gate.co";
        InboundMailRouter.Received received = router.receive(new InboundEmailReceived("e-r", TENANT, replyAddress,
                List.of(replyAddress), "maria@example.com", "Maria <maria@example.com>", "Re: Consulta",
                "<d-1-reply@x.co>", "El 3 de marzo.", null, Instant.now()));

        assertThat(received.consultation().id()).isEqualTo(pending.id());
        assertThat(received.correspondence()).isNull();
    }

    @Test
    void onlyTheEnvelopeSenderIsMatchedNeverTheDisplayNameOrReplyTo() {
        firm(PROMPT);

        // A stranger signing as the Client's general counsel is a stranger.
        InboundMailRouter.Received forged = router.receive(
                mail("attacker@evil.co", "Acme GC <gc@acme.co>", "<f-1@evil.co>"));
        assertThat(forged.consultation()).isNotNull();
        assertThat(forged.correspondence()).isNull();

        // The real address with a forged display name and Reply-To still answers only the address on file.
        router.receive(new InboundEmailReceived("e-f2", TENANT, TENANT + "@intake.legal-gate.co",
                List.of(TENANT + "@intake.legal-gate.co"), "gc@acme.co", "Someone <reply-here@evil.co>",
                "Hola", "<f-2@acme.co>", "Hola.", null, Instant.now()));
        clientMail.processDueClientMail();

        assertThat(queued()).filteredOn(item -> "CLIENT".equals(item.recipientRole()))
                .extracting(NotificationOutboxItem::recipientEmail)
                .containsExactly("gc@acme.co");
    }

    @Test
    void eachClientEmailIsForwardedToTheResponsibleLawyerWithAThreadedFirmVoiceReceipt() {
        firm(PROMPT);

        router.receive(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<c-3@acme.co>"));
        clientMail.processDueClientMail();

        List<NotificationOutboxItem> queued = queued();
        NotificationOutboxItem forward = only(queued, "CLIENT_MAIL_FORWARD");
        assertThat(forward.recipientRole()).isEqualTo("LAWYER");
        assertThat(forward.recipientEmail()).isEqualTo("ana@firm.co");
        assertThat(forward.replyTo()).isEqualTo("gc@acme.co");
        assertThat(forward.body()).contains("Como va mi contrato?").contains("Acme SAS");

        NotificationOutboxItem receipt = only(queued, "CLIENT_RECEIPT");
        assertThat(receipt.recipientRole()).isEqualTo("CLIENT");
        assertThat(receipt.recipientEmail()).isEqualTo("gc@acme.co");
        assertThat(receipt.fromEmail()).isEqualTo(TENANT + "@intake.legal-gate.co");
        assertThat(receipt.replyTo()).isNull();
        assertThat(receipt.subject()).isEqualTo("Re: Estado de mi contrato");
        assertThat(receipt.htmlBody()).contains("Vargas &amp; Asociados").contains("Asunto: Estado de mi contrato");
        assertThat(receipt.body()).contains("Su mensaje · Asunto: Estado de mi contrato");
        assertThat(receipt.body()).contains("Vargas & Asociados").doesNotContain("LegalGate");
        assertThat(receipt.body()).doesNotContain("no crea una relacion abogado-cliente");

        Correspondence entry = clients.correspondence(TENANT, acme.id()).get(0);
        assertThat(receipt.correspondenceId()).isEqualTo(entry.id());
        assertThat(entry.messageId()).isEqualTo("<c-3@acme.co>");
        assertThat(entry.outcome()).isEqualTo(ClientsModels.FORWARDED);
        assertThat(entry.contactAddress()).isEqualTo("gc@acme.co");
    }

    @Test
    void autoRespondersAreRecordedAndNeverAnswered() {
        firm(PROMPT);

        router.receive(autoResponder(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<ooo@acme.co>")));
        clientMail.processDueClientMail();

        assertThat(queued()).isEmpty();
        assertThat(clients.correspondence(TENANT, acme.id()))
                .extracting(Correspondence::outcome).containsExactly(ClientsModels.IGNORED);
    }

    @Test
    void aRedeliveredWebhookIsHandledOnce() {
        firm(PROMPT);

        router.receive(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<c-4@acme.co>"));
        clientMail.processDueClientMail();
        router.receive(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<c-4@acme.co>"));
        clientMail.processDueClientMail();

        assertThat(clients.correspondence(TENANT, acme.id())).hasSize(1);
        assertThat(queued()).hasSize(2);
    }
}
