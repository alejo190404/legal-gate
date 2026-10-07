package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

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
                        .filter(type -> "TURNAROUND".equals(type.kind())).findFirst().orElseThrow().id(), "Contrato de arriendo", null, null, null, null,
                ClientsFixtures.LUIS));

        assertThatThrownBy(() -> saveLawyers(ana(true), luis(false)))
                .isInstanceOfSatisfying(LawyerInChargeException.class, refused ->
                        assertThat(refused.blockers()).singleElement()
                                .satisfies(blocker -> assertThat(blocker.activities())
                                        .containsExactly("Contrato de arriendo (Acme SAS)")));

        clients.completeActivity(TENANT, "user_admin", activity.id());

        assertThatCode(() -> saveLawyers(ana(true), luis(false))).doesNotThrowAnyException();
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
