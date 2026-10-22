package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityKind;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;

class EmailTemplateRendererTests {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void lawyerTemplateFillsFieldsAndEscapes() {
        String html = renderer.renderLawyer(consultation(), event()).html();

        assertThat(html).contains("A1B2C3D4");                 // matter_id: first 8 compact chars, uppercased
        assertThat(html).contains("Juan &amp; Co Pérez"); // client_name with & escaped
        assertThat(html).contains("Tutela");                   // route
        assertThat(html).contains("URGENTE");                   // urgency
        assertThat(html).doesNotContain("{{");                 // every merge field substituted
    }

    @Test
    void clientTemplateFormatsDateAndEscapesSummary() {
        String html = renderer.renderClient(consultation(), event(), "Firma Ejemplo").html();

        assertThat(html).contains("Juan");            // client_first_name (first token)
        assertThat(html).contains("julio");           // Spanish month in America/Bogota
        assertThat(html).contains("14:00–14:45"); // time_range (19:00Z -> 14:00 COT)
        assertThat(html).contains("45 min");          // duration
        // summary is HTML-escaped, raw markup must not leak into the document
        assertThat(html).contains("&lt;b&gt;urgente&lt;/b&gt; &amp; grave");
        assertThat(html).doesNotContain("<b>urgente</b>");
        assertThat(html).doesNotContain("{{");
    }

    @Test
    void clientTemplateHandlesNullScheduleWithoutThrowing() {
        EventResponse openEnded = new EventResponse(
                "event-1", "lawyer-1", "Ana Abogada", "ana@firm.co",
                "Tutela", "URGENTE", 1, null, 100,
                null, null, "SCHEDULED", "SYSTEM");

        String html = renderer.renderClient(consultation(), openEnded, "Firma Ejemplo").html();

        assertThat(html).doesNotContain("{{");
    }

    @Test
    void clientReceiptSaysItIsAutomaticAndInvitesNoReply() {
        String html = renderer.renderClient(consultation(), event(), "Firma Ejemplo").html();

        assertThat(html).contains("Este correo es autom&aacute;tico");
        assertThat(html).doesNotContain("Responde a este mensaje");
    }

    @Test
    void clientTemplateIsFirmBrandedAndCarriesNoLegalGateMark() {
        String html = renderer.renderClient(consultation(), event(), "Firma Ejemplo").html();

        assertThat(html).containsOnlyOnce("Firma Ejemplo"); // named once, in the header, like letterhead
        assertThat(html).doesNotContain("LegalGate"); // no wordmark, no product footer
        assertThat(html).doesNotContain("LEGALGATE");
        assertThat(html).doesNotContain("`");         // the backtick-corrupted CSS values are gone
    }

    @Test
    void aTenantWithNoDisplayNameGetsAnEmptyHeaderBandAndNoLegalGateAnywhere() {
        // No fallback to the sender name: a stranger never sees LegalGate in a firm's letter (ADR-0009).
        RenderedEmail mail = renderer.renderClient(consultation(), event(), null);

        assertThat(mail.html()).containsPattern("letter-spacing:-0\\.01em;\">\\s*</div>");
        assertThat(mail.html()).doesNotContainIgnoringCase("legalgate");
        assertThat(mail.text()).doesNotContainIgnoringCase("legalgate");
        assertThat(mail.text()).startsWith("Tu consulta fue agendada.");
    }

    @Test
    void theScheduledCardsCarryTheirPlaintextPart() {
        assertThat(renderer.renderClient(consultation(), event(), "Firma Ejemplo").text())
                .startsWith("Tu consulta con Firma Ejemplo fue agendada.")
                .contains("Abogado: Ana Abogada <ana@firm.co>");
        assertThat(renderer.renderLawyer(consultation(), event()).text())
                .startsWith("LegalGate agendo una consulta.")
                .contains("Cliente: Juan & Co Pérez <juan@example.com>");
    }

    @Test
    void clientTemplateNamesNoLawyerRatherThanAnyLegalGateOne() {
        EventResponse unassigned = new EventResponse(
                "event-1", "lawyer-1", null, null,
                "Tutela", "URGENTE", 1, Instant.parse("2026-07-03T00:00:00Z"), 100,
                Instant.parse("2026-07-02T19:00:00Z"), Instant.parse("2026-07-02T19:45:00Z"),
                "SCHEDULED", "SYSTEM");

        assertThat(renderer.renderClient(consultation(), unassigned, "Firma Ejemplo").html())
                .doesNotContain("LegalGate")
                .contains("Por asignar");
    }

