package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;

class EmailTemplateRendererTests {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void lawyerTemplateFillsFieldsAndEscapes() {
        String html = renderer.renderLawyer(consultation(), event());

        assertThat(html).contains("A1B2C3D4");                 // matter_id: first 8 compact chars, uppercased
        assertThat(html).contains("Juan &amp; Co Pérez"); // client_name with & escaped
        assertThat(html).contains("Tutela");                   // route
        assertThat(html).contains("URGENTE");                   // urgency
        assertThat(html).doesNotContain("{{");                 // every merge field substituted
    }

    @Test
    void clientTemplateFormatsDateAndEscapesSummary() {
        String html = renderer.renderClient(consultation(), event());

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

        String html = renderer.renderClient(consultation(), openEnded);

        assertThat(html).doesNotContain("{{");
    }

    @Test
    void diagnosticsQuestionIsFirmCorrespondenceAroundTheModelsQuestion() {
        String body = renderer.renderDiagnosticsQuestion(
                "Alejandro Barragán", "Firma Ejemplo", "  ¿Cual fue la fecha del despido?  ");

        assertThat(body).startsWith("Estimado(a) Alejandro:\n\n");
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
    void diagnosticsQuestionNeverSignsWithALawyer() {
        String body = renderer.renderDiagnosticsQuestion("Maria Perez", "Firma Ejemplo", "¿Tiene el contrato?");

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
        String body = renderer.renderDiagnosticsQuestion("Maria Perez", null, "¿Fecha?");

        assertThat(body).contains("Cordialmente,\nEquipo de consultas\n\n---");
        assertThat(body).doesNotContain("LegalGate");
    }

    @Test
    void questionSubjectRepliesOnTheClientsOwnSubjectLine() {
        assertThat(renderer.diagnosticsQuestionSubject("  Consulta laboral ")).isEqualTo("Re: Consulta laboral");
        assertThat(renderer.diagnosticsQuestionSubject("Re: Consulta laboral")).isEqualTo("Re: Consulta laboral");
        // A blank subject must never go out as a bare "Re:".
        assertThat(renderer.diagnosticsQuestionSubject("  ")).isEqualTo("Necesitamos algunos datos para revisar su consulta");
        assertThat(renderer.diagnosticsQuestionSubject(null)).isEqualTo("Necesitamos algunos datos para revisar su consulta");
    }

    private String salutationFor(String clientName) {
        return renderer.renderDiagnosticsQuestion(clientName, "Firma Ejemplo", "¿Fecha?").split("\n")[0];
    }

    private ConsultationResponse consultation() {
        return new ConsultationResponse(
                "a1b2c3d4-0000-4000-8000-000000000000", "tenant-a",
                "Juan & Co Pérez", "juan@example.com",
                "<b>urgente</b> & grave", "manana", "SCHEDULED", "URGENTE",
                "GENERAL", "ana@firm.co", null, null, null, null, Instant.parse("2026-07-01T12:00:00Z"));
    }

    private EventResponse event() {
        return new EventResponse(
                "event-1", "lawyer-1", "Ana Abogada", "ana@firm.co",
                "Tutela", "URGENTE", 1, Instant.parse("2026-07-03T00:00:00Z"), 100,
                Instant.parse("2026-07-02T19:00:00Z"), Instant.parse("2026-07-02T19:45:00Z"),
                "SCHEDULED", "SYSTEM");
    }
}
