package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.legalgate.intake.clients.ClientsModels.ActivityKind;
import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityRequest;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.LawyerInChargeException;
import com.legalgate.intake.model.LawyerProfile;

/** Seam 1: a Client's mail must never reach a lawyer who has left the firm. */
class LawyerGuardTests extends ClientsSeam {

    @Test
    void aResponsibleLawyerCannotBeDeactivatedOrRemovedUntilReassigned() {
        firm(null);

        assertThatThrownBy(() -> saveLawyers(ana(false), luis(true)))
                .isInstanceOfSatisfying(LawyerInChargeException.class, refused -> {
                    assertThat(refused.getMessage()).contains("Ana Abogada").contains("Acme SAS");
                    assertThat(refused.blockers()).singleElement()
                            .satisfies(blocker -> assertThat(blocker.clients()).containsExactly("Acme SAS"));
                });
        assertThatThrownBy(() -> saveLawyers(luis(true))).isInstanceOf(LawyerInChargeException.class);

        clients.update(TENANT, "user_admin", acme.id(), new ClientRequest(acme.name(), acme.kind(), null, null, null,
                ClientsFixtures.LUIS, acme.contactAddresses()));

        assertThatCode(() -> saveLawyers(ana(false), luis(true))).doesNotThrowAnyException();
    }

    @Test
    void anAssignedLawyerOnOpenWorkCannotBeDeactivatedUntilItIsDone() {
        firm(null);
        Activity activity = clients.createActivity(TENANT, "user_admin", acme.id(), new ActivityRequest(
                clients.offeredActivityTypes(TENANT).stream()
                        .filter(type -> type.kind() == ActivityKind.TURNAROUND).findFirst().orElseThrow().id(), "Contrato de arriendo", null, null, null, null,
                ClientsFixtures.LUIS));

        assertThatThrownBy(() -> saveLawyers(ana(true), luis(false)))
                .isInstanceOfSatisfying(LawyerInChargeException.class, refused ->
                        assertThat(refused.blockers()).singleElement()
                                .satisfies(blocker -> assertThat(blocker.activities())
                                        .containsExactly("Contrato de arriendo (Acme SAS)")));

        clients.completeActivity(TENANT, "user_admin", acme.id(), activity.id());

        assertThatCode(() -> saveLawyers(ana(true), luis(false))).doesNotThrowAnyException();
    }

    @Test
    void aLawyerWhoLeavesAfterTheirWorkIsDoneIsDeactivatedNotRemoved() {
        firm(null);
        Activity activity = clients.createActivity(TENANT, "user_admin", acme.id(), new ActivityRequest(
                clients.offeredActivityTypes(TENANT).stream()
                        .filter(type -> type.kind() == ActivityKind.TURNAROUND).findFirst().orElseThrow().id(), "Contrato de arriendo", null, null, null, null,
                ClientsFixtures.ANA));
        clients.completeActivity(TENANT, "user_admin", acme.id(), activity.id());
        clients.update(TENANT, "user_admin", acme.id(), new ClientRequest(acme.name(), acme.kind(), null, null, null,
                ClientsFixtures.LUIS, acme.contactAddresses()));

        saveLawyers(luis(true));
        saveLawyers(luis(true)); // saving the team again keeps Ana inactive

        assertThat(intakeService.settingsForTenant(TENANT).lawyers())
                .extracting(LawyerProfile::id, LawyerProfile::active)
                .containsExactlyInAnyOrder(tuple(ClientsFixtures.ANA, false), tuple(ClientsFixtures.LUIS, true),
                        tuple(ClientsFixtures.RETIRED, false));
        assertThat(clients.activities(TENANT, acme.id())).singleElement()
                .satisfies(done -> assertThat(done.assignedLawyerId()).isEqualTo(ClientsFixtures.ANA));
    }

    @Test
    void aLawyerReAddedUnderTheSameEmailReplacesTheOneWhoLeft() {
        firm(null);
        clients.update(TENANT, "user_admin", acme.id(), new ClientRequest(acme.name(), acme.kind(), null, null, null,
                ClientsFixtures.LUIS, acme.contactAddresses()));
        LawyerProfile newAna = new LawyerProfile("44444444-4444-4444-4444-444444444444", "Ana Abogada",
                "ana@firm.co", true, 60, null);

        saveLawyers(newAna, luis(true));

        assertThat(intakeService.settingsForTenant(TENANT).lawyers())
                .extracting(LawyerProfile::id)
                .containsExactlyInAnyOrder(newAna.id(), ClientsFixtures.LUIS, ClientsFixtures.RETIRED);
    }

    private void saveLawyers(LawyerProfile... lawyers) {
        intakeService.saveSettings(TENANT, ClientsFixtures.settingsRequest(null, List.of(lawyers)));
    }

    private static LawyerProfile ana(boolean active) {
        return new LawyerProfile(ClientsFixtures.ANA, "Ana Abogada", "ana@firm.co", active, 60, null);
    }

    private static LawyerProfile luis(boolean active) {
        return new LawyerProfile(ClientsFixtures.LUIS, "Luis Litigante", "luis@firm.co", active, 60, null);
    }
}