    @Test
    void diagnosticsQuestionIsFirmCorrespondenceAroundTheModelsQuestion() {
        String body = renderer.renderDiagnosticsQuestion(
                "Alejandro Barragán", "Firma Ejemplo", "la acusación en su institución educativa",
                "  ¿Cual fue la fecha del despido?  ").text();

        assertThat(body).contains(
                "Estimado(a) Alejandro:\n\nRecibimos su mensaje sobre la acusación en su institución educativa.\n\n");
        assertThat(body).contains("Lo que necesitamos:\n\n¿Cual fue la fecha del despido?\n");
        assertThat(body).contains("Quedamos atentos a su respuesta.");
        assertThat(body).contains("Cordialmente,\nEquipo de consultas\nFirma Ejemplo\n");
        assertThat(body).contains("Este mensaje no constituye asesoría legal y no crea una relación abogado-cliente.");
        // The reply-linking sentence is an automation tell now that the message is a threaded Re:.
        assertThat(body).doesNotContain("vinculada automaticamente");
        assertThat(body).doesNotContain("<");
    }

    @Test
    void theAcknowledgmentFallsBackToABareReceiptRatherThanNamingWhatNobodyWrote() {
        assertThat(renderer.renderDiagnosticsQuestion("Maria Perez", "Firma Ejemplo", null, "¿Fecha?").text())
                .contains("Estimado(a) Maria:\n\nRecibimos su mensaje.\n\n");
        assertThat(renderer.renderDiagnosticsQuestion("Maria Perez", "Firma Ejemplo", "   ", "¿Fecha?").text())
                .contains("Recibimos su mensaje.\n\n");
        // The model punctuating its own restatement must not produce "sobre su despido..".
        assertThat(renderer.renderDiagnosticsQuestion("Maria Perez", "Firma Ejemplo", "su despido.", "¿Fecha?").text())
                .contains("Recibimos su mensaje sobre su despido.\n\n");
    }

    @Test
    void theAcknowledgmentCarriesOneLeadInEvenWhenTheModelEchoesIt() {
        // The model is asked to complete the sentence and sometimes returns the whole of it.
        assertThat(renderer.renderDiagnosticsQuestion(
                "Maria Perez", "Firma Ejemplo", "Recibimos su mensaje sobre que la acusan de plagio", "¿Fecha?").text())
                .contains("Recibimos su mensaje sobre que la acusan de plagio.\n\n");
        assertThat(renderer.renderDiagnosticsQuestion(
                "Maria Perez", "Firma Ejemplo", "recibimos su mensaje sobre: su despido", "¿Fecha?").text())
                .contains("Recibimos su mensaje sobre su despido.\n\n");
        // Nothing left once the echo goes: the bare receipt, not a sentence ending in "sobre".
        assertThat(renderer.renderDiagnosticsQuestion(
                "Maria Perez", "Firma Ejemplo", "Recibimos su mensaje.", "¿Fecha?").text())
                .contains("Estimado(a) Maria:\n\nRecibimos su mensaje.\n\n");
    }

    @Test
    void diagnosticsQuestionNeverSignsWithALawyer() {
        String body = renderer.renderDiagnosticsQuestion("Maria Perez", "Firma Ejemplo", "su despido", "¿Tiene el contrato?").text();

        assertThat(body).doesNotContain("Ana Abogada");
        assertThat(body).doesNotContain("Abogado");
    }

    @Test
    void salutationFallsBackToANeutralFormWhenTheNameCannotBeTrusted() {
        assertThat(salutationFor("Unknown client")).isEqualTo("Estimado(a):");
        assertThat(salutationFor("maria@example.com")).isEqualTo("Estimado(a):");
        assertThat(salutationFor("albarragan")).isEqualTo("Estimado(a):");
        assertThat(salutationFor("juan perez")).isEqualTo("Estimado(a):");
        assertThat(salutationFor("   ")).isEqualTo("Estimado(a):");
        assertThat(salutationFor(null)).isEqualTo("Estimado(a):");
        assertThat(salutationFor("Maria Perez")).isEqualTo("Estimado(a) Maria:");
    }

    @Test
    void signatureOmitsTheFirmLineRatherThanLeakingLegalGateToAPotentialClient() {
        String body = renderer.renderDiagnosticsQuestion("Maria Perez", null, "su despido", "¿Fecha?").text();

        assertThat(body).contains("Cordialmente,\nEquipo de consultas\n\n---");
        assertThat(body).doesNotContain("LegalGate");
    }

