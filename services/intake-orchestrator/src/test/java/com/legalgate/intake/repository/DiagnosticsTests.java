package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.classifier.ConsultationClassifierResponse;
import com.legalgate.intake.classifier.ConsultationDiagnosticsRequest;
import com.legalgate.intake.classifier.ConsultationDiagnosticsResponse;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.DiagnosticsMessage;
import com.legalgate.intake.model.DiagnosticsSession;
import com.legalgate.intake.model.DiagnosticsView;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.UrgencyDefinition;
import com.legalgate.intake.service.DiagnosticsService;
import com.legalgate.intake.service.EmailTemplateRenderer;
import com.legalgate.intake.service.FirmNameResolver;
import com.legalgate.intake.service.IntakeService;

/**
 * Seam 1: the intake service's consultation service, built on the real in-memory repository with
 * a stub classifier client. Everything behind this seam is production logic; the assertions are
 * on observable outcomes — which state a Consultation is in, whether an Event exists, and what
 * was queued for delivery.
 *
 * <p>Lives in the repository package to reach the package-private InMemoryIntakeRepository.
 */
class DiagnosticsTests {

    private static final String TENANT = "firma-demo";
    private static final String PROMPT = "Tomamos casos laborales. Necesitamos la fecha del despido.";

    private final InMemoryIntakeRepository repository = new InMemoryIntakeRepository();
    private final StubClassifier classifier = new StubClassifier();

