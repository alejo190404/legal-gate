package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.HtmlUtils;

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

        assertThat(body).startsWith(
                "Estimado(a) Alejandro:\n\nRecibimos su mensaje sobre la acusación en su institución educativa.\n\n");
        assertThat(body).contains("Para poder revisar su consulta necesitamos algunos datos adicionales:");
        assertThat(body).contains("¿Cual fue la fecha del despido?");
        assertThat(body).contains("Quedamos atentos a su respuesta.");
        assertThat(body).contains("Cordialmente,\nEquipo de consultas\nFirma Ejemplo\n");
        assertThat(body).contains("Este mensaje no constituye asesoria legal y no crea una relacion abogado-cliente.");
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

        assertThat(body).startsWith("Estimado(a) Maria:\n\n");
        assertThat(body).contains("Cordialmente,\nEquipo de consultas\nFirma Ejemplo\n");
        assertThat(body).contains("Este mensaje no constituye asesoria legal y no crea una relacion abogado-cliente.");
        // The firm is declining; nothing here invites a reply.
        assertThat(body).doesNotContain("Quedamos atentos");
    }

    @Test
    void aFirmAuthoredNoticeIsReproducedVerbatim() {
        String notice = "Apreciado(a) consultante:\n\nNo tomamos este asunto.\n\nAtentamente,\nLa firma";

        String body = renderer.renderNonEngagementNotice("Maria Perez", "Firma Ejemplo", "\n\n" + notice + "  \n").text();

        // Nothing inserted, reworded or stripped between the salutation and the signature.
        assertThat(body).isEqualTo("Estimado(a) Maria:\n\n" + notice + "\n\nCordialmente,\n"
                + "Equipo de consultas\nFirma Ejemplo\n\n---\n"
                + "Este mensaje no constituye asesoria legal y no crea una relacion abogado-cliente.\n");
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

    @Test
    void theStatusAnswerListsEachOpenActivityWithItsStatusLine() {
        RenderedEmail mail = renderer.renderStatusAnswer("Maria Perez", "Firma Ejemplo",
                List.of(dated("Audiencia inicial", LocalDate.of(2026, 3, 3))), LocalDate.of(2026, 3, 1));

        assertThat(mail.text()).isEqualTo("""
                Estimado(a) Maria:

                Este es el estado de sus asuntos con nosotros:

                Audiencia inicial
                Programada para el 3 de marzo de 2026.

                Cordialmente,
                Firma Ejemplo
                """);
    }

    // --- Client forward and Activity nudge cards (#92) ---

    @Test
    void theClientForwardCardShowsWhoWroteWhyAndWhatAndThatAReplyGoesToTheClient() {
        RenderedEmail mail = renderer.renderClientForward("Acme SAS", "gc@acme.co", "El cliente escribió sobre algo que requiere a un abogado.",
                "Estado del contrato", "Hola <b>Ana</b>,\n¿cómo va?");

        for (String part : List.of(HtmlUtils.htmlUnescape(mail.html()), mail.text())) {
            assertThat(part).contains("Acme SAS le escribió")
                    .contains("gc@acme.co")
                    .contains("Estado del contrato")
                    .contains("El cliente escribió sobre algo que requiere a un abogado.")
                    .contains("Responda a este correo para contestarle directamente al cliente.");
        }
        assertThat(mail.html()).contains("Hola &lt;b&gt;Ana&lt;/b&gt;,\n¿cómo va?").doesNotContain("<b>Ana")
                .contains("white-space:pre-line");
        assertThat(mail.text()).contains("Hola <b>Ana</b>,\n¿cómo va?");
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void aClientForwardWithNoSubjectOrBodySaysSo() {
        RenderedEmail mail = renderer.renderClientForward("Acme SAS", "gc@acme.co", "Motivo.", null, "  ");

        assertThat(mail.text()).contains("Asunto: (sin asunto)").contains("(sin contenido)");
        assertThat(mail.html()).contains("(sin asunto)").contains("(sin contenido)");
        CardParity.assertPlaintextCarriesTheCard(mail);
    }

    @Test
    void eachActivityNudgeCardCarriesItsOwnLabelSubjectAndSentenceAndLinksToTheActivity() {
        String link = "https://x.co/dashboard?client=c-1&activity=a-1";
        Map<EmailTemplateRenderer.ActivityNudge, List<String>> expected = Map.of(
                EmailTemplateRenderer.ActivityNudge.OVERDUE, List.of("Vencida", "Actividad vencida",
                        "La actividad \"Contestación\" del cliente Acme SAS venció el 3 de marzo de 2026 y sigue abierta.",
                        "Si ya terminó, márquela como terminada; si no, actualice su fecha."),
                EmailTemplateRenderer.ActivityNudge.TERM_PASSED, List.of("Término vencido", "Termino legal vencido",
                        "El término legal de \"Contestación\" del cliente Acme SAS terminó el 3 de marzo de 2026 y no hay decisión registrada.",
                        "Puede ser momento de actuar, por ejemplo con un incidente de desacato."),
                EmailTemplateRenderer.ActivityNudge.HELD_BACK, List.of("Consulta retenida", "Consulta de estado retenida",
                        "Acme SAS preguntó por el estado de su trabajo y no se le respondió automáticamente porque \"Contestación\" está vencida desde el 3 de marzo de 2026.",
                        "El correo del cliente se le reenvió a su abogado responsable."));

        expected.forEach((nudge, copy) -> {
            RenderedEmail mail = renderer.renderActivityNudge(nudge, "Contestación", "Acme SAS", LocalDate.of(2026, 3, 3), link);

            assertThat(nudge.subject()).isEqualTo(copy.get(1));
            for (String part : List.of(HtmlUtils.htmlUnescape(mail.html()), mail.text())) {
                assertThat(part).as(nudge.name()).contains(copy).contains("Contestación").contains("Acme SAS")
                        .contains("Nada cambia hasta que alguien actualice la actividad en la consola.");
            }
            assertThat(mail.html()).contains("href=\"https://x.co/dashboard?client=c-1&amp;activity=a-1\"").contains("LegalGate");
            assertThat(mail.text()).contains(link);
            CardParity.assertPlaintextCarriesTheCard(mail);
        });
    }

    static Activity dated(String label, LocalDate on) {
        return new Activity("activity-1", "tenant-a", "client-1", "type-1", "Audiencia", ActivityKind.DATED, label,
                on.minusDays(10), null, null, on, null, "lawyer-1", false, null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private String salutationFor(String clientName) {
        return renderer.renderDiagnosticsQuestion(clientName, "Firma Ejemplo", "su despido", "¿Fecha?").text().split("\n")[0];
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