    @Test
    void theNonEngagementNoticeWearsTheSameEnvelopeAsTheQuestion() {
        String body = renderer.renderNonEngagementNotice(
                "Maria Perez", "Firma Ejemplo", "La firma no puede asumir este asunto.").text();

        assertThat(body).contains("Estimado(a) Maria:\n\nLa firma no puede asumir este asunto.\n\n");
        assertThat(body).contains("Cordialmente,\nEquipo de consultas\nFirma Ejemplo\n");
        assertThat(body).contains("Este mensaje no constituye asesoría legal y no crea una relación abogado-cliente.");
        // The firm is declining; nothing here invites a reply.
        assertThat(body).doesNotContain("Quedamos atentos");
    }

    @Test
    void aFirmAuthoredNoticeIsReproducedVerbatim() {
        String notice = "Apreciado(a) consultante:\n\nNo tomamos este asunto.\n\nAtentamente,\nLa firma";

        String body = renderer.renderNonEngagementNotice("Maria Perez", "Firma Ejemplo", "\n\n" + notice + "  \n").text();

        // Nothing inserted, reworded or stripped between the salutation and the signature.
        assertThat(body).isEqualTo("Respuesta a su consulta\nSobre su consulta\n\n"
                + "Estimado(a) Maria:\n\n" + notice + "\n\nCordialmente,\n"
                + "Equipo de consultas\nFirma Ejemplo\n\n---\n"
                + "Aviso: Este mensaje no constituye asesoría legal y no crea una relación abogado-cliente.\n");
    }

    // --- Diagnostics question and Non-Engagement Notice cards (#90) ---

