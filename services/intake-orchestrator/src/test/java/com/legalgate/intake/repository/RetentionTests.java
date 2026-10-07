package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.legalgate.intake.classifier.ClientMailIntentResponse;
import com.legalgate.intake.clients.ClientsModels;
import com.legalgate.intake.clients.ClientsModels.ActivityRequest;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.clients.ClientsModels.EngagementRequest;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.ConsultationResponse;

/** Seam 1 for retention and deleting a Client (Ley 1581). */
class RetentionTests extends ClientsSeam {

    @Test
    void whatClientsWroteIsPurgedAfterAYearWhileWhatLegalGateDidIsKept() {
        firm(null);
        receivedAt("<old@acme.co>", Instant.now().minus(Duration.ofDays(400)));
        receivedAt("<new@acme.co>", Instant.now().minus(Duration.ofDays(10)));

        clientMail.purgeExpiredCorrespondence();

        List<Correspondence> entries = clients.correspondence(TENANT, acme.id());
        Correspondence old = entries.stream().filter(entry -> entry.messageId().equals("<old@acme.co>")).findFirst().orElseThrow();
        Correspondence recent = entries.stream().filter(entry -> entry.messageId().equals("<new@acme.co>")).findFirst().orElseThrow();
        assertThat(old.body()).isNull();
        assertThat(old.purgedAt()).isNotNull();
        assertThat(old.outcome()).isEqualTo(ClientsModels.WOULD_HAVE_ANSWERED);
        assertThat(old.intent()).isEqualTo(ClientMailIntentResponse.STATUS_INQUIRY);
        assertThat(old.answerText()).isNotBlank();
        assertThat(recent.body()).isEqualTo("Como va mi contrato?");
    }

    @Test
    void deletingAClientRemovesItsDataAndTheNextEmailFromItsAddressIsAConsultation() {
        firm(null);
        ConsultationResponse engaged = router.receive(new InboundEmailReceived("e-c", TENANT, TENANT + "@intake.legal-gate.co",
                List.of(TENANT + "@intake.legal-gate.co"), "legal@acme.co", "Legal <legal@acme.co>", "Consulta",
                "<c@acme.co>", "Hola.", null, Instant.now())).consultation();
        clients.engage(TENANT, "user_admin", engaged.id(), new EngagementRequest(acme.id(), null));
        receivedAt("<m@acme.co>", Instant.now());

        clients.deleteClient(TENANT, acme.id());

        assertThatThrownBy(() -> clients.client(TENANT, acme.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("client_not_found");
        assertThat(clientsRepository.activities(TENANT, acme.id())).isEmpty();
        assertThat(clientsRepository.history(TENANT, acme.id())).isEmpty();
        assertThat(clientsRepository.correspondenceForClient(TENANT, acme.id())).isEmpty();
        assertThat(clients.engagement(TENANT, engaged.id()).clientId()).isNull();
        assertThat(intake.consultationById(TENANT, engaged.id())).isPresent();

        InboundEmailReceived again = mail("gc@acme.co", "Acme GC <gc@acme.co>", "<after-delete@acme.co>");
        assertThat(router.receive(again).consultation()).isNotNull();
    }

    void receivedAt(String messageId, Instant when) {
        clients.createActivity(TENANT, "user_admin", acme.id(), new ActivityRequest(
                clients.offeredActivityTypes(TENANT).stream().filter(type -> "TURNAROUND".equals(type.kind()))
                        .findFirst().orElseThrow().id(), null, null, null, null, null, null));
        InboundEmailReceived email = mail("gc@acme.co", "Acme GC <gc@acme.co>", messageId);
        router.receive(new InboundEmailReceived(email.eventId(), email.tenantId(), email.envelopeTo(), email.recipients(),
                email.envelopeFrom(), email.headerFrom(), email.subject(), email.messageId(), email.plain(), email.html(), when));
        classifier.intents.add(ClientMailIntentResponse.STATUS_INQUIRY);
        clientMail.processDueClientMail();
    }
}
