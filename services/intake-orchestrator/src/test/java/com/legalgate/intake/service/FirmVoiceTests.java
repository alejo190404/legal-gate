package com.legalgate.intake.service;

import static com.legalgate.intake.service.EmailTemplateRendererTests.consultation;
import static com.legalgate.intake.service.EmailTemplateRendererTests.dated;
import static com.legalgate.intake.service.EmailTemplateRendererTests.event;
import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Firm Voice guard: no mail a client can read says LegalGate, in its card or its plaintext,
 * for a firm with a display name or without one (ADR-0009). Every client-facing type is listed in
 * {@link #CLIENT_FACING}; a new one is added there as one line.
 */
class FirmVoiceTests {

    private static final EmailTemplateRenderer RENDERER = new EmailTemplateRenderer();
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    /** Each client-facing type, rendered with representative inputs for the given firm name (null for none). */
    private static final List<Type> CLIENT_FACING = List.of(
            new Type("client scheduled", firm -> RENDERER.renderClient(consultation(), event(), firm)),
            new Type("Diagnostics question", firm -> RENDERER.renderDiagnosticsQuestion(
                    "Maria Perez", firm, "su despido", "¿Cuál fue la fecha del despido?")),
            new Type("Non-Engagement Notice", firm -> RENDERER.renderNonEngagementNotice(
                    "Maria Perez", firm, DiagnosticsService.DEFAULT_NON_ENGAGEMENT_NOTICE)),
            new Type("client receipt", firm -> RENDERER.renderClientReceipt("Maria Perez", firm)),
            new Type("status answer", firm -> RENDERER.renderStatusAnswer(null, firm, List.of(
                    dated("Audiencia inicial", TODAY.plusDays(5)),
                    activity(ActivityKind.TURNAROUND, 15, null),
                    activity(ActivityKind.EXTERNAL, null, 10),
                    activity(ActivityKind.EXTERNAL, null, null)), TODAY)),
            // Staff mail, but the lawyer's reply quotes it to the Client; it has no firm name to vary.
            new Type("client forward", firm -> RENDERER.renderClientForward("Acme SAS", "gc@acme.co",
                    "No se pudo determinar de qué trata el correo, así que no se respondió automáticamente.",
                    "Estado del contrato", "¿Cómo va mi contrato?")));

    record Type(String name, Function<String, RenderedEmail> render) {
    }

    static Stream<Arguments> clientFacingMail() {
        return CLIENT_FACING.stream().flatMap(type -> Stream.of("Vargas & Asociados", null)
                .map(firm -> Arguments.of(type.name() + (firm == null ? ", no display name" : ""), type.render().apply(firm))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientFacingMail")
    void clientMailNeverSaysLegalGate(String type, RenderedEmail mail) {
        assertThat(mail.text()).isNotBlank().doesNotContainIgnoringCase("legalgate");
        if (mail.html() != null) {
            assertThat(mail.html()).doesNotContainIgnoringCase("legalgate");
        }
    }

    private static Activity activity(ActivityKind kind, Integer turnaroundDays, Integer termDays) {
        return new Activity("activity-" + kind, "tenant-a", "client-1", "type-1", "Contrato", kind, null,
                TODAY.minusDays(20), turnaroundDays, null, null, termDays, "lawyer-1", false, null, null, null,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
