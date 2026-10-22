package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.legalgate.intake.classifier.ClientMailIntentResponse;
import com.legalgate.intake.clients.ClientsModels;
import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityRequest;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.mail.InboundEmailReceived;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.service.BusinessDays;

/** Seam 1 for answering Status Inquiries (ADR-0007), with a stubbed classifier. */
class StatusInquiryTests extends ClientsSeam {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"));

    @Test
    void automaticAnswersStartSwitchedOff() {
        firm(null);

        assertThat(intakeService.settingsForTenant(TENANT).automaticAnswers()).isFalse();
        automaticAnswers(true);
        assertThat(intakeService.settingsForTenant(TENANT).automaticAnswers()).isTrue();
        // A save that does not mention the switch leaves it where it was.
        ClientsFixtures.saveSettings(intake, TENANT, null);
        assertThat(intakeService.settingsForTenant(TENANT).automaticAnswers()).isTrue();
    }

    @Test
    void aStatusInquiryIsAnsweredFromTheRecordToTheAddressThatAskedWhenSwitchedOn() {
        firm(null);
        automaticAnswers(true);
        Activity drafting = open("Redacción de contrato", "Contrato de arriendo", businessDaysAgo(2), null, null, null);

        ask(new InboundEmailReceived("e-1", TENANT, TENANT + "@intake.legal-gate.co", List.of(TENANT + "@intake.legal-gate.co"),
                "ceo@acme.co", "Someone <reply-here@evil.co>", "Como va?", "<s-1@acme.co>", "Como va mi contrato?", null,
                Instant.now()));

        NotificationOutboxItem answer = only(queued(), "CLIENT_STATUS_ANSWER");
        assertThat(answer.recipientEmail()).isEqualTo("ceo@acme.co");
        assertThat(answer.recipientRole()).isEqualTo("CLIENT");
        assertThat(answer.subject()).isEqualTo("Re: Como va?");
        assertThat(answer.body())
                .startsWith("Estimado(a):\n\n")
                .contains("Contrato de arriendo\n")
                .contains("Inicio: " + DATE.format(drafting.startDate()) + ". Han transcurrido 2 dias habiles y faltan 3.")
                .contains("Fecha estimada de entrega: " + DATE.format(drafting.expectedDate()) + ".")
                .endsWith("Cordialmente,\nVargas & Asociados\n")
                .doesNotContain("LegalGate");
        Correspondence entry = onlyEntry();
        assertThat(entry.outcome()).isEqualTo(ClientsModels.ANSWERED);
        assertThat(entry.intent()).isEqualTo(ClientMailIntentResponse.STATUS_INQUIRY);
        assertThat(entry.answerText()).isEqualTo(answer.body());
        assertThat(answer.correspondenceId()).isEqualTo(entry.id());
    }

