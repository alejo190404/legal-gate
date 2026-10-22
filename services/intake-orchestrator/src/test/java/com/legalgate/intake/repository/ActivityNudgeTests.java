package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityRequest;
import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.service.BusinessDays;

/** Seam 1 for reminders: Activities are opened backdated and the worker is run by hand. */
class ActivityNudgeTests extends ClientsSeam {

    @Test
    void anActivityTurningOverdueRemindsItsLawyerOnceWithACopyToTheFirm() {
        firm(null);
        Activity late = open("Revisión de contrato", BusinessDays.today().minusDays(30), null, ClientsFixtures.LUIS);

        nudges.sendDueNudges();
        nudges.sendDueNudges();

        List<NotificationOutboxItem> queued = queued();
        assertThat(queued).extracting(NotificationOutboxItem::type).containsOnly("ACTIVITY_NUDGE");
        // Luis is assigned; the firm's destination address for its first rule is luis@firm.co too, so one email.
        assertThat(queued).extracting(NotificationOutboxItem::recipientEmail).containsExactly("luis@firm.co");
        assertThat(queued.get(0).recipientRole()).isEqualTo("LAWYER");
        assertThat(queued.get(0).subject()).contains("Actividad vencida").contains("Revisión de contrato");
        assertThat(queued.get(0).body())
                .contains("https://app.legal-gate.co/dashboard?client=" + acme.id() + "&activity=" + late.id())
                .contains("Acme SAS");
        assertThat(queued.get(0).htmlBody())
                .contains("https://app.legal-gate.co/dashboard?client=" + acme.id() + "&amp;activity=" + late.id())
                .contains("Actividad vencida");
        assertThat(queued.get(0).activityId()).isEqualTo(late.id());
        assertThat(late.overdue()).isTrue();
    }

    @Test
    void theCopyGoesToTheFirmsDestinationWhenItIsNotTheLawyer() {
        firm(null);
        open("Revisión de contrato", BusinessDays.today().minusDays(30), null, ClientsFixtures.ANA);

        nudges.sendDueNudges();

        assertThat(queued()).extracting(NotificationOutboxItem::recipientEmail)
                .containsExactlyInAnyOrder("ana@firm.co", "luis@firm.co");
    }

    @Test
    void anExternalTermPassingRemindsOnceAndNoTermNeverDoes() {
        firm(null);
        open("Fallo de tutela", BusinessDays.today().minusDays(40), 10, ClientsFixtures.ANA);
        open("Registro de marca", BusinessDays.today().minusDays(400), null, ClientsFixtures.ANA);

        nudges.sendDueNudges();
        nudges.sendDueNudges();

        List<NotificationOutboxItem> toAna = queued().stream().filter(item -> "ana@firm.co".equals(item.recipientEmail())).toList();
        assertThat(toAna).hasSize(1);
        assertThat(toAna.get(0).subject()).contains("Termino legal vencido").contains("Fallo de tutela");
        assertThat(toAna.get(0).body()).contains("incidente de desacato");
    }

    @Test
    void doneWorkAndWorkStillOnTimeNeverRemind() {
        firm(null);
        Activity late = open("Revisión de contrato", BusinessDays.today().minusDays(30), null, ClientsFixtures.ANA);
        clients.completeActivity(TENANT, "user_admin", acme.id(), late.id());
        open("Redacción de demanda", BusinessDays.today(), null, ClientsFixtures.ANA);

        nudges.sendDueNudges();

        assertThat(queued()).isEmpty();
    }

    @Test
    void eachReminderGoesOutOnceEvenWhenTheDateMovesAndLapsesAgain() {
        firm(null);
        Activity late = open("Revisión de contrato", BusinessDays.today().minusDays(30), null, ClientsFixtures.LUIS);
        nudges.sendDueNudges();
        queued();

        clients.updateActivity(TENANT, "user_admin", acme.id(), late.id(), new ActivityRequest(null, null, null,
                BusinessDays.today().minusDays(1), null, null, null));
        nudges.sendDueNudges();

        assertThat(queued()).isEmpty();
    }

    @Test
    void remindersChangeNothingAndCarryNoStateChangingLink() {
        firm(null);
        Activity late = open("Revisión de contrato", BusinessDays.today().minusDays(30), null, ClientsFixtures.LUIS);

        nudges.sendDueNudges();

        String body = queued().get(0).body();
        assertThat(body).doesNotContain("/done").doesNotContain("token");
        assertThat(clients.activities(TENANT, acme.id())).extracting(Activity::done).containsExactly(false);
        assertThat(late.id()).isNotNull();
    }

    Activity open(String typeName, LocalDate start, Integer termDays, String lawyerId) {
        ActivityType type = clients.activityTypes(TENANT).stream()
                .filter(candidate -> candidate.name().equals(typeName)).findFirst().orElseThrow();
        return clients.createActivity(TENANT, "user_admin", acme.id(),
                new ActivityRequest(type.id(), null, start, null, null, termDays, lawyerId));
    }
}