    @Test
    void theDiagnosticsQuestionIsAHouseStyleCardWhosePlaintextCarriesEverySentence() {
        RenderedEmail mail = renderer.renderDiagnosticsQuestion(
                "Maria Perez", "Firma Ejemplo", "su despido", "¿Cuál fue la fecha del despido?");

        assertThat(CardParity.visibleSentences(mail.html())).containsExactly(
                "Firma Ejemplo", "Revisión de su consulta", "Necesitamos algunos datos",
                "Estimado(a) Maria:", "Recibimos su mensaje sobre su despido.",
                "Lo que necesitamos", "¿Cuál fue la fecha del despido?",
                "Quedamos atentos a su respuesta.", "Cordialmente,", "Equipo de consultas", "Firma Ejemplo",
                "Aviso", "Este mensaje no constituye asesoría legal y no crea una relación abogado-cliente.",
                "Puede responder directamente a este correo.");
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void theNonEngagementNoticeIsAHouseStyleCardWhosePlaintextCarriesEverySentence() {
        RenderedEmail mail = renderer.renderNonEngagementNotice(
                "Maria Perez", "Firma Ejemplo", "La firma no puede asumir este asunto.");

        assertThat(CardParity.visibleSentences(mail.html())).containsExactly(
                "Firma Ejemplo", "Respuesta a su consulta", "Sobre su consulta", "Estimado(a) Maria:",
                "La firma no puede asumir este asunto.", "Cordialmente,", "Equipo de consultas", "Firma Ejemplo",
                "Aviso", "Este mensaje no constituye asesoría legal y no crea una relación abogado-cliente.");
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void markupInTheQuestionOrTheNoticeComesOutEscapedAndKeepsItsLineBreaks() {
        String multiLine = "<b>¿Fecha?</b> & <script>x</script>\n¿Lugar?";

        RenderedEmail question = renderer.renderDiagnosticsQuestion("Maria Perez", "Firma Ejemplo", "su despido", multiLine);
        RenderedEmail notice = renderer.renderNonEngagementNotice("Maria Perez", "Firma Ejemplo", multiLine);

        for (RenderedEmail mail : List.of(question, notice)) {
            assertThat(mail.html())
                    .contains("&lt;b&gt;¿Fecha?&lt;/b&gt; &amp; &lt;script&gt;x&lt;/script&gt;\n¿Lugar?")
                    .doesNotContain("<b>", "<script>");
            assertThat(mail.text()).contains(multiLine);
            CardParity.assertPlaintextCarriesTheCard(mail);
        }
    }

    @Test
    void anUnusableAcknowledgmentDegradesToTheBareReceiptInTheCardToo() {
        // An over-long one reaches the renderer as null: DiagnosticsService drops it first.
        RenderedEmail mail = renderer.renderDiagnosticsQuestion("Maria Perez", "Firma Ejemplo", "   ", "¿Fecha?");

        assertThat(CardParity.visibleSentences(mail.html()))
                .contains("Recibimos su mensaje.")
                .noneMatch(sentence -> sentence.contains("sobre"));
        assertThat(mail.text()).contains("Estimado(a) Maria:\n\nRecibimos su mensaje.\n\n");
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void withNoFirmNameBothCardsHaveAnEmptyHeaderBandAndSignAsTheConsultationTeamAlone() {
        RenderedEmail question = renderer.renderDiagnosticsQuestion("Maria Perez", null, "su despido", "¿Fecha?");
        RenderedEmail notice = renderer.renderNonEngagementNotice("Maria Perez", "  ", "No tomamos este asunto.");

        for (RenderedEmail mail : List.of(question, notice)) {
            assertThat(mail.html()).containsPattern("letter-spacing:-0\\.01em;\">\\s*</div>");
            List<String> sentences = CardParity.visibleSentences(mail.html());
            assertThat(sentences.get(sentences.indexOf("Equipo de consultas") + 1)).isEqualTo("Aviso");
            assertThat(mail.text()).contains("Cordialmente,\nEquipo de consultas\n\n---\n");
            CardParity.assertPlaintextCarriesTheCard(mail);
        }
    }

    @Test
    void noticeSubjectStaysOnTheConsultationThread() {
        assertThat(renderer.nonEngagementSubject("  Consulta laboral ")).isEqualTo("Re: Consulta laboral");
        assertThat(renderer.nonEngagementSubject("Re: Consulta laboral")).isEqualTo("Re: Consulta laboral");
        assertThat(renderer.nonEngagementSubject("  ")).isEqualTo("Sobre su consulta");
        assertThat(renderer.nonEngagementSubject(null)).isEqualTo("Sobre su consulta");
    }

    @Test
    void questionSubjectRepliesOnTheClientsOwnSubjectLine() {
        assertThat(renderer.diagnosticsQuestionSubject("  Consulta laboral ")).isEqualTo("Re: Consulta laboral");
        assertThat(renderer.diagnosticsQuestionSubject("Re: Consulta laboral")).isEqualTo("Re: Consulta laboral");
        // A blank subject must never go out as a bare "Re:".
        assertThat(renderer.diagnosticsQuestionSubject("  ")).isEqualTo("Necesitamos algunos datos para revisar su consulta");
        assertThat(renderer.diagnosticsQuestionSubject(null)).isEqualTo("Necesitamos algunos datos para revisar su consulta");
    }

    // --- #91: client receipt and status answer cards ---

    @Test
    void theClientReceiptIsAFirmCardThatRepeatsTheClientsSubject() {
        RenderedEmail mail = renderer.renderClientReceipt("Maria Perez", "Firma Ejemplo", "Contrato <urgente>");

        assertThat(mail.html())
                .contains("Mensaje recibido")
                .contains("Su mensaje est&aacute; con su abogado(a)")
                .contains("Estimado(a) Maria:")
                .contains("Asunto: Contrato &lt;urgente&gt;")
                .doesNotContain("<urgente>")
                .doesNotContain("{{");
        assertThat(mail.text()).isEqualTo("""
                Mensaje recibido
                Su mensaje está con su abogado(a)

                Estimado(a) Maria:

                Recibimos su mensaje y lo trasladamos a su abogado(a), quien le responderá directamente.

                Cordialmente,
                Firma Ejemplo

                Su mensaje · Asunto: Contrato <urgente>
                """);
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void aReceiptForAnEmailWithNoSubjectSaysSo() {
        assertThat(renderer.renderClientReceipt(null, null, "  ").text()).endsWith("Su mensaje · Asunto: (sin asunto)\n");
    }

    @Test
    void theStatusAnswerListsEachOpenActivityWithItsStatusLine() {
        RenderedEmail mail = renderer.renderStatusAnswer("Maria Perez", "Firma Ejemplo",
                List.of(dated("Audiencia inicial", LocalDate.of(2026, 3, 3))), LocalDate.of(2026, 3, 1));

        assertThat(mail.text()).isEqualTo("""
                Estado de sus asuntos
                Así van sus asuntos

                Estimado(a) Maria:

                Este es el estado de sus asuntos con nosotros:

                Audiencia inicial
                Programada para el 3 de marzo de 2026.

                Cordialmente,
                Firma Ejemplo

                Corte · Estado al 1 de marzo de 2026.
                """);
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void theStatusCardHasOneEscapedRowPerOpenActivityAndInvitesNoReply() {
        LocalDate today = LocalDate.of(2026, 3, 1);
        RenderedEmail mail = renderer.renderStatusAnswer(null, "Firma Ejemplo", List.of(
                dated("Audiencia <b>inicial</b>", LocalDate.of(2026, 3, 3)),
                dated("Entrega & firma", LocalDate.of(2026, 3, 9))), today);

        assertThat(mail.html())
                .contains("Estado de sus asuntos")
                .contains("As&iacute; van sus asuntos")
                .contains("Audiencia &lt;b&gt;inicial&lt;/b&gt;")
                .doesNotContain("<b>inicial</b>")
                .contains("Entrega &amp; firma")
                .contains("Programada para el 9 de marzo de 2026.")
                .contains("Estado al 1 de marzo de 2026.")
                .doesNotContain("{{");
        assertThat(mail.html().split("Programada para el", -1)).as("one row per open Activity").hasSize(3);
        assertThat(mail.html() + mail.text()).doesNotContain("Puede responder directamente a este correo");
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void theStatusLinesAreSpelledWithTheirAccents() {
        LocalDate today = LocalDate.of(2026, 3, 2);
        Activity external = new Activity("activity-2", "tenant-a", "client-1", "type-1", "Tutela", ActivityKind.EXTERNAL,
                "Fallo", today.minusDays(60), null, null, null, 10, "lawyer-1", false, null, null, null,
                Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(renderer.renderStatusAnswer(null, null, List.of(external), today).text())
                .contains("El término legal para la decisión venció el ")
                .contains("y estamos atentos a la respuesta del juzgado o la autoridad.");
    }

    // --- end #91 ---

    @Test
    void theClientForwardTellsTheLawyerWhoWroteWhyAndWhat() {
        RenderedEmail mail = renderer.renderClientForward("Acme SAS", "gc@acme.co", "Motivo.", null, "  ");

        assertThat(mail.text()).isEqualTo("""
                LegalGate le reenvia un correo de su cliente Acme SAS <gc@acme.co>.
                Motivo: Motivo.
                Responda a este correo para contestarle directamente al cliente.

                Asunto: (sin asunto)

                (sin contenido)""");
    }

    @Test
    void anActivityNudgeNamesTheActivityTheClientAndTheDateAndLinksToTheConsole() {
        RenderedEmail mail = renderer.renderActivityNudge(EmailTemplateRenderer.ActivityNudge.OVERDUE,
                "Contestación", "Acme SAS", LocalDate.of(2026, 3, 3), "https://x.co/dashboard?client=c&activity=a");

        assertThat(mail.text()).isEqualTo("""
                LegalGate:

                La actividad "Contestación" del cliente Acme SAS vencio el 3 de marzo de 2026 y sigue abierta.
                Si ya termino, marquela como terminada; si no, actualice su fecha.

                Ver en la consola: https://x.co/dashboard?client=c&activity=a

                Este correo es informativo: nada cambia hasta que alguien actualice la actividad en la consola.""");
    }

    static Activity dated(String label, LocalDate on) {
        return new Activity("activity-1", "tenant-a", "client-1", "type-1", "Audiencia", ActivityKind.DATED, label,
                on.minusDays(10), null, null, on, null, "lawyer-1", false, null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private String salutationFor(String clientName) {
        return CardParity.visibleSentences(
                renderer.renderDiagnosticsQuestion(clientName, "Firma Ejemplo", "su despido", "¿Fecha?").html()).get(3);
    }

    static ConsultationResponse consultation() {
        return new ConsultationResponse(
                "a1b2c3d4-0000-4000-8000-000000000000", "tenant-a",
                "Juan & Co Pérez", "juan@example.com",
                "<b>urgente</b> & grave", "manana", "SCHEDULED", "URGENTE",
                "GENERAL", "ana@firm.co", null, null, null, null, Instant.parse("2026-07-01T12:00:00Z"));
    }

    static EventResponse event() {
        return new EventResponse(
                "event-1", "lawyer-1", "Ana Abogada", "ana@firm.co",
                "Tutela", "URGENTE", 1, Instant.parse("2026-07-03T00:00:00Z"), 100,
                Instant.parse("2026-07-02T19:00:00Z"), Instant.parse("2026-07-02T19:45:00Z"),
                "SCHEDULED", "SYSTEM");
    }
}
