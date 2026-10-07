package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.legalgate.intake.clients.ClientsModels;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.ClientsModels.Engagement;
import com.legalgate.intake.clients.ClientsModels.EngagementRequest;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.clients.ContactAddressTakenException;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.ConsultationResponse;

/** Seam 1 for Engagement: Consultations come in through the inbound router, as they do in production. */
class EngagementTests extends ClientsSeam {

    @Test
    void engagementIsPrefilledFromTheConsultationAndItsRoutingRule() {
        firm(null);
        ConsultationResponse scheduled = inbound("maria@example.com", "Maria Perez <maria@example.com>");

        Engagement engagement = clients.engagement(TENANT, scheduled.id());

        assertThat(engagement.clientId()).isNull();
        assertThat(engagement.draft().name()).isEqualTo("Maria");
        assertThat(engagement.draft().contactAddresses()).containsExactly("maria@example.com");
        // The Laboral rule routes to Luis.
        assertThat(engagement.draft().responsibleLawyerId()).isEqualTo(ClientsFixtures.LUIS);
    }

    @Test
    void engagingCreatesAClientLinkedToTheConsultationAndRecordsIt() {
        firm(null);
        ConsultationResponse scheduled = inbound("maria@example.com", "Maria Perez <maria@example.com>");

        Client maria = clients.engage(TENANT, "user_admin", scheduled.id(), new EngagementRequest(null,
                new ClientRequest("Maria Perez", ClientsModels.PERSON, "1020304050", null, null, ClientsFixtures.LUIS,
                        List.of("maria@example.com"))));

        assertThat(clients.engagement(TENANT, scheduled.id()).clientId()).isEqualTo(maria.id());
        assertThat(clients.history(TENANT, maria.id()))
                .extracting(HistoryEntry::field, HistoryEntry::newValue, HistoryEntry::actorUserId)
                .contains(tuple("engagement", scheduled.id(), "user_admin"));
        // From now on her mail is a Client's.
        assertThat(router.receive(mail("maria@example.com", "Maria <maria@example.com>", "<after@x.co>")).correspondence())
                .isNotNull();
    }

    @Test
    void engagingIntoAnExistingClientAddsTheSenderAddress() {
        firm(null);
        ConsultationResponse fromNewAddress = inbound("legal@acme.co", "Acme Legal <legal@acme.co>");

        Client attached = clients.engage(TENANT, "user_admin", fromNewAddress.id(), new EngagementRequest(acme.id(), null));

        assertThat(attached.id()).isEqualTo(acme.id());
        assertThat(attached.contactAddresses()).contains("legal@acme.co", "gc@acme.co");
        assertThat(clients.history(TENANT, acme.id()))
                .extracting(HistoryEntry::field, HistoryEntry::oldValue, HistoryEntry::newValue)
                .contains(tuple("contactAddress", null, "legal@acme.co"), tuple("engagement", null, fromNewAddress.id()));
    }

    @Test
    void anAddressOnFileForAnotherClientCannotBeAttached() {
        firm(null);
        ConsultationResponse scheduled = inbound("maria@example.com", "Maria <maria@example.com>");
        Client maria = clients.create(TENANT, "user_admin", new ClientRequest("Maria", ClientsModels.PERSON, null, null,
                null, ClientsFixtures.ANA, List.of("maria@example.com")));

        assertThatThrownBy(() -> clients.engage(TENANT, "user_admin", scheduled.id(), new EngagementRequest(acme.id(), null)))
                .isInstanceOfSatisfying(ContactAddressTakenException.class,
                        taken -> assertThat(taken.clientId()).isEqualTo(maria.id()));
    }

    @Test
    void aConsultationIsEngagedAtMostOnce() {
        firm(null);
        ConsultationResponse scheduled = inbound("maria@example.com", "Maria <maria@example.com>");
        clients.engage(TENANT, "user_admin", scheduled.id(), new EngagementRequest(acme.id(), null));

        assertThatThrownBy(() -> clients.engage(TENANT, "user_admin", scheduled.id(), new EngagementRequest(acme.id(), null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("consultation_already_engaged");
    }

    @Test
    void aConsultationStillInDiagnosticsCannotBeEngaged() {
        firm(PROMPT);
        ConsultationResponse pending = inbound("maria@example.com", "Maria <maria@example.com>");

        assertThatThrownBy(() -> clients.engage(TENANT, "user_admin", pending.id(), new EngagementRequest(acme.id(), null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("consultation_in_diagnostics");
    }

    @Test
    void aConsultationDiagnosticsRejectedCanStillBeEngaged() {
        firm(PROMPT);
        ConsultationResponse pending = inbound("maria@example.com", "Maria <maria@example.com>");
        intake.updateConsultation(TENANT, pending.withStatus("DIAGNOSTICS_REJECTED"), List.of(), List.of());

        assertThatCode(() -> clients.engage(TENANT, "user_admin", pending.id(), new EngagementRequest(acme.id(), null)))
                .doesNotThrowAnyException();
    }

    private ConsultationResponse inbound(String sender, String headerFrom) {
        return router.receive(new InboundEmailReceived("event-" + sender, TENANT, TENANT + "@intake.legal-gate.co",
                List.of(TENANT + "@intake.legal-gate.co"), sender, headerFrom, "Consulta", "<" + sender + ">",
                "Necesito ayuda.", null, Instant.now())).consultation();
    }
}