    @Test
    void inboundEmailArrivesWithNoEventAndNoLawyerTimeReserved() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);

        ConsultationResponse consultation = diagnostics.receiveInboundEmail(inboundEmail("<m-1@example.com>"));

        assertThat(consultation.status()).isEqualTo("DIAGNOSTICS_PENDING");
        assertThat(consultation.eventId()).isNull();
        assertThat(consultation.event()).isNull();
        assertThat(repository.claimPendingNotifications(10)).isEmpty();
    }

    @Test
    void aBlankDiagnosticsPromptReproducesTheOldBehaviourExactly() {
        DiagnosticsService diagnostics = diagnosticsFor(null);
        classifier.classification = classification();

        ConsultationResponse consultation = diagnostics.receiveInboundEmail(inboundEmail("<m-2@example.com>"));

        assertThat(consultation.status()).isEqualTo("RECEIVED");
        assertThat(consultation.eventId()).isNotNull();
        assertThat(consultation.event().scheduledStart()).isNotNull();
        assertThat(repository.claimPendingNotifications(10)).isNotEmpty();
    }

    @Test
    void acceptOnTheFirstPassClassifiesAndSchedulesWithoutAskingAnything() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-3@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("accept", null, "Completo.", "Despido el 3 de marzo."));
        classifier.classification = classification();

        diagnostics.processDueDiagnostics();

        ConsultationResponse accepted = reload(pending);
        assertThat(accepted.status()).isEqualTo("RECEIVED");
        assertThat(accepted.eventId()).isNotNull();
        assertThat(accepted.summary()).contains("Despido el 3 de marzo.");
        assertThat(sessionFor(pending).status()).isEqualTo(DiagnosticsSession.ACCEPTED);
        assertThat(queuedTypes()).contains("CONSULTATION_SCHEDULED");
    }

    @Test
    void theAskPathQueuesAClientQuestionFromATokenBearingAddressAndCountsTheRound() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-4@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse(
                "ask", "Cual fue la fecha del despido?", "Falta la fecha.", "Despido sin fecha."));

        diagnostics.processDueDiagnostics();

        List<NotificationOutboxItem> queued = repository.claimPendingNotifications(10);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).type()).isEqualTo("DIAGNOSTICS_QUESTION");
        assertThat(queued.get(0).recipientEmail()).isEqualTo("maria@example.com");
        assertThat(queued.get(0).body()).contains("Cual fue la fecha del despido?");
        // Firm correspondence, not a bare prompt: salutation, framing, signature, plaintext only.
        assertThat(queued.get(0).body()).startsWith("Estimado(a) Maria:");
        assertThat(queued.get(0).body()).contains("Cordialmente,\nEquipo de consultas");
        assertThat(queued.get(0).body()).doesNotContain("LegalGate");
        assertThat(queued.get(0).subject()).isEqualTo("Re: Consulta laboral");
        assertThat(queued.get(0).htmlBody()).isNull();
        assertThat(queued.get(0).icsContent()).isNull();
        assertThat(queued.get(0).fromEmail())
                .startsWith("firma-demo+d")
                .endsWith("@intake.legal-gate.co");

        DiagnosticsSession session = sessionFor(pending);
        assertThat(session.rounds()).isEqualTo(1);
        assertThat(session.status()).isEqualTo(DiagnosticsSession.PENDING);
        assertThat(reload(pending).eventId()).isNull();
    }

    @Test
    void theRejectPathQueuesANonEngagementNoticeAndRecordsTheVerdictReasonAndPromptSnapshot() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-5@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse(
                "reject", null, "La firma no toma casos penales.", "Asunto penal."));

        diagnostics.processDueDiagnostics();

        assertThat(reload(pending).status()).isEqualTo("DIAGNOSTICS_REJECTED");
        assertThat(reload(pending).eventId()).isNull();
        DiagnosticsSession session = sessionFor(pending);
        assertThat(session.status()).isEqualTo(DiagnosticsSession.REJECTED);
        assertThat(session.verdict()).isEqualTo("reject");
        assertThat(session.reason()).isEqualTo("La firma no toma casos penales.");
        assertThat(session.promptSnapshot()).isEqualTo(PROMPT);

        List<NotificationOutboxItem> queued = repository.claimPendingNotifications(10);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).type()).isEqualTo("NON_ENGAGEMENT_NOTICE");
        assertThat(queued.get(0).body()).contains("No se ha formado ninguna relacion abogado-cliente");
        // Same Firm Voice envelope and same thread as every other client message.
        assertThat(queued.get(0).body()).startsWith("Estimado(a) Maria:");
        assertThat(queued.get(0).body()).contains("Cordialmente,\nEquipo de consultas");
        assertThat(queued.get(0).body()).doesNotContain("LegalGate");
        assertThat(queued.get(0).subject()).isEqualTo("Re: Consulta laboral");
        assertThat(queued.get(0).htmlBody()).isNull();
    }

    @Test
    void aFirmAuthoredNoticeGoesOutVerbatimInsideTheEnvelope() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        String notice = "Apreciado(a) consultante:\n\nNo tomamos este asunto.\n\nAtentamente,\nLa firma";
        saveNonEngagementNotice(notice);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-verbatim@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("reject", null, "Fuera de alcance.", "Resumen."));
        diagnostics.processDueDiagnostics();

        List<NotificationOutboxItem> queued = repository.claimPendingNotifications(10);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).body()).contains(notice);
    }

    @Test
    void theRecordedPromptSnapshotSurvivesTheFirmRewritingItsPrompt() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-6@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("reject", null, "Fuera de alcance.", "Resumen."));
        diagnostics.processDueDiagnostics();

        saveSettings("Ahora tomamos de todo.");

        assertThat(sessionFor(pending).promptSnapshot()).isEqualTo(PROMPT);
    }

    @Test
    void aClientReplyAttachesToTheSameConsultationAndTriggersTheNextRound() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-7@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta la fecha.", "Sin fecha."));
        diagnostics.processDueDiagnostics();

        ConsultationResponse afterReply = diagnostics.receiveInboundEmail(
                reply(pending, "El 3 de marzo.", "<m-7-reply@example.com>"));

        assertThat(afterReply.id()).isEqualTo(pending.id());
        assertThat(repository.consultationsForTenant(TENANT).consultations()).hasSize(1);
        assertThat(transcript(pending))
                .extracting(DiagnosticsMessage::role, DiagnosticsMessage::body)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LEGALGATE", "Fecha?"),
                        org.assertj.core.groups.Tuple.tuple("CLIENT", "El 3 de marzo."));

        classifier.verdicts.add(new ConsultationDiagnosticsResponse("accept", null, "Completo.", "Despido el 3 de marzo."));
        classifier.classification = classification();
        diagnostics.processDueDiagnostics();

        assertThat(reload(pending).status()).isEqualTo("RECEIVED");
    }

    @Test
    void severalRepliesInOneRoundDoNotTriggerAnExtraDiagnoseCall() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-8@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta.", "Sin fecha."));
        diagnostics.processDueDiagnostics();
        int callsAfterFirstRound = classifier.diagnoseCalls;

        diagnostics.receiveInboundEmail(reply(pending, "El 3 de marzo.", "<m-8-r1@example.com>"));
        diagnostics.receiveInboundEmail(reply(pending, "Y era termino fijo.", "<m-8-r2@example.com>"));

        assertThat(classifier.diagnoseCalls).isEqualTo(callsAfterFirstRound);
        assertThat(transcript(pending)).hasSize(3);
    }

    @Test
    void theRoundCapParksAMatterThatNeverSuppliesWhatTheFirmNeeds() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-9@example.com>"));

        for (int round = 1; round <= 3; round++) {
            classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta.", "Sin fecha."));
            diagnostics.processDueDiagnostics();
            diagnostics.receiveInboundEmail(reply(pending, "No recuerdo.", "<m-9-r" + round + "@example.com>"));
        }
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta.", "Sin fecha."));
        diagnostics.processDueDiagnostics();

        assertThat(sessionFor(pending).rounds()).isEqualTo(3);
        assertThat(sessionFor(pending).status()).isEqualTo(DiagnosticsSession.ABANDONED);
        assertThat(reload(pending).status()).isEqualTo("DIAGNOSTICS_ABANDONED");
        assertThat(reload(pending).eventId()).isNull();
    }

    @Test
    void sevenDaysOfClientSilenceParksTheMatterWithoutDeletingIt() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-10@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta.", "Sin fecha."));
        diagnostics.processDueDiagnostics();
        backdateSilence(pending, Instant.now().minusSeconds(8 * 24 * 3600));

        diagnostics.expireSilentDiagnostics();

        assertThat(reload(pending).status()).isEqualTo("DIAGNOSTICS_ABANDONED");
        assertThat(repository.consultationsForTenant(TENANT).consultations()).hasSize(1);
    }

    @Test
    void aReplyToATerminalConsultationIsRecordedAndFlaggedButNeverReopensDiagnostics() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-11@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("reject", null, "Fuera de alcance.", "Resumen."));
        diagnostics.processDueDiagnostics();
        repository.claimPendingNotifications(10);
        int diagnoseCalls = classifier.diagnoseCalls;

        diagnostics.receiveInboundEmail(reply(pending, "Pero tengo mas datos.", "<m-11-r@example.com>"));
        diagnostics.processDueDiagnostics();

        assertThat(classifier.diagnoseCalls).isEqualTo(diagnoseCalls);
        assertThat(sessionFor(pending).status()).isEqualTo(DiagnosticsSession.REJECTED);
        assertThat(reload(pending).status()).isEqualTo("DIAGNOSTICS_REJECTED");
        DiagnosticsView view = diagnostics.viewFor(TENANT, pending.id()).orElseThrow();
        assertThat(view.lateReply()).isTrue();
        assertThat(view.transcript()).extracting(DiagnosticsMessage::body).contains("Pero tengo mas datos.");
        assertThat(repository.claimPendingNotifications(10)).isEmpty();
    }

    @Test
    void automatedResponderMailIsNeverAnswered() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);

        ConsultationResponse parked = diagnostics.receiveInboundEmail(autoResponder("<m-12@example.com>"));
        diagnostics.processDueDiagnostics();

        assertThat(parked.status()).isEqualTo("DIAGNOSTICS_ABANDONED");
        assertThat(classifier.diagnoseCalls).isZero();
        assertThat(repository.claimPendingNotifications(10)).isEmpty();
    }

    @Test
    void anAutomatedReplyIsRecordedWithoutBeingAnswered() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-13@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta.", "Sin fecha."));
        diagnostics.processDueDiagnostics();
        repository.claimPendingNotifications(10);
        int diagnoseCalls = classifier.diagnoseCalls;

        InboundEmailReceived vacation = reply(pending, "Estoy de vacaciones.", "<m-13-ooo@example.com>");
        diagnostics.receiveInboundEmail(new InboundEmailReceived(
                vacation.eventId(), vacation.tenantId(), vacation.envelopeTo(), vacation.recipients(),
                vacation.envelopeFrom(), vacation.headerFrom(), vacation.subject(), vacation.messageId(),
                vacation.plain(), vacation.html(), vacation.receivedAt(), true));
        diagnostics.processDueDiagnostics();

        assertThat(classifier.diagnoseCalls).isEqualTo(diagnoseCalls);
        assertThat(repository.claimPendingNotifications(10)).isEmpty();
    }

    @Test
    void theSlaClockAnchorsAtAcceptanceRatherThanArrival() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-14@example.com>"));
        Instant arrival = pending.createdAt();
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("accept", null, "Completo.", "Resumen."));
        classifier.classification = classification();

        diagnostics.processDueDiagnostics();

        // NORMAL is a 5-business-day SLA; anchored at arrival the deadline would be earlier than
        // one anchored now, and the clock must not have been running while the client was asked.
        Instant deadline = reload(pending).event().slaDeadline();
        assertThat(deadline).isAfter(arrival.plusSeconds(4 * 24 * 3600));
    }

    @Test
    void anExhaustedRetryBudgetFailsOpenAndTheMatterProceeds() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-15@example.com>"));
        classifier.diagnoseFailure = new ClassifierUnavailableException("gemini down");
        classifier.classification = classification();

        // Six backoff steps, then the seventh pass gives up and proceeds as if accepted.
        for (int attempt = 0; attempt <= 6; attempt++) {
            makeDue(pending);
            diagnostics.processDueDiagnostics();
        }

        assertThat(reload(pending).status()).isEqualTo("RECEIVED");
        assertThat(reload(pending).eventId()).isNotNull();
        assertThat(sessionFor(pending).status()).isEqualTo(DiagnosticsSession.ACCEPTED);
    }

    @Test
    void aResponseThatFailsValidationIsRetriedLikeAServiceFailure() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-16@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", null, "Falta.", "Resumen."));

        diagnostics.processDueDiagnostics();

        assertThat(sessionFor(pending).attempts()).isEqualTo(1);
        assertThat(sessionFor(pending).status()).isEqualTo(DiagnosticsSession.PENDING);
        assertThat(reload(pending).status()).isEqualTo("DIAGNOSTICS_PENDING");
        assertThat(repository.claimPendingNotifications(10)).isEmpty();
    }

    @Test
    void classificationFailureAfterAcceptanceIsRetriedRatherThanStampedUnclassifiable() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-17@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("accept", null, "Completo.", "Resumen."));
        classifier.classifyFailure = new ClassifierUnavailableException("gemini down");

        diagnostics.processDueDiagnostics();

        assertThat(reload(pending).status()).isEqualTo("DIAGNOSTICS_PENDING");
        assertThat(sessionFor(pending).attempts()).isEqualTo(1);

        classifier.classifyFailure = null;
        classifier.classification = classification();
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("accept", null, "Completo.", "Resumen."));
        makeDue(pending);
        diagnostics.processDueDiagnostics();

        assertThat(reload(pending).status()).isEqualTo("RECEIVED");
        assertThat(reload(pending).classification().label()).isEqualTo("LLM_CLASSIFIED");
    }

    @Test
    void theConsoleOverrideEndsDiagnosticsEarlyAndSchedulesTheMatter() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-18@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta.", "Sin fecha."));
        diagnostics.processDueDiagnostics();
        classifier.classification = classification();

        ConsultationResponse accepted = diagnostics.acceptNow(TENANT, pending.id());

        assertThat(accepted.status()).isEqualTo("RECEIVED");
        assertThat(accepted.eventId()).isNotNull();
        assertThat(sessionFor(pending).status()).isEqualTo(DiagnosticsSession.ACCEPTED);
        assertThatThrownBy(() -> diagnostics.acceptNow(TENANT, pending.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("diagnostics_already_resolved");
    }

    @Test
    void transcriptsOfTerminalMattersArePurgedWhileTheVerdictIsKept() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-19@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("ask", "Fecha?", "Falta.", "Sin fecha."));
        diagnostics.processDueDiagnostics();
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("reject", null, "Fuera de alcance.", "Resumen."));
        diagnostics.receiveInboundEmail(reply(pending, "No se.", "<m-19-r@example.com>"));
        diagnostics.processDueDiagnostics();
        backdateResolution(pending, Instant.now().minusSeconds(200L * 24 * 3600));

        diagnostics.purgeExpiredTranscripts();

        assertThat(transcript(pending)).isEmpty();
        DiagnosticsSession session = sessionFor(pending);
        assertThat(session.verdict()).isEqualTo("reject");
        assertThat(session.reason()).isEqualTo("Fuera de alcance.");
        assertThat(session.promptSnapshot()).isEqualTo(PROMPT);
    }

    @Test
    void anInterruptedAcceptanceResumesWithoutReservingASecondSlot() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);
        ConsultationResponse pending = diagnostics.receiveInboundEmail(inboundEmail("<m-20@example.com>"));
        classifier.verdicts.add(new ConsultationDiagnosticsResponse("accept", null, "Completo.", "Resumen."));
        classifier.classification = classification();
        diagnostics.processDueDiagnostics();
        String eventId = reload(pending).eventId();
        repository.claimPendingNotifications(20);

        // The session is dragged back to PENDING as a crash between scheduling and resolving would
        // leave it; the retry must finish the acceptance, not schedule a second event.
        DiagnosticsSession interrupted = sessionFor(pending);
        repository.saveDiagnosticsSession(TENANT,
                interrupted.accepting(interrupted.reason(), interrupted.extractedSummary())
                        .dueNow(Instant.now().minusSeconds(1)),
                List.of(), List.of());
        int diagnoseCalls = classifier.diagnoseCalls;
        diagnostics.processDueDiagnostics();

        assertThat(classifier.diagnoseCalls).isEqualTo(diagnoseCalls);
        assertThat(reload(pending).eventId()).isEqualTo(eventId);
        assertThat(sessionFor(pending).status()).isEqualTo(DiagnosticsSession.ACCEPTED);
        assertThat(queuedTypes()).isEmpty();
    }

    @Test
    void anAutomatedResponderIsRecordedAsASessionTheFirmCanRead() {
        DiagnosticsService diagnostics = diagnosticsFor(PROMPT);

        ConsultationResponse parked = diagnostics.receiveInboundEmail(autoResponder("<m-21@example.com>"));

        DiagnosticsView view = diagnostics.viewFor(TENANT, parked.id()).orElseThrow();
        assertThat(view.status()).isEqualTo(DiagnosticsSession.ABANDONED);
        assertThat(view.reason()).contains("Correo automatico");
    }

    private DiagnosticsService diagnosticsFor(String prompt) {
        IntakeService intakeService = new IntakeService(repository, properties(), classifier, new EmailTemplateRenderer(),
                new FirmNameResolver(repository, properties()));
        intakeService.saveSettings(TENANT, settingsRequest(prompt));
        return new DiagnosticsService(repository, intakeService, classifier, properties(),
                new EmailTemplateRenderer(), new FirmNameResolver(repository, properties()));
    }

    private void saveSettings(String prompt) {
        new IntakeService(repository, properties(), classifier, new EmailTemplateRenderer(),
                new FirmNameResolver(repository, properties()))
                .saveSettings(TENANT, settingsRequest(prompt));
    }

    private void saveNonEngagementNotice(String notice) {
        new IntakeService(repository, properties(), classifier, new EmailTemplateRenderer(),
                new FirmNameResolver(repository, properties()))
                .saveSettings(TENANT, settingsRequest(PROMPT, notice));
    }

    private TenantSettingsRequest settingsRequest(String prompt) {
        return settingsRequest(prompt, null);
    }

    private TenantSettingsRequest settingsRequest(String prompt, String nonEngagementNotice) {
        return new TenantSettingsRequest(
                List.of(new TenantRoutingRule(
                        "Laboral", "Despidos y contratos", List.of(), List.of("manana"),
                        List.of("NORMAL", "URGENTE"), null,
                        List.of(new UrgencyDefinition("NORMAL", 1, 5, true), new UrgencyDefinition("URGENTE", 2, 1, true)),
                        "ana@firm.co")),
                List.of(new LawyerProfile(null, "Ana Abogada", "ana@firm.co", true, 60, null)),
                prompt,
                nonEngagementNotice);
    }

    private InboundEmailReceived inboundEmail(String messageId) {
        return new InboundEmailReceived("event-" + messageId, TENANT, "firma-demo@intake.legal-gate.co",
                List.of("firma-demo@intake.legal-gate.co"), "maria@example.com", "Maria Perez <maria@example.com>",
                "Consulta laboral", messageId, "Me despidieron.", null, Instant.now());
    }

    private InboundEmailReceived autoResponder(String messageId) {
        InboundEmailReceived email = inboundEmail(messageId);
        return new InboundEmailReceived(email.eventId(), email.tenantId(), email.envelopeTo(), email.recipients(),
                email.envelopeFrom(), email.headerFrom(), email.subject(), email.messageId(), email.plain(),
                email.html(), email.receivedAt(), true);
    }

    private InboundEmailReceived reply(ConsultationResponse consultation, String body, String messageId) {
        String replyAddress = "firma-demo+d" + sessionFor(consultation).replyToken() + "@intake.legal-gate.co";
        return new InboundEmailReceived("event-" + messageId, TENANT, replyAddress, List.of(replyAddress),
                "maria@example.com", "Maria Perez <maria@example.com>", "Re: Consulta laboral", messageId,
                body, null, Instant.now());
    }

    private ConsultationResponse reload(ConsultationResponse consultation) {
        return repository.consultationById(TENANT, consultation.id()).orElseThrow();
    }

    private DiagnosticsSession sessionFor(ConsultationResponse consultation) {
        return repository.diagnosticsSessionForConsultation(TENANT, consultation.id()).orElseThrow();
    }

    private List<DiagnosticsMessage> transcript(ConsultationResponse consultation) {
        return repository.diagnosticsMessages(TENANT, sessionFor(consultation).id());
    }

    private List<String> queuedTypes() {
        return repository.claimPendingNotifications(20).stream().map(NotificationOutboxItem::type).toList();
    }

    private void makeDue(ConsultationResponse consultation) {
        DiagnosticsSession session = sessionFor(consultation);
        repository.saveDiagnosticsSession(TENANT, session.dueNow(Instant.now().minusSeconds(1)), List.of(), List.of());
    }

    private void backdateSilence(ConsultationResponse consultation, Instant since) {
        repository.saveDiagnosticsSession(
                TENANT, sessionFor(consultation).withAwaitingReplySince(since), List.of(), List.of());
    }

    private void backdateResolution(ConsultationResponse consultation, Instant resolvedAt) {
        repository.saveDiagnosticsSession(
                TENANT, sessionFor(consultation).withResolvedAt(resolvedAt), List.of(), List.of());
    }

    private ConsultationClassifierResponse classification() {
        return new ConsultationClassifierResponse(0, "Laboral", "NORMAL", "Despido", "Resumen", "Maria", "Ruta laboral.", 0.9);
    }

    private IntakeProperties properties() {
        return new IntakeProperties(
                "memory", false, "intake.legal-gate.co", null, null, null, null, null, null,
                false, null, null, null, null, false, "test-token", "test-key", null);
    }

    private static final class StubClassifier implements ConsultationClassifierClient {
        private final Deque<ConsultationDiagnosticsResponse> verdicts = new ArrayDeque<>();
        private ConsultationClassifierResponse classification;
        private RuntimeException classifyFailure;
        private RuntimeException diagnoseFailure;
        private int diagnoseCalls;

        @Override
        public ConsultationClassifierResponse classify(ConsultationClassifierRequest request) {
            if (classifyFailure != null) {
                throw classifyFailure;
            }
            return classification;
        }

        @Override
        public ConsultationDiagnosticsResponse diagnose(ConsultationDiagnosticsRequest request) {
            diagnoseCalls++;
            if (diagnoseFailure != null) {
                throw diagnoseFailure;
            }
            return Optional.ofNullable(verdicts.poll())
                    .orElseThrow(() -> new IllegalStateException("no stubbed verdict left"));
        }
    }
}
