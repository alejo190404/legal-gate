package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityRequest;
import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.ActivityTypeRequest;
import com.legalgate.intake.clients.ClientsModels;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.service.BusinessDays;

/** Seam 1 for Activities. Time is driven by backdating the dates an Activity is opened with. */
class ActivitiesTests extends ClientsSeam {

    @Test
    void aTurnaroundActivityIsExpectedItsSnapshottedTurnaroundInBusinessDaysAfterItsStart() {
        firm(null);
        ActivityType drafting = type("Redacción de contrato");

        // Friday 9 Oct 2026; Monday 12 Oct is a festivo.
        Activity activity = open(new ActivityRequest(drafting.id(), "Contrato de arriendo",
                LocalDate.of(2026, 10, 9), null, null, null, null));

        assertThat(activity.turnaroundDays()).isEqualTo(5);
        assertThat(activity.expectedDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(activity.assignedLawyerId()).isEqualTo(ClientsFixtures.ANA);
    }

    @Test
    void aDueDateFixedFromOutsideOverridesTheComputedOne() {
        firm(null);
        Activity activity = open(new ActivityRequest(type("Redacción de demanda").id(), null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), null, null, null));

        assertThat(activity.expectedDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void editingTheTypesDefaultNeverMovesAPromiseAlreadyMade() {
        firm(null);
        ActivityType drafting = type("Redacción de contrato");
        Activity activity = open(new ActivityRequest(drafting.id(), null, LocalDate.of(2026, 10, 9), null, null, null, null));

        clients.updateActivityType(TENANT, drafting.id(), new ActivityTypeRequest(drafting.name(), "TURNAROUND", 20, null));

        Activity reloaded = clients.activities(TENANT, acme.id()).get(0);
        assertThat(reloaded.turnaroundDays()).isEqualTo(5);
        assertThat(reloaded.expectedDate()).isEqualTo(activity.expectedDate());
    }

    @Test
    void overdueIsTheFirmsOwnLateWorkAndNeverAnExternalClock() {
        firm(null);
        LocalDate today = BusinessDays.today();
        Activity lateDraft = open(new ActivityRequest(type("Revisión de contrato").id(), null, today.minusDays(30), null, null, null, null));
        Activity pastHearing = open(new ActivityRequest(type("Audiencia").id(), null, today.minusDays(10), null, today.minusDays(1), null, null));
        Activity hearingToday = open(new ActivityRequest(type("Audiencia").id(), null, today.minusDays(10), null, today, null, null));
        Activity ruling = open(new ActivityRequest(type("Fallo de tutela").id(), null, today.minusDays(40), null, null, 10, null));

        assertThat(lateDraft.overdue()).isTrue();
        assertThat(pastHearing.overdue()).isTrue();
        assertThat(hearingToday.overdue()).isFalse();
        assertThat(ruling.overdue()).isFalse();
        assertThat(ruling.termPassed()).isTrue();
        assertThat(ruling.expectedDate()).isEqualTo(BusinessDays.add(today.minusDays(40), 10));
    }

    @Test
    void aDatedActivityNeedsItsDateAndARetiredTypeIsNotOffered() {
        firm(null);
        assertThatThrownBy(() -> open(new ActivityRequest(type("Audiencia").id(), null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("scheduled_date_required");

        ActivityType retired = clients.retireActivityType(TENANT, type("Firma en notaría").id());
        assertThatThrownBy(() -> open(new ActivityRequest(retired.id(), null, null, null, LocalDate.now(), null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("activity_type_retired");

        clients.restoreActivityType(TENANT, retired.id());
        assertThat(open(new ActivityRequest(retired.id(), null, null, null, LocalDate.now(), null, null)).id()).isNotNull();
    }

    @Test
    void doneIsFinalAndAnIdleClientHasNothingOpen() {
        firm(null);
        Activity activity = open(new ActivityRequest(type("Concepto jurídico").id(), null, null, null, null, null, null));
        assertThat(activity.startDate()).isEqualTo(BusinessDays.today());
        assertThat(clients.search(TENANT, null, true, null).items()).extracting(Client::id).doesNotContain(acme.id());

        clients.completeActivity(TENANT, "user_admin", acme.id(), activity.id());

        assertThat(clients.activities(TENANT, acme.id()).get(0).done()).isTrue();
        assertThat(clients.search(TENANT, null, true, null).items()).extracting(Client::id).contains(acme.id());
        assertThatThrownBy(() -> clients.completeActivity(TENANT, "user_admin", acme.id(), activity.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("activity_done");
        assertThatThrownBy(() -> clients.updateActivity(TENANT, "user_admin", acme.id(), activity.id(),
                new ActivityRequest(null, "Otro", null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("activity_done");
    }

    @Test
    void anExternalTermCanBeCorrectedLater() {
        firm(null);
        Activity ruling = open(new ActivityRequest(type("Fallo de tutela").id(), null, LocalDate.of(2026, 10, 1), null, null, 10, null));

        Activity edited = clients.updateActivity(TENANT, "user_admin", acme.id(), ruling.id(),
                new ActivityRequest(null, null, null, null, null, 15, null));

        assertThat(edited.termDays()).isEqualTo(15);
        assertThat(edited.expectedDate()).isEqualTo(BusinessDays.add(LocalDate.of(2026, 10, 1), 15));
        assertThat(clients.history(TENANT, acme.id())).extracting(HistoryEntry::field).contains("termDays");
    }

    @Test
    void anActivityIsOnlyReachedThroughItsOwnClient() {
        firm(null);
        Activity activity = open(new ActivityRequest(type("Concepto jurídico").id(), null, null, null, null, null, null));
        Client other = clients.create(TENANT, "user_admin", new ClientRequest("Beta SAS", ClientsModels.ORGANIZATION,
                null, null, null, ClientsFixtures.ANA, List.of("legal@beta.co")));

        assertThatThrownBy(() -> clients.completeActivity(TENANT, "user_admin", other.id(), activity.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("activity_not_found");
        assertThatThrownBy(() -> clients.updateActivity(TENANT, "user_admin", other.id(), activity.id(),
                new ActivityRequest(null, "Otro", null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("activity_not_found");
    }

    @Test
    void everyActivityChangeIsInTheHistoryWithItsActorAndValues() {
        firm(null);
        Activity activity = open(new ActivityRequest(type("Concepto jurídico").id(), "Concepto laboral",
                LocalDate.of(2026, 10, 1), null, null, null, null));

        clients.updateActivity(TENANT, "user_other", acme.id(), activity.id(), new ActivityRequest(null, "Concepto tributario",
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 20), null, null, ClientsFixtures.LUIS));
        clients.completeActivity(TENANT, "user_other", acme.id(), activity.id());

        assertThat(clients.history(TENANT, acme.id()))
                .filteredOn(entry -> activity.id().equals(entry.activityId()))
                .extracting(HistoryEntry::field, HistoryEntry::oldValue, HistoryEntry::newValue, HistoryEntry::actorUserId)
                .contains(
                        tuple("activity", null, "Concepto laboral", "user_admin"),
                        tuple("label", "Concepto laboral", "Concepto tributario", "user_other"),
                        tuple("startDate", "2026-10-01", "2026-10-02", "user_other"),
                        tuple("dueDate", null, "2026-10-20", "user_other"),
                        tuple("assignedLawyerId", ClientsFixtures.ANA, ClientsFixtures.LUIS, "user_other"),
                        tuple("status", "OPEN", "DONE", "user_other"));
    }

    Activity open(ActivityRequest request) {
        return clients.createActivity(TENANT, "user_admin", acme.id(), request);
    }

    ActivityType type(String name) {
        return clients.activityTypes(TENANT).stream().filter(type -> type.name().equals(name)).findFirst().orElseThrow();
    }

    static List<String> ids(List<Activity> activities) {
        return activities.stream().map(Activity::id).toList();
    }
}
