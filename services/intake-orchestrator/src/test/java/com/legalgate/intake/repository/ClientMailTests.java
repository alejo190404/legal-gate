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
 * Seam 1 for Client mail: mail enters through the inbound router, the worker is driven by hand,
 * and assertions are on what was queued and what Client Correspondence says.
 */
class ClientMailTests {

    static final String TENANT = "firma-demo";
    static final String PROMPT = "Tomamos casos laborales.";

    final InMemoryIntakeRepository intake = new InMemoryIntakeRepository();
    final InMemoryClientsRepository clientsRepository = new InMemoryClientsRepository(intake);
    final StubClassifier classifier = new StubClassifier();
    final ClientsService clients = ClientsFixtures.clientsService(intake, clientsRepository);
    IntakeService intakeService;
    DiagnosticsService diagnostics;
    ClientMailService clientMail;
    InboundMailRouter router;

    Client acme;

    void firm(String diagnosticsPrompt) {
        intake.startTenantProvisioning("owner", "Vargas & Asociados", TENANT, TENANT + "@intake.legal-gate.co");
        ClientsFixtures.saveSettings(intake, TENANT, diagnosticsPrompt);
        intakeService = ClientsFixtures.intakeService(intake, classifier);
        diagnostics = new DiagnosticsService(intake, intakeService, classifier, ClientsFixtures.properties(),
                new EmailTemplateRenderer(), new FirmNameResolver(intake, ClientsFixtures.properties()));
        clientMail = new ClientMailService(clientsRepository, intake, intakeService, classifier,
                new EmailTemplateRenderer(), new FirmNameResolver(intake, ClientsFixtures.properties()));
        router = new InboundMailRouter(diagnostics, clientMail);
        acme = clients.create(TENANT, "user_admin", new ClientRequest("Acme SAS", ClientsModels.ORGANIZATION, null,
                null, null, ClientsFixtures.ANA, List.of("gc@acme.co", "ceo@acme.co")));
    }

    @Test
    void mailFromAContactAddressNeverBecomesAConsultationWithADiagnosticsPrompt() {
        firm(PROMPT);

        router.receive(mail("GC@Acme.co", "Acme <gc@acme.co>", "<c-1@acme.co>"));
        clientMail.processDueClientMail();

        assertThat(intake.consultationsForTenant(TENANT).consultations()).isEmpty();
        assertThat(queued()).extracting(NotificationOutboxItem::type)
                .containsExactlyInAnyOrder("CLIENT_MAIL_FORWARD", "CLIENT_RECEIPT")
                .doesNotContain("NON_ENGAGEMENT_NOTICE");
    }

    @Test
    void mailFromAContactAddressNeverBecomesAConsultationWithoutADiagnosticsPrompt() {
        firm(null);

        router.receive(mail("ceo@acme.co", "CEO <ceo@acme.co>", "<c-2@acme.co>"));

        assertThat(intake.consultationsForTenant(TENANT).consultations()).isEmpty();
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
        assertThat(receipt.htmlBody()).isNull();
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

    InboundEmailReceived mail(String envelopeFrom, String headerFrom, String messageId) {
        return new InboundEmailReceived("event-" + messageId, TENANT, TENANT + "@intake.legal-gate.co",
                List.of(TENANT + "@intake.legal-gate.co"), envelopeFrom, headerFrom, "Estado de mi contrato",
                messageId, "Como va mi contrato?", null, Instant.now());
    }

    InboundEmailReceived autoResponder(InboundEmailReceived email) {
        return new InboundEmailReceived(email.eventId(), email.tenantId(), email.envelopeTo(), email.recipients(),
                email.envelopeFrom(), email.headerFrom(), email.subject(), email.messageId(), email.plain(),
                email.html(), email.receivedAt(), true);
    }

    List<NotificationOutboxItem> queued() {
        return intake.claimPendingNotifications(50);
    }

    static NotificationOutboxItem only(List<NotificationOutboxItem> queued, String type) {
        return queued.stream().filter(item -> type.equals(item.type())).collect(onlyElement());
    }

    static <T> java.util.stream.Collector<T, ?, T> onlyElement() {
        return java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), list -> {
            assertThat(list).hasSize(1);
            return list.get(0);
        });
    }

    static final class StubClassifier implements ConsultationClassifierClient {
        final Deque<Object> intents = new ArrayDeque<>();
        int intentCalls;

        @Override
        public ConsultationClassifierResponse classify(ConsultationClassifierRequest request) {
            return new ConsultationClassifierResponse(0, "Laboral", "NORMAL", "Despido", "Resumen", "Maria", "Ruta.", 0.9);
        }

        @Override
        public ConsultationDiagnosticsResponse diagnose(ConsultationDiagnosticsRequest request) {
            return new ConsultationDiagnosticsResponse("ask", "Fecha?", null, "Falta.", "Sin fecha.");
        }

        /** Answers from the queue; an empty queue means needs_lawyer, an exception in it is thrown. */
        @Override
        public ClientMailIntentResponse classifyClientMail(ClientMailIntentRequest request) {
            intentCalls++;
            Object next = intents.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return new ClientMailIntentResponse(next == null ? ClientMailIntentResponse.NEEDS_LAWYER : (String) next, 0.9);
        }

        void outage() {
            for (int i = 0; i < 10; i++) {
                intents.add(new ClassifierUnavailableException("jev down"));
            }
        }
    }
}
