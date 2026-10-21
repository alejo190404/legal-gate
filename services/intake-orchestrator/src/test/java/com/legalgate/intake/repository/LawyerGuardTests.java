package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.legalgate.intake.clients.ClientsModels.ActivityKind;
import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityRequest;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.LawyerInChargeException;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;

/** Seam 1: a Client's mail must never reach a lawyer who has left the firm. */
class LawyerGuardTests extends ClientsSeam {

    private static final String NEW_ID = "44444444-4444-4444-4444-444444444444";

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
    void aFormerLawyerAddedBackUnderTheirEmailReturnsAsThemselves() {
        firm(null);
        Activity activity = clients.createActivity(TENANT, "user_admin", acme.id(), new ActivityRequest(
                clients.offeredActivityTypes(TENANT).stream()
                        .filter(type -> type.kind() == ActivityKind.TURNAROUND).findFirst().orElseThrow().id(), "Contrato de arriendo", null, null, null, null,
                ClientsFixtures.ANA));
        clients.completeActivity(TENANT, "user_admin", acme.id(), activity.id());
        clients.update(TENANT, "user_admin", acme.id(), new ClientRequest(acme.name(), acme.kind(), null, null, null,
                ClientsFixtures.LUIS, acme.contactAddresses()));
        saveLawyers(luis(true));
        LawyerProfile returning = new LawyerProfile(NEW_ID, "Ana María Abogada", "ANA@firm.co", true, 45, null);

        TenantSettingsResponse saved = intakeService.saveSettings(TENANT, routedTo(returning, luis(true)));

        assertThat(saved.lawyers())
                .extracting(LawyerProfile::id, LawyerProfile::displayName, LawyerProfile::active)
                .containsExactlyInAnyOrder(tuple(ClientsFixtures.ANA, "Ana María Abogada", true),
                        tuple(ClientsFixtures.LUIS, "Luis Litigante", true), tuple(ClientsFixtures.RETIRED, "Rita Retirada", false));
        assertThat(saved.routingRules()).singleElement()
                .satisfies(rule -> assertThat(rule.lawyerId()).isEqualTo(ClientsFixtures.ANA));
        assertThat(clients.activities(TENANT, acme.id())).singleElement()
                .satisfies(done -> assertThat(done.assignedLawyerId()).isEqualTo(ClientsFixtures.ANA));
    }

    @Test
    void aLawyerRemovedAndAddedBackInOneSaveIsTheSameLawyerOnceTheyHaveNothingOpen() {
        firm(null);
        LawyerProfile replacement = new LawyerProfile(NEW_ID, "Ana Abogada", "ana@firm.co", true, 60, null);

        assertThatThrownBy(() -> saveLawyers(replacement, luis(true))).isInstanceOf(LawyerInChargeException.class);

        clients.update(TENANT, "user_admin", acme.id(), new ClientRequest(acme.name(), acme.kind(), null, null, null,
                ClientsFixtures.LUIS, acme.contactAddresses()));
        saveLawyers(replacement, luis(true));

        assertThat(intakeService.settingsForTenant(TENANT).lawyers())
                .extracting(LawyerProfile::id, LawyerProfile::active)
                .containsExactlyInAnyOrder(tuple(ClientsFixtures.ANA, true), tuple(ClientsFixtures.LUIS, true),
                        tuple(ClientsFixtures.RETIRED, false));
    }

    @Test
    void twoLawyersCannotShareAnEmail() {
        firm(null);

        assertThatThrownBy(() -> saveLawyers(ana(true), luis(true),
                new LawyerProfile(NEW_ID, "Otra Ana", "Ana@Firm.co", true, 60, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(refused.getReason()).isEqualTo("duplicate_lawyer_email");
                });
    }

    @Test
    void aLawyerCannotTakeAnotherLawyersEmail() {
        firm(null);

        assertThatThrownBy(() -> saveLawyers(new LawyerProfile(ClientsFixtures.ANA, "Ana Abogada", "rita@firm.co", true, 60, null),
                luis(true)))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(refused.getReason()).isEqualTo("lawyer_email_taken");
                    assertThat(refused.getMessage()).contains("rita@firm.co").contains("Rita Retirada");
                });
    }

    private static TenantSettingsRequest routedTo(LawyerProfile destination, LawyerProfile... others) {
        TenantRoutingRule rule = ClientsFixtures.settingsRequest(null, List.of()).routingRules().get(0);
        List<LawyerProfile> lawyers = new ArrayList<>(List.of(destination));
        lawyers.addAll(List.of(others));
        return new TenantSettingsRequest(List.of(new TenantRoutingRule(rule.name(), rule.description(), rule.urgentKeywords(),
                rule.consultationWindows(), rule.urgencyLevels(), destination.id(), rule.urgencyDefinitions(),
                destination.email())), lawyers);
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
