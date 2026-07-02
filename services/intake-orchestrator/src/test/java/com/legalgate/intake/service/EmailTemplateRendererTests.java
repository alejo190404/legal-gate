package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmailTemplateRendererTests {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void lawyerTemplateFillsFieldsAndEscapes() {
        String html = renderer.renderLawyer(consultation(), event());

        assertThat(html).contains("A1B2C3D4");                 // matter_id: first 8 compact chars, uppercased
        assertThat(html).contains("Juan &amp; Co Pérez"); // client_name with & escaped
        assertThat(html).contains("Tutela");                   // route
        assertThat(html).contains("URGENT");                   // urgency
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
                "Tutela", "URGENT", 1, null, 100,
                null, null, "SCHEDULED", "SYSTEM");

        String html = renderer.renderClient(consultation(), openEnded);

        assertThat(html).doesNotContain("{{");
    }

    private ConsultationResponse consultation() {
        return new ConsultationResponse(
                "a1b2c3d4-0000-4000-8000-000000000000", "tenant-a",
                "Juan & Co Pérez", "juan@example.com",
                "<b>urgente</b> & grave", "manana", "SCHEDULED", "URGENT",
                "GENERAL", "ana@firm.co", null, null, null, null, Instant.parse("2026-07-01T12:00:00Z"));
    }

    private EventResponse event() {
        return new EventResponse(
                "event-1", "lawyer-1", "Ana Abogada", "ana@firm.co",
                "Tutela", "URGENT", 1, Instant.parse("2026-07-03T00:00:00Z"), 100,
                Instant.parse("2026-07-02T19:00:00Z"), Instant.parse("2026-07-02T19:45:00Z"),
                "SCHEDULED", "SYSTEM");
    }
}
