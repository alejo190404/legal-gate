package com.legalgate.intake.clients;

import com.legalgate.intake.classifier.ClassifierUnavailableException;
import com.legalgate.intake.classifier.ClientMailIntentRequest;
import com.legalgate.intake.classifier.ClientMailIntentResponse;
import com.legalgate.intake.classifier.ConsultationClassifierClient;
import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
import com.legalgate.intake.service.BusinessDays;
import com.legalgate.intake.service.EmailTemplateRenderer;
import com.legalgate.intake.service.FirmNameResolver;
import com.legalgate.intake.service.IntakeService;
import com.legalgate.intake.service.RenderedEmail;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Mail from a Contact Address: recorded on the webhook as Client Correspondence, acted on by the
 * worker. It never becomes a Consultation and never reaches Diagnostics (ADR-0007).
 *
 * <p>The scheduled methods are public so tests drive them directly instead of waiting on a schedule.
 */
@Service
public class ClientMailService {

    static final String FORWARD = "CLIENT_MAIL_FORWARD";
    static final String RECEIPT = "CLIENT_RECEIPT";
    static final String STATUS_ANSWER = "CLIENT_STATUS_ANSWER";

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientMailService.class);
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final int BATCH_SIZE = 20;
    private static final Duration CORRESPONDENCE_RETENTION = Duration.ofDays(365);

    private final ClientsRepository clients;
    private final IntakeRepository intake;
    private final IntakeService intakeService;
    private final ConsultationClassifierClient classifier;
    private final EmailTemplateRenderer renderer;
    private final FirmNameResolver firmNames;
    private final ActivityNudgeService nudges;

    public ClientMailService(ClientsRepository clients, IntakeRepository intake, IntakeService intakeService,
            ConsultationClassifierClient classifier, EmailTemplateRenderer renderer, FirmNameResolver firmNames,
            ActivityNudgeService nudges) {
        this.clients = clients;
        this.intake = intake;
        this.intakeService = intakeService;
        this.classifier = classifier;
        this.renderer = renderer;
        this.firmNames = firmNames;
        this.nudges = nudges;
    }

    /**
     * The Client whose Contact Address sent this, matched on the envelope sender only: a display
     * name or Reply-To is whatever the sender typed, the envelope is what the mail system saw.
     */
    public Optional<Client> clientFor(InboundEmailReceived event) {
        return envelopeSender(event).flatMap(sender -> clients.clientForContactAddress(event.tenantId(), sender));
    }

    /**
     * Records the email and returns. A redelivery of an email already recorded is a no-op, and an
     * automated responder is recorded as ignored so nothing ever answers it.
     */
    public Optional<Correspondence> receive(InboundEmailReceived event, Client client) {
        String sender = envelopeSender(event).orElseThrow();
        String messageId = firstNonBlank(event.messageId(), event.eventId(), "<" + UUID.randomUUID() + "@legalgate.invalid>");
        Correspondence entry = new Correspondence(null, event.tenantId(), client.id(), sender, messageId.trim(),
                event.subject(), bodyOf(event), event.receivedAt() == null ? Instant.now() : event.receivedAt(),
                null, event.autoResponder() ? ClientsModels.IGNORED : null, null, null, 0,
                event.autoResponder() ? null : Instant.now());
        return clients.recordCorrespondence(event.tenantId(), entry);
    }

    @Scheduled(fixedDelayString = "${LEGALGATE_CLIENT_MAIL_DELAY_MS:30000}",
            initialDelayString = "${LEGALGATE_CLIENT_MAIL_INITIAL_DELAY_MS:20000}")
    public void processDueClientMail() {
        for (Correspondence entry : clients.claimDueCorrespondence(BATCH_SIZE)) {
            try {
                process(entry);
            } catch (RuntimeException ex) {
                // The lease expires and the entry is picked up again; nothing is lost.
                LOGGER.warn("Client mail id={} tenant={} failed", entry.id(), entry.tenantId(), ex);
            }
        }
    }

    /**
     * What a Client wrote is purged after a year; what LegalGate did and the exact words it sent
     * on the firm's behalf are kept while the Client exists (ADR-0007, the split of ADR-0003).
     */
    @Scheduled(fixedDelayString = "${LEGALGATE_CLIENT_MAIL_PURGE_SWEEP_MS:86400000}",
            initialDelayString = "${LEGALGATE_CLIENT_MAIL_PURGE_INITIAL_DELAY_MS:150000}")
    public void purgeExpiredCorrespondence() {
        int purged = clients.purgeCorrespondenceBodies(Instant.now().minus(CORRESPONDENCE_RETENTION));
        if (purged > 0) {
            LOGGER.info("Purged the text of {} Client emails past retention.", purged);
        }
    }

    /**
     * Decides what the email is, then answers it from the record or passes it to a lawyer. Only a
     * Status Inquiry from a Client with open work, none of it Overdue, is ever answered; anything
     * LegalGate cannot decide goes to a person (ADR-0007).
     */
    private void process(Correspondence entry) {
        Client client = clients.client(entry.tenantId(), entry.clientId()).orElse(null);
        if (client == null) {
            return;
        }
        ClientMailIntentResponse intent;
        try {
            intent = classifier.classifyClientMail(new ClientMailIntentRequest(entry.subject(), entry.body()));
        } catch (ClassifierUnavailableException ex) {
            if (entry.attempts() < ConsultationClassifierClient.RETRY_BACKOFF.size()) {
                clients.saveCorrespondence(entry.tenantId(),
                        entry.retrying(Instant.now().plus(ConsultationClassifierClient.RETRY_BACKOFF.get(entry.attempts()))), List.of());
                return;
            }
            LOGGER.warn("Client mail id={} tenant={} could not be classified; forwarding.", entry.id(), entry.tenantId());
            forward(entry, client, null, ClientsModels.FORWARDED, null,
                    "LegalGate no pudo determinar de que trata el correo, asi que no lo respondio.", List.of());
            return;
        }
        if (!intent.isStatusInquiry()) {
            forward(entry, client, intent.intent(), ClientsModels.FORWARDED, null,
                    "El cliente escribio sobre algo que requiere a un abogado.", List.of());
            return;
        }
        List<Activity> open = clients.activities(entry.tenantId(), client.id()).stream().filter(activity -> !activity.done()).toList();
        if (open.isEmpty()) {
            // "Nothing pending" may be work the firm never logged, so a person answers.
            forward(entry, client, intent.intent(), ClientsModels.FORWARDED, null,
                    "El cliente pregunto por el estado de su trabajo, pero no tiene actividades abiertas registradas.", List.of());
            return;
        }
        List<Activity> overdue = open.stream().filter(Activity::overdue).toList();
        if (!overdue.isEmpty()) {
            // LegalGate never tells a Client in writing that the firm is late.
            List<NotificationOutboxItem> reminders = new ArrayList<>();
            overdue.forEach(activity -> reminders.addAll(nudges.heldBack(activity, client)));
            forward(entry, client, intent.intent(), ClientsModels.HELD_BACK, null,
                    "El cliente pregunto por el estado de su trabajo y LegalGate no respondio porque esta vencida: "
                            + String.join(", ", overdue.stream().map(Activity::displayName).toList()) + ".",
                    reminders);
            return;
        }
        RenderedEmail answer = renderer.renderStatusAnswer(salutationName(client), firmName(entry), open, BusinessDays.today());
        if (intakeService.settingsForTenant(entry.tenantId()).automaticAnswers()) {
            clients.saveCorrespondence(entry.tenantId(),
                    entry.resolved(intent.intent(), ClientsModels.ANSWERED, answer.text()),
                    List.of(clientMessage(entry, STATUS_ANSWER, answer)));
            return;
        }
        forward(entry, client, intent.intent(), ClientsModels.WOULD_HAVE_ANSWERED, answer.text(),
                "El cliente pregunto por el estado de su trabajo. Las respuestas automaticas estan apagadas; "
                        + "la consola muestra la respuesta que se habria enviado.", List.of());
    }

    /** Passes the email to the Responsible Lawyer and sends the Client a receipt, in one write. */
    void forward(Correspondence entry, Client client, String intent, String outcome, String answerText, String why,
            List<NotificationOutboxItem> alsoQueue) {
        List<NotificationOutboxItem> notifications = new ArrayList<>(List.of(forwardToLawyer(entry, client, why), receipt(entry, client)));
        notifications.addAll(alsoQueue);
        clients.saveCorrespondence(entry.tenantId(), entry.resolved(intent, outcome, answerText), notifications);
    }

    private NotificationOutboxItem forwardToLawyer(Correspondence entry, Client client, String why) {
        LawyerProfile lawyer = lawyer(entry.tenantId(), client.responsibleLawyerId());
        RenderedEmail mail = renderer.renderClientForward(client.name(), entry.contactAddress(), why, entry.subject(), entry.body());
        return NotificationOutboxItem.forCorrespondence(entry.id(), FORWARD, "LAWYER", lawyer.email(), null,
                entry.contactAddress(), "Correo de cliente: " + client.name() + " - " + firstNonBlank(entry.subject(), "(sin asunto)"),
                mail.text(), mail.html());
    }

    private NotificationOutboxItem receipt(Correspondence entry, Client client) {
        return clientMessage(entry, RECEIPT, renderer.renderClientReceipt(salutationName(client), firmName(entry)));
    }

    /**
     * Firm Voice, threaded on the Client's own email, to the Contact Address that matched and from
     * the tenant's plain intake address, so a reply comes back through Contact Address matching.
     */
    NotificationOutboxItem clientMessage(Correspondence entry, String type, RenderedEmail mail) {
        return NotificationOutboxItem.forCorrespondence(entry.id(), type, "CLIENT", entry.contactAddress(),
                intakeService.settingsForTenant(entry.tenantId()).intakeEmail(), null,
                renderer.clientReplySubject(entry.subject()), mail.text(), mail.html());
    }

    String firmName(Correspondence entry) {
        return firmNames.firmDisplayName(entry.tenantId()).orElse(null);
    }

    static String salutationName(Client client) {
        return ClientsModels.ORGANIZATION.equals(client.kind()) ? null : client.name();
    }

    LawyerProfile lawyer(String tenant, String lawyerId) {
        return intake.lawyer(tenant, lawyerId)
                .orElseThrow(() -> new IllegalStateException("lawyer_not_found:" + lawyerId));
    }

    private static Optional<String> envelopeSender(InboundEmailReceived event) {
        if (event.envelopeFrom() == null) {
            return Optional.empty();
        }
        Matcher matcher = EMAIL.matcher(event.envelopeFrom());
        return matcher.find() ? Optional.of(matcher.group().toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    private static String bodyOf(InboundEmailReceived event) {
        return firstNonBlank(event.plain(), event.html(), null);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
