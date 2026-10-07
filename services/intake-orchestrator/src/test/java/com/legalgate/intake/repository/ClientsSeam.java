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
import com.legalgate.intake.clients.ActivityNudgeService;
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
 * Seam 1 wiring for the Clients context: the intake and Clients services on the real in-memory
 * repositories with a stubbed classifier client. Inbound mail enters through the inbound router
 * and workers are driven by hand. Lives in the repository package to reach InMemoryIntakeRepository.
 */
abstract class ClientsSeam {


    static final String TENANT = "firma-demo";
    static final String PROMPT = "Tomamos casos laborales.";

    final InMemoryIntakeRepository intake = new InMemoryIntakeRepository();
    final InMemoryClientsRepository clientsRepository = new InMemoryClientsRepository(intake);
    final StubClassifier classifier = new StubClassifier();
    final ClientsService clients = ClientsFixtures.clientsService(intake, clientsRepository);
    IntakeService intakeService;
    DiagnosticsService diagnostics;
    ClientMailService clientMail;
    ActivityNudgeService nudges;
    InboundMailRouter router;

    Client acme;

    void firm(String diagnosticsPrompt) {
        intake.startTenantProvisioning("owner", "Vargas & Asociados", TENANT, TENANT + "@intake.legal-gate.co");
        ClientsFixtures.saveSettings(intake, TENANT, diagnosticsPrompt);
        intakeService = ClientsFixtures.intakeService(intake, classifier, clientsRepository);
        diagnostics = new DiagnosticsService(intake, intakeService, classifier, ClientsFixtures.properties(),
                new EmailTemplateRenderer(), new FirmNameResolver(intake, ClientsFixtures.properties()));
        nudges = new ActivityNudgeService(clientsRepository, intake, intakeService, "https://app.legal-gate.co/dashboard");
        clientMail = new ClientMailService(clientsRepository, intake, intakeService, classifier,
                new EmailTemplateRenderer(), new FirmNameResolver(intake, ClientsFixtures.properties()), nudges);
        router = new InboundMailRouter(diagnostics, clientMail);
        acme = clients.create(TENANT, "user_admin", new ClientRequest("Acme SAS", ClientsModels.ORGANIZATION, null,
                null, null, ClientsFixtures.ANA, List.of("gc@acme.co", "ceo@acme.co")));
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
