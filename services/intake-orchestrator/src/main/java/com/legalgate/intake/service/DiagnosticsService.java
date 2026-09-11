package com.legalgate.intake.service;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.classifier.ConsultationDiagnosticsRequest;
import com.legalgate.intake.classifier.ConsultationDiagnosticsResponse;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.DiagnosticsMessage;
import com.legalgate.intake.model.DiagnosticsSession;
import com.legalgate.intake.model.DiagnosticsView;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.repository.IntakeRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Diagnostics: the exchange that decides whether a Consultation is worth scheduling, before any
 * lawyer time is committed.
 *
 * <p>The inbound-email webhook only persists; every LLM call happens on the scheduled methods
 * here, which are public so tests can drive them directly instead of waiting on a schedule.
 */
@Service
public class DiagnosticsService {

    public static final String CONSULTATION_STATUS_PENDING = "DIAGNOSTICS_PENDING";
    public static final String CONSULTATION_STATUS_REJECTED = "DIAGNOSTICS_REJECTED";
    public static final String CONSULTATION_STATUS_ABANDONED = "DIAGNOSTICS_ABANDONED";

    static final int MAX_ROUNDS = 3;
    static final int MAX_ACKNOWLEDGMENT_WORDS = 15;
    static final String REPLY_TAG_PREFIX = "d";

    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticsService.class);
    private static final int BATCH_SIZE = 20;
    private static final Duration SILENCE_TIMEOUT = Duration.ofDays(7);
    private static final Duration TRANSCRIPT_RETENTION = Duration.ofDays(180);

    // 5m, 10m, 15m: half an hour of retrying before a matter is handed to a human unfiltered.
    // A potential client waiting on a first reply is the thing being spent here, not compute.
    private static final List<Duration> RETRY_BACKOFF = List.of(
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15));

    /** Why a Consultation reached the firm without a Verdict; see ADR 0005. */
    static final String UNFILTERED_DIAGNOSTICS_UNAVAILABLE = "DIAGNOSTICS_UNAVAILABLE";
    static final String UNFILTERED_DIAGNOSTICS_INVALID_RESPONSE = "DIAGNOSTICS_INVALID_RESPONSE";
    static final String UNFILTERED_CLASSIFICATION_UNAVAILABLE = "CLASSIFICATION_UNAVAILABLE";
    static final String UNFILTERED_DIAGNOSTICS_ERROR = "DIAGNOSTICS_ERROR";

    private static final String AUTO_RESPONDER_REASON =
            "Correo automatico (fuera de oficina o lista); Diagnostics nunca le responde.";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REPLY_TOKEN_BYTES = 16;

    // The body only: the template layer wraps it in the salutation, signature and the
    // not-legal-advice disclaimer, so neither is repeated here.
    // One line per paragraph: a hard-wrapped paragraph rewraps badly on a phone.
    private static final String DEFAULT_NON_ENGAGEMENT_NOTICE =
            "Gracias por escribirnos.\n"
            + "\n"
            + "Despues de revisar su mensaje, la firma no puede asumir este asunto.\n"
            + "\n"
            + "No se ha formado ninguna relacion abogado-cliente. Le recomendamos buscar otro abogado"
            + " lo antes posible, ya que su asunto puede estar sujeto a terminos o plazos legales.\n";

    private final IntakeRepository intakeRepository;
    private final IntakeService intakeService;
    private final ConsultationClassifierClient consultationClassifierClient;
    private final IntakeProperties intakeProperties;
    private final EmailTemplateRenderer emailTemplateRenderer;
    private final FirmNameResolver firmNameResolver;

    public DiagnosticsService(
            IntakeRepository intakeRepository,
            IntakeService intakeService,
            ConsultationClassifierClient consultationClassifierClient,
            IntakeProperties intakeProperties,
            EmailTemplateRenderer emailTemplateRenderer,
            FirmNameResolver firmNameResolver
    ) {
        this.intakeRepository = intakeRepository;
        this.intakeService = intakeService;
        this.consultationClassifierClient = consultationClassifierClient;
        this.intakeProperties = intakeProperties;
        this.emailTemplateRenderer = emailTemplateRenderer;
        this.firmNameResolver = firmNameResolver;
    }

    /** Whether this email is a potential client answering a Diagnostics question we already sent. */
    public boolean isDiagnosticsReply(InboundEmailReceived event) {
        return sessionForReply(event).isPresent();
    }

    /**
     * Entry point for the inbound-email webhook. Persists and returns; no LLM work happens here.
     * A tenant with a blank Diagnostics Prompt keeps the pre-diagnostics behaviour exactly.
     */
    public ConsultationResponse receiveInboundEmail(InboundEmailReceived event) {
        Optional<DiagnosticsSession> replySession = sessionForReply(event);
        if (replySession.isPresent()) {
            return recordReply(replySession.get(), event);
        }

        TenantSettingsResponse settings = intakeService.settingsForTenant(event.tenantId());
        String prompt = settings.diagnosticsPrompt();
        if (prompt == null || prompt.isBlank()) {
            return intakeService.createConsultationFromInboundEmail(event);
        }

        Optional<ConsultationResponse> existing = intakeRepository
                .consultationForSourceMessageId(event.tenantId(), event.messageId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = Instant.now();
        if (event.autoResponder()) {
            // Never answered, so never started. The session is still recorded so the firm can see
            // in the console why this one was parked rather than qualified.
            ConsultationResponse parked = intakeService.savePendingDiagnosticsConsultation(
                    event, CONSULTATION_STATUS_ABANDONED, AUTO_RESPONDER_REASON);
            intakeRepository.saveDiagnosticsSession(event.tenantId(),
                    newSession(event, parked, prompt, now)
                            .resolved(DiagnosticsSession.ABANDONED, null, AUTO_RESPONDER_REASON, null, now),
                    List.of(), List.of());
            return parked;
        }

        ConsultationResponse pending = intakeService.savePendingDiagnosticsConsultation(
                event, CONSULTATION_STATUS_PENDING,
                "Diagnostics is qualifying this matter; no lawyer time is reserved yet.");
        intakeRepository.saveDiagnosticsSession(
                event.tenantId(), newSession(event, pending, prompt, now), List.of(), List.of());
        return pending;
    }

    private DiagnosticsSession newSession(
            InboundEmailReceived event, ConsultationResponse consultation, String prompt, Instant now) {
        return new DiagnosticsSession(null, event.tenantId(), consultation.id(), newReplyToken(),
                DiagnosticsSession.PENDING, null, null, null, prompt.trim(), inboundEmailFor(event),
                0, 0, now, null, null, null, now, null);
    }

    private Optional<DiagnosticsSession> sessionForReply(InboundEmailReceived event) {
        return replyTokenFrom(event.recipients()).flatMap(intakeRepository::diagnosticsSessionForReplyToken);
    }

    @Scheduled(fixedDelayString = "${LEGALGATE_DIAGNOSTICS_DELAY_MS:30000}",
            initialDelayString = "${LEGALGATE_DIAGNOSTICS_INITIAL_DELAY_MS:15000}")
    public void processDueDiagnostics() {
        for (DiagnosticsSession session : intakeRepository.claimDueDiagnosticsSessions(BATCH_SIZE)) {
            try {
                process(session);
            } catch (RuntimeException ex) {
                LOGGER.warn("Diagnostics session id={} tenant={} failed", session.id(), session.tenantId(), ex);
                retryOrFailOpen(session, ex.getMessage(), UNFILTERED_DIAGNOSTICS_ERROR);
            }
        }
    }

    @Scheduled(fixedDelayString = "${LEGALGATE_DIAGNOSTICS_SILENCE_SWEEP_MS:3600000}",
            initialDelayString = "${LEGALGATE_DIAGNOSTICS_SILENCE_INITIAL_DELAY_MS:60000}")
    public void expireSilentDiagnostics() {
        Instant threshold = Instant.now().minus(SILENCE_TIMEOUT);
        for (DiagnosticsSession session : intakeRepository.silentDiagnosticsSessions(threshold, BATCH_SIZE)) {
            abandon(session, "El cliente potencial dejo de responder.");
        }
    }

    /** Transcripts of declined and abandoned matters are purged; verdicts and reasons are kept. */
    @Scheduled(fixedDelayString = "${LEGALGATE_DIAGNOSTICS_PURGE_SWEEP_MS:86400000}",
            initialDelayString = "${LEGALGATE_DIAGNOSTICS_PURGE_INITIAL_DELAY_MS:120000}")
    public void purgeExpiredTranscripts() {
        int purged = intakeRepository.purgeDiagnosticsTranscripts(Instant.now().minus(TRANSCRIPT_RETENTION));
        if (purged > 0) {
            LOGGER.info("Purged {} diagnostics transcripts past retention.", purged);
        }
    }

    /** The console escape hatch: end Diagnostics early and schedule the matter now. */
    public ConsultationResponse acceptNow(String tenantId, String consultationId) {
        DiagnosticsSession session = intakeRepository.diagnosticsSessionForConsultation(tenantId, consultationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "diagnostics_session_not_found"));
        if (!session.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "diagnostics_already_resolved");
        }
        accept(session, requireConsultation(session), session.extractedSummary(),
                "Aceptada en la consola antes de terminar el diagnostico.", true);
        return requireConsultation(session);
    }

    public Optional<DiagnosticsView> viewFor(String tenantId, String consultationId) {
        return intakeRepository.diagnosticsSessionForConsultation(tenantId, consultationId).map(session -> {
            List<DiagnosticsMessage> transcript = intakeRepository.diagnosticsMessages(tenantId, session.id());
            boolean lateReply = session.resolvedAt() != null && transcript.stream()
                    .anyMatch(message -> DiagnosticsMessage.CLIENT.equals(message.role())
                            && message.createdAt() != null && message.createdAt().isAfter(session.resolvedAt()));
            return new DiagnosticsView(consultationId, session.status(), session.verdict(), session.reason(),
                    session.extractedSummary(), session.promptSnapshot(), session.rounds(),
                    session.awaitingReplySince(), session.resolvedAt(), lateReply,
                    session.unfilteredCause(), transcript);
        });
    }

    private void process(DiagnosticsSession session) {
        ConsultationResponse consultation = requireConsultation(session);
        if (ConsultationDiagnosticsResponse.ACCEPT.equals(session.verdict())) {
            // The verdict is already in. A retry here is finishing an interrupted acceptance, not
            // asking the model again: re-running diagnose would let the verdict flip after the
            // firm had committed to the matter.
            accept(session, consultation, session.extractedSummary(), session.reason(), false);
            return;
        }
        List<DiagnosticsMessage> transcript = intakeRepository.diagnosticsMessages(session.tenantId(), session.id());
        ConsultationDiagnosticsResponse verdict;
        try {
            verdict = consultationClassifierClient.diagnose(diagnoseRequestFor(session, transcript));
        } catch (ClassifierUnavailableException ex) {
            retryOrFailOpen(session, ex.getMessage(), UNFILTERED_DIAGNOSTICS_UNAVAILABLE);
            return;
        }
        if (!isUsable(verdict)) {
            // A response that fails validation is retried like a service failure, but it is not
            // one: Diagnostics answered. The two are recorded apart so the console can say which.
            retryOrFailOpen(session, "diagnostics_invalid_response", UNFILTERED_DIAGNOSTICS_INVALID_RESPONSE);
            return;
        }
        switch (verdict.verdict()) {
            case ConsultationDiagnosticsResponse.ACCEPT -> accept(session, consultation, verdict.summary(), verdict.reason(), false);
            case ConsultationDiagnosticsResponse.REJECT -> reject(session, consultation, verdict);
            default -> ask(session, consultation, verdict);
        }
    }

    private void ask(DiagnosticsSession session, ConsultationResponse consultation, ConsultationDiagnosticsResponse verdict) {
        if (session.rounds() >= MAX_ROUNDS) {
            abandon(session, "Se alcanzo el maximo de rondas de diagnostico sin obtener la informacion necesaria.");
            return;
        }
        Instant now = Instant.now();
        intakeRepository.saveDiagnosticsSession(
                session.tenantId(),
                session.awaitingReply(now, verdict.reason(), verdict.summary()),
                List.of(DiagnosticsMessage.fromLegalGate(verdict.question())),
                List.of(questionNotification(consultation, session, verdict)));
    }

    private void accept(DiagnosticsSession session, ConsultationResponse consultation,
            String summary, String reason, boolean allowFallback) {
        // Scheduling and resolving are two writes, so the verdict is recorded on the session
        // first. If the process dies between them, the retry resumes at acceptance instead of
        // re-asking the model, and the event guard below stops it reserving a second slot.
        DiagnosticsSession accepting = session.accepting(reason, summary);
        if (consultation.eventId() == null) {
            intakeRepository.saveDiagnosticsSession(session.tenantId(), accepting, List.of(), List.of());
            try {
                intakeService.scheduleAcceptedConsultation(
                        session.tenantId(), consultation, session.originalEmail(), summary, allowFallback);
            } catch (ClassifierUnavailableException ex) {
                // Classification inherits the same retry budget now that it is off the webhook path.
                // The Verdict was reached here — only the routing failed — so the cause says so.
                retryOrFailOpen(accepting, ex.getMessage(), UNFILTERED_CLASSIFICATION_UNAVAILABLE);
                return;
            }
        }
        intakeRepository.saveDiagnosticsSession(session.tenantId(),
                accepting.resolved(DiagnosticsSession.ACCEPTED, ConsultationDiagnosticsResponse.ACCEPT,
                        reason, summary, Instant.now()),
                List.of(), List.of());
    }

    private void reject(DiagnosticsSession session, ConsultationResponse consultation, ConsultationDiagnosticsResponse verdict) {
        intakeRepository.updateConsultation(session.tenantId(), consultation.withStatus(CONSULTATION_STATUS_REJECTED), List.of(), List.of());
        intakeRepository.saveDiagnosticsSession(session.tenantId(),
                session.resolved(DiagnosticsSession.REJECTED, ConsultationDiagnosticsResponse.REJECT,
                        verdict.reason(), verdict.summary(), Instant.now()),
                List.of(),
                List.of(nonEngagementNotification(consultation, session)));
    }

    private void abandon(DiagnosticsSession session, String reason) {
        ConsultationResponse consultation = intakeRepository
                .consultationById(session.tenantId(), session.consultationId()).orElse(null);
        if (consultation != null) {
            intakeRepository.updateConsultation(session.tenantId(), consultation.withStatus(CONSULTATION_STATUS_ABANDONED), List.of(), List.of());
        }
        intakeRepository.saveDiagnosticsSession(session.tenantId(),
                session.resolved(DiagnosticsSession.ABANDONED, session.verdict(), reason, session.extractedSummary(), Instant.now()),
                List.of(), List.of());
    }

    private void retryOrFailOpen(DiagnosticsSession session, String error, String unfilteredCause) {
        if (session.attempts() < RETRY_BACKOFF.size()) {
            Instant next = Instant.now().plus(RETRY_BACKOFF.get(session.attempts()));
            intakeRepository.saveDiagnosticsSession(session.tenantId(), session.retrying(next, error), List.of(), List.of());
            return;
        }
        // Exhausted. Failure is open, not closed: an outage must not silently swallow real
        // clients, so the matter proceeds as if accepted and lands on a human's desk.
        LOGGER.error("Diagnostics exhausted its retry budget for consultation={} tenant={}; proceeding as accepted.",
                session.consultationId(), session.tenantId());
        ConsultationResponse consultation = intakeRepository
                .consultationById(session.tenantId(), session.consultationId()).orElse(null);
        if (consultation == null) {
            return;
        }
        // The matter proceeds without a Verdict. That is recorded as its own fact rather than
        // written into `reason`, which the console shows as the model's own words. ADR 0005.
        accept(session.unfiltered(unfilteredCause), consultation, session.extractedSummary(),
                session.reason(), true);
    }

    private ConsultationResponse recordReply(DiagnosticsSession session, InboundEmailReceived event) {
        String tenantId = session.tenantId();
        ConsultationResponse consultation = requireConsultation(session);
        // ponytail: a provider redelivery appends a duplicate transcript line rather than being
        // deduped; harmless, and the webhook now returns fast enough that redelivery is rare.
        List<DiagnosticsMessage> reply = List.of(DiagnosticsMessage.fromClient(replyBodyFor(event)));

        if (!session.isPending() || event.autoResponder()) {
            // Recorded and surfaced to the firm, but Diagnostics never reopens on a reply: a
            // persistent sender must not be able to re-trigger the model indefinitely, and an
            // automated responder must never be answered.
            intakeRepository.saveDiagnosticsSession(tenantId, session, reply, List.of());
            return consultation;
        }
        // Several replies inside one round are appended without a second diagnose call.
        DiagnosticsSession updated = session.awaitingReplySince() == null ? session : session.dueNow(Instant.now());
        intakeRepository.saveDiagnosticsSession(tenantId, updated, reply, List.of());
        return consultation;
    }

    private ConsultationResponse requireConsultation(DiagnosticsSession session) {
        return intakeRepository.consultationById(session.tenantId(), session.consultationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "consultation_not_found"));
    }

    private boolean isUsable(ConsultationDiagnosticsResponse verdict) {
        if (verdict == null || verdict.verdict() == null) {
            return false;
        }
        return switch (verdict.verdict()) {
            case ConsultationDiagnosticsResponse.ACCEPT, ConsultationDiagnosticsResponse.REJECT -> true;
            case ConsultationDiagnosticsResponse.ASK -> verdict.question() != null && !verdict.question().isBlank();
            default -> false;
        };
    }

    /**
     * The Acknowledgment is the one generated sentence in a first contact with a stranger, so it is
     * held to a hard length: a restatement that grows past it has stopped repeating the potential
     * client and started characterizing their matter. Anything blank or over the cap is dropped in
     * favour of the neutral receipt — a degraded email is fine, a failed round is not, so unlike
     * {@link #isUsable} this never rejects the response.
     */
    private String usableAcknowledgment(String acknowledgment) {
        if (acknowledgment == null || acknowledgment.isBlank()) {
            return null;
        }
        String sanitized = EmailTemplateRenderer.restatement(
                acknowledgment.replaceAll("[\\p{Cntrl}\\s]+", " "));
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized.split("\\s+").length > MAX_ACKNOWLEDGMENT_WORDS ? null : sanitized;
    }

    private ConsultationDiagnosticsRequest diagnoseRequestFor(DiagnosticsSession session, List<DiagnosticsMessage> transcript) {
        return new ConsultationDiagnosticsRequest(
                session.promptSnapshot(),
                session.originalEmail(),
                transcript.stream()
                        .map(message -> new ConsultationDiagnosticsRequest.Message(message.role(), message.body()))
                        .toList(),
                intakeProperties.consultationDiagnosticsSystemPrompt(),
                intakeProperties.consultationDiagnosticsPromptVersion()
        );
    }

    /** Plaintext firm correspondence, envelope and subject both supplied by the template layer. */
    private NotificationOutboxItem questionNotification(ConsultationResponse consultation, DiagnosticsSession session,
            ConsultationDiagnosticsResponse verdict) {
        return new NotificationOutboxItem(
                consultation.id(), null, "DIAGNOSTICS_QUESTION", "CLIENT", consultation.clientEmail(),
                replyAddressFor(session),
                emailTemplateRenderer.diagnosticsQuestionSubject(originalSubjectOf(session)),
                emailTemplateRenderer.renderDiagnosticsQuestion(
                        consultation.clientName(), firmNameOf(session),
                        usableAcknowledgment(verdict.acknowledgment()), verdict.question()),
                null, null);
    }

    /** Same envelope as the question; the firm's own notice is the body and is not touched here. */
    private NotificationOutboxItem nonEngagementNotification(ConsultationResponse consultation, DiagnosticsSession session) {
        String configured = intakeService.settingsForTenant(session.tenantId()).nonEngagementNotice();
        String body = configured == null || configured.isBlank() ? DEFAULT_NON_ENGAGEMENT_NOTICE : configured;
        return new NotificationOutboxItem(
                consultation.id(), null, "NON_ENGAGEMENT_NOTICE", "CLIENT", consultation.clientEmail(),
                replyAddressFor(session),
                emailTemplateRenderer.nonEngagementSubject(originalSubjectOf(session)),
                emailTemplateRenderer.renderNonEngagementNotice(
                        consultation.clientName(), firmNameOf(session), body),
                null, null);
    }

    private String originalSubjectOf(DiagnosticsSession session) {
        return session.originalEmail() == null ? null : session.originalEmail().subject();
    }

    private String firmNameOf(DiagnosticsSession session) {
        return firmNameResolver.firmDisplayName(session.tenantId()).orElse(null);
    }

    private ConsultationClassifierRequest.InboundEmail inboundEmailFor(InboundEmailReceived event) {
        return new ConsultationClassifierRequest.InboundEmail(
                event.subject(), event.plain(), event.html(), event.headerFrom(),
                event.recipients() == null ? List.of() : event.recipients(), event.messageId());
    }

    private String replyBodyFor(InboundEmailReceived event) {
        if (event.plain() != null && !event.plain().isBlank()) {
            return event.plain().trim();
        }
        if (event.html() != null && !event.html().isBlank()) {
            return event.html().trim();
        }
        return event.subject() == null || event.subject().isBlank() ? "(sin contenido)" : event.subject().trim();
    }

    /**
     * The address a Diagnostics message is sent from: a plus-addressed variant of the tenant's
     * intake address carrying the Reply Token. The token travels in From rather than only
     * Reply-To because a significant share of mail clients reply to From.
     */
    String replyAddressFor(DiagnosticsSession session) {
        String intakeEmail = intakeService.settingsForTenant(session.tenantId()).intakeEmail();
        if (intakeEmail == null || intakeEmail.isBlank()) {
            intakeEmail = intakeProperties.canonicalIntakeEmail(session.tenantId());
        }
        int at = intakeEmail.indexOf('@');
        return intakeEmail.substring(0, at) + "+" + REPLY_TAG_PREFIX + session.replyToken() + intakeEmail.substring(at);
    }

    /** 128 bits of unguessable, lower-case hex so no mail client can mangle it in transit. */
    public static String newReplyToken() {
        byte[] bytes = new byte[REPLY_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static Optional<String> replyTokenFrom(List<String> recipients) {
        if (recipients == null) {
            return Optional.empty();
        }
        return recipients.stream()
                .filter(recipient -> recipient != null)
                .map(recipient -> recipient.trim().toLowerCase(Locale.ROOT))
                .map(DiagnosticsService::tokenIn)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<String> tokenIn(String recipient) {
        int at = recipient.indexOf('@');
        int plus = recipient.indexOf('+');
        if (at < 0 || plus < 0 || plus > at) {
            return Optional.empty();
        }
        String tag = recipient.substring(plus + 1, at);
        if (!tag.startsWith(REPLY_TAG_PREFIX)) {
            return Optional.empty();
        }
        String token = tag.substring(REPLY_TAG_PREFIX.length());
        boolean wellFormed = token.length() == REPLY_TOKEN_BYTES * 2
                && token.chars().allMatch(character -> Character.digit(character, 16) >= 0);
        return wellFormed ? Optional.of(token) : Optional.empty();
    }
}
