package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsRepository.NudgeTrigger;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
import com.legalgate.intake.service.IntakeService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reminders that let a lawyer learn about stale work before a Client does: once when an Activity
 * turns Overdue, once when an External term passes, and whenever a Status Inquiry
 * is held back. LegalGate-branded, to the Assigned Lawyer with a copy to the firm's destination
 * address, because lawyers do not sign in and whoever keeps the docket must update the record.
 *
 * <p>Nothing in a reminder changes state: it links to the console, behind normal login.
 */
@Service
public class ActivityNudgeService {

    static final String NUDGE = "ACTIVITY_NUDGE";

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityNudgeService.class);
    private static final String FOOTER = "Este correo es informativo: nada cambia hasta que alguien actualice la actividad en la consola.";

    private final ClientsRepository clients;
    private final IntakeRepository intake;
    private final IntakeService intakeService;
    private final String consoleUrl;

    public ActivityNudgeService(ClientsRepository clients, IntakeRepository intake, IntakeService intakeService,
            @Value("${LEGALGATE_CONSOLE_URL:https://legal-gate.co/dashboard}") String consoleUrl) {
        this.clients = clients;
        this.intake = intake;
        this.intakeService = intakeService;
        this.consoleUrl = consoleUrl;
    }

    @Scheduled(fixedDelayString = "${LEGALGATE_ACTIVITY_NUDGE_SWEEP_MS:3600000}",
            initialDelayString = "${LEGALGATE_ACTIVITY_NUDGE_INITIAL_DELAY_MS:90000}")
    public void sendDueNudges() {
        for (Activity activity : clients.openActivitiesAcrossTenants()) {
            try {
                if (activity.overdue() && !activity.nudgedFor(NudgeTrigger.OVERDUE)) {
                    nudge(activity, NudgeTrigger.OVERDUE, "Actividad vencida", """
                            La actividad "%s" del cliente %s vencio el %s y sigue abierta.
                            Si ya termino, marquela como terminada; si no, actualice su fecha.""");
                }
                if (activity.termPassed() && !activity.nudgedFor(NudgeTrigger.TERM_PASSED)) {
                    nudge(activity, NudgeTrigger.TERM_PASSED, "Termino legal vencido", """
                            El termino legal de "%s" del cliente %s termino el %s y no hay decision registrada.
                            Puede ser momento de actuar, por ejemplo con un incidente de desacato.""");
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Activity reminder id={} tenant={} failed", activity.id(), activity.tenantId(), ex);
            }
        }
    }

    /** The reminder queued when a Client's Status Inquiry is held back because this Activity is Overdue. */
    public List<NotificationOutboxItem> heldBack(Activity activity, Client client) {
        return reminders(activity, client, "Consulta de estado retenida", """
                %2$s pregunto por el estado de su trabajo y LegalGate no respondio porque "%1$s" esta vencida desde el %3$s.
                El correo del cliente se le reenvio a su abogado responsable. Cierre la actividad o actualice su fecha.""");
    }

    private void nudge(Activity activity, NudgeTrigger trigger, String subject, String sentence) {
        Client client = clients.client(activity.tenantId(), activity.clientId()).orElse(null);
        if (client != null) {
            clients.claimNudge(activity.tenantId(), activity.id(), trigger, activity.expectedDate(),
                    reminders(activity, client, subject, sentence));
        }
    }

    private List<NotificationOutboxItem> reminders(Activity activity, Client client, String subject, String sentence) {
        String body = "LegalGate:\n\n"
                + sentence.formatted(activity.displayName(), client.name(), ClientsModels.spanishDate(activity.expectedDate())) + "\n\n"
                + "Ver en la consola: " + consoleUrl + "?client=" + client.id() + "&activity=" + activity.id() + "\n\n"
                + FOOTER;
        String fullSubject = subject + ": " + activity.displayName() + " (" + client.name() + ")";
        List<String> recipients = new ArrayList<>();
        intake.lawyer(activity.tenantId(), activity.assignedLawyerId()).map(LawyerProfile::email).ifPresent(recipients::add);
        String destination = intakeService.settingsForTenant(activity.tenantId()).destinationEmail();
        if (destination != null && recipients.stream().noneMatch(destination::equalsIgnoreCase)) {
            recipients.add(destination);
        }
        return recipients.stream()
                .map(recipient -> NotificationOutboxItem.forActivity(activity.id(), NUDGE, recipient, fullSubject, body))
                .toList();
    }
}