    @Test
    void whileSwitchedOffTheAnswerIsRecordedAndTheEmailForwarded() {
        firm(null);
        open("Audiencia", "Audiencia inicial", BusinessDays.today(), BusinessDays.today().plusDays(10), null, null);

        ask(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<s-2@acme.co>"));

        assertThat(queued()).extracting(NotificationOutboxItem::type)
                .containsExactlyInAnyOrder("CLIENT_MAIL_FORWARD", "CLIENT_RECEIPT");
        Correspondence entry = onlyEntry();
        assertThat(entry.outcome()).isEqualTo(ClientsModels.WOULD_HAVE_ANSWERED);
        assertThat(entry.answerText()).contains("Audiencia inicial\nProgramada para el "
                + DATE.format(BusinessDays.today().plusDays(10)) + ".");
    }

    @Test
    void anOverdueActivityHoldsTheWholeAnswerBackAndRemindsItsLawyer() {
        firm(null);
        automaticAnswers(true);
        open("Redacción de contrato", "Contrato de arriendo", businessDaysAgo(2), null, null, null);
        openAssigned("Revisión de contrato", "Revision de estatutos", BusinessDays.today().minusDays(40), ClientsFixtures.LUIS);

        ask(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<s-3@acme.co>"));

        List<NotificationOutboxItem> queued = queued();
        assertThat(queued).extracting(NotificationOutboxItem::type).doesNotContain("CLIENT_STATUS_ANSWER")
                .contains("CLIENT_MAIL_FORWARD", "CLIENT_RECEIPT", "ACTIVITY_NUDGE");
        assertThat(queued).filteredOn(item -> "ACTIVITY_NUDGE".equals(item.type()))
                .extracting(NotificationOutboxItem::recipientEmail).containsExactly("luis@firm.co");
        assertThat(only(queued, "CLIENT_MAIL_FORWARD").body()).contains("Revision de estatutos").contains("vencida");
        // The forward is quoted to the Client in the lawyer's reply: its "why" never names LegalGate.
        assertThat(only(queued, "CLIENT_MAIL_FORWARD").body()).doesNotContainIgnoringCase("legalgate");
        assertThat(only(queued, "CLIENT_MAIL_FORWARD").htmlBody()).contains("Revision de estatutos").doesNotContainIgnoringCase("legalgate");
        assertThat(onlyEntry().outcome()).isEqualTo(ClientsModels.HELD_BACK);
    }

    @Test
    void aStatusInquiryWithNothingOpenGoesToTheLawyer() {
        firm(null);
        automaticAnswers(true);

        ask(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<s-4@acme.co>"));

        assertThat(queued()).extracting(NotificationOutboxItem::type)
                .containsExactlyInAnyOrder("CLIENT_MAIL_FORWARD", "CLIENT_RECEIPT");
        assertThat(onlyEntry().outcome()).isEqualTo(ClientsModels.FORWARDED);
        assertThat(onlyEntry().answerText()).isNull();
    }

    @Test
    void mailThatNeedsALawyerIsForwarded() {
        firm(null);
        automaticAnswers(true);
        open("Redacción de contrato", null, businessDaysAgo(1), null, null, null);

        router.receive(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<s-5@acme.co>"));
        classifier.intents.add(ClientMailIntentResponse.NEEDS_LAWYER);
        clientMail.processDueClientMail();

        assertThat(queued()).extracting(NotificationOutboxItem::type)
                .containsExactlyInAnyOrder("CLIENT_MAIL_FORWARD", "CLIENT_RECEIPT");
        assertThat(onlyEntry().intent()).isEqualTo(ClientMailIntentResponse.NEEDS_LAWYER);
    }

    @Test
    void aClassifierOutageRetriesOnTheDiagnosticsLadderThenForwardsAndNeverAnswers() {
        firm(null);
        automaticAnswers(true);
        open("Redacción de contrato", null, businessDaysAgo(1), null, null, null);
        router.receive(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<s-6@acme.co>"));
        classifier.outage();

        clientMail.processDueClientMail();
        assertThat(queued()).isEmpty();
        assertThat(onlyEntry().outcome()).isNull();

        for (int retry = 0; retry < 3; retry++) {
            makeDue(onlyEntry());
            clientMail.processDueClientMail();
        }

        assertThat(classifier.intentCalls).isEqualTo(4);
        List<NotificationOutboxItem> queued = queued();
        assertThat(queued).extracting(NotificationOutboxItem::type)
                .containsExactlyInAnyOrder("CLIENT_MAIL_FORWARD", "CLIENT_RECEIPT");
        assertThat(only(queued, "CLIENT_MAIL_FORWARD").body()).doesNotContainIgnoringCase("legalgate");
        assertThat(only(queued, "CLIENT_MAIL_FORWARD").htmlBody()).doesNotContainIgnoringCase("legalgate");
        assertThat(onlyEntry().outcome()).isEqualTo(ClientsModels.FORWARDED);
    }

    @Test
    void externalWorkIsDescribedAsWaitingOnTheAuthorityWithItsTermOrNone() {
        firm(null);
        LocalDate recent = businessDaysAgo(2);
        LocalDate old = BusinessDays.today().minusDays(60);
        open("Fallo de tutela", null, recent, null, 10);
        open("Respuesta a derecho de petición", null, old, null, 15);
        open("Registro de marca", null, old, null, null, null);

        ask(mail("gc@acme.co", "Acme GC <gc@acme.co>", "<s-7@acme.co>"));

        assertThat(onlyEntry().answerText())
                .contains("Fallo de tutela\nEstamos a la espera de la decision del juzgado o la autoridad. El termino legal vence el "
                        + DATE.format(BusinessDays.add(recent, 10)) + ".")
                .contains("Respuesta a derecho de petición\nEl termino legal para la decision vencio el "
                        + DATE.format(BusinessDays.add(old, 15)) + " y estamos atentos a la respuesta del juzgado o la autoridad.")
                .contains("Registro de marca\nEstamos a la espera de la decision del juzgado o la autoridad, que no tiene un termino fijo.");
    }

    void ask(InboundEmailReceived email) {
        router.receive(email);
        classifier.intents.add(ClientMailIntentResponse.STATUS_INQUIRY);
        clientMail.processDueClientMail();
    }

    Activity open(String typeName, String label, LocalDate start, LocalDate scheduled, Integer termDays) {
        return open(typeName, label, start, scheduled, termDays, null);
    }

    Activity openAssigned(String typeName, String label, LocalDate start, String lawyerId) {
        return open(typeName, label, start, null, null, lawyerId);
    }

    Activity open(String typeName, String label, LocalDate start, LocalDate scheduled, Integer termDays, String lawyerId) {
        String typeId = clients.activityTypes(TENANT).stream()
                .filter(type -> type.name().equals(typeName)).findFirst().orElseThrow().id();
        return clients.createActivity(TENANT, "user_admin", acme.id(),
                new ActivityRequest(typeId, label, start, null, scheduled, termDays, lawyerId));
    }

    /** The date {@code days} Business Days before today. */
    static LocalDate businessDaysAgo(int days) {
        LocalDate date = BusinessDays.today();
        while (BusinessDays.between(date, BusinessDays.today()) < days) {
            date = date.minusDays(1);
        }
        return date;
    }

    void automaticAnswers(boolean on) {
        intakeService.saveSettings(TENANT, ClientsFixtures.settingsRequest(null, ClientsFixtures.lawyers(), on));
    }

    Correspondence onlyEntry() {
        return only(clients.correspondence(TENANT, acme.id()));
    }

    void makeDue(Correspondence entry) {
        clientsRepository.saveCorrespondence(TENANT, new Correspondence(entry.id(), entry.tenantId(), entry.clientId(),
                entry.contactAddress(), entry.messageId(), entry.subject(), entry.body(), entry.receivedAt(),
                entry.intent(), entry.outcome(), entry.answerText(), entry.purgedAt(), entry.attempts(),
                Instant.now().minusSeconds(1)), List.of());
    }

    static <T> T only(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }
}
