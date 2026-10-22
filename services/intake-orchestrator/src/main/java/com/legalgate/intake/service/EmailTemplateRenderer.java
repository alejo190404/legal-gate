package com.legalgate.intake.service;

import com.legalgate.intake.clients.ClientsModels;
import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Owns every outbound email body: each render returns the plaintext part and, where the type has
 * one yet, the HTML card in the House Style (ADR-0009). Merge fields are flat {@code {{key}}}
 * tokens, so a plain string replace over an escaped value map is enough — no template engine.
 */
@Component
public class EmailTemplateRenderer {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d", ES);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", ES);
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy", ES);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", ES);
    private static final DateTimeFormatter ZONE = DateTimeFormatter.ofPattern("zzz", ES);
    private static final DateTimeFormatter LAWYER_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", ES);

    /** What the receipt names when no lawyer is on the Event yet — never a LegalGate one. */
    static final String UNASSIGNED_LAWYER = "Por asignar";

    private static final String NEUTRAL_SALUTATION = "Estimado(a):";
    private static final String NEUTRAL_ACKNOWLEDGMENT = "Recibimos su mensaje.";
    /** The lead-in {@link #acknowledgmentLine} supplies itself, as a model sometimes echoes it back. */
    private static final Pattern ECHOED_LEAD_IN = Pattern.compile(
            "^recibimos\\s+su\\s+mensaje\\b[\\s:,.]*(?:sobre\\b[\\s:,.]*)?", Pattern.CASE_INSENSITIVE);
    private static final String DIAGNOSTICS_QUESTION_SUBJECT = "Necesitamos algunos datos para revisar su consulta";
    private static final String NON_ENGAGEMENT_SUBJECT = "Sobre su consulta";
    private static final String DISCLAIMER =
            "Este mensaje no constituye asesoría legal y no crea una relación abogado-cliente.";

    private final String lawyerTemplate;
    private final String clientTemplate;
    private final String diagnosticsQuestionTemplate;
    private final String nonEngagementTemplate;

    public EmailTemplateRenderer() {
        this.lawyerTemplate = load("templates/email/lawyer-scheduled.html");
        this.clientTemplate = load("templates/email/client-scheduled.html");
        this.diagnosticsQuestionTemplate = load("templates/email/diagnostics-question.html");
        this.nonEngagementTemplate = load("templates/email/non-engagement-notice.html");
    }

    RenderedEmail renderLawyer(ConsultationResponse consultation, EventResponse event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("matter_id", matterId(consultation.id()));
        fields.put("client_name", nullToEmpty(consultation.clientName()));
        fields.put("client_email", nullToEmpty(consultation.clientEmail()));
        fields.put("route", nullToEmpty(event.routeName()));
        fields.put("urgency", nullToEmpty(event.urgencyName()));
        fields.put("datetime", lawyerDateTime(event.scheduledStart(), event.scheduledEnd()));
        fields.put("summary", nullToEmpty(consultation.summary()));
        return new RenderedEmail(lawyerText(consultation, event), render(lawyerTemplate, fields));
    }

    private String lawyerText(ConsultationResponse consultation, EventResponse event) {
        return """
                LegalGate agendo una consulta.

                Cliente: %s <%s>
                Ruta: %s
                Urgencia: %s
                SLA: %s
                Hora: %s - %s
                En SLA: %s
                %s
                Resumen:
                %s
                """.formatted(
                consultation.clientName(),
                consultation.clientEmail(),
                event.routeName(),
                event.urgencyName(),
                event.slaDeadline(),
                event.scheduledStart(),
                event.scheduledEnd(),
                Boolean.TRUE.equals(event.scheduledWithinSla()) ? "si" : "no",
                event.meetingUrl() == null ? "" : "Meeting: " + event.meetingUrl(),
                consultation.summary()
        ).trim();
    }

    /**
     * The scheduling receipt a potential client gets. It carries the firm's name and no LegalGate
     * mark of any kind: the firm is who they wrote to, and LegalGate is a supplier they have never
     * heard of. {@code firmName} is the firm's display name with no fallback; a tenant without one
     * gets an empty header band, as with signatures (ADR-0009).
     */
    RenderedEmail renderClient(ConsultationResponse consultation, EventResponse event, String firmName) {
        ZonedDateTime start = event.scheduledStart() == null ? null : ZonedDateTime.ofInstant(event.scheduledStart(), BUSINESS_ZONE);
        ZonedDateTime end = event.scheduledEnd() == null ? null : ZonedDateTime.ofInstant(event.scheduledEnd(), BUSINESS_ZONE);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("firm_name", nullToEmpty(firmName).trim());
        fields.put("client_first_name", firstName(consultation.clientName()));
        fields.put("date_day", start == null ? "" : start.format(DAY));
        fields.put("date_month", start == null ? "" : start.format(MONTH));
        fields.put("date_year", start == null ? "" : start.format(YEAR));
        fields.put("time_range", timeRange(start, end));
        fields.put("timezone", start == null ? "" : start.format(ZONE));
        fields.put("duration", durationLabel(event.scheduledStart(), event.scheduledEnd()));
        fields.put("lawyer_name", firstNonBlank(event.lawyerDisplayName(), UNASSIGNED_LAWYER));
        fields.put("summary", nullToEmpty(consultation.summary()));
        return new RenderedEmail(clientText(consultation, event, firmName), render(clientTemplate, fields));
    }

    private String clientText(ConsultationResponse consultation, EventResponse event, String firmName) {
        String firm = nullToEmpty(firmName).trim();
        return """
                %s

                Hora: %s - %s
                Abogado: %s <%s>
                %s
                Resumen:
                %s

                Este correo es automatico: no podemos leer lo que respondas aqui. Si necesitas
                algo, escribenos al correo de siempre y con gusto te ayudamos.
                """.formatted(
                firm.isEmpty() ? "Tu consulta fue agendada." : "Tu consulta con " + firm + " fue agendada.",
                event.scheduledStart(),
                event.scheduledEnd(),
                firstNonBlank(event.lawyerDisplayName(), UNASSIGNED_LAWYER),
                event.lawyerEmail(),
                event.meetingUrl() == null ? "" : "Meeting: " + event.meetingUrl(),
                consultation.summary()
        ).trim();
    }

    /**
     * The Diagnostics question as firm correspondence: a House Style card in the Firm Voice, with
     * every fixed sentence supplied here rather than by the model (ADR-0004, ADR-0009). The model
     * contributes the question and the Acknowledgment and nothing else, so no generation can put a
     * sentence of its own into a firm's first contact with a stranger.
     *
     * <p>{@code acknowledgment} is what the potential client wrote about, in their own terms; the
     * caller passes null when the model gave nothing usable and the neutral receipt is used instead.
     */
    RenderedEmail renderDiagnosticsQuestion(String clientName, String firmName, String acknowledgment, String question) {
        String salutation = salutation(clientName);
        String acknowledgmentLine = acknowledgmentLine(acknowledgment);
        String asked = nullToEmpty(question).trim();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("firm_name", nullToEmpty(firmName).trim());
        fields.put("salutation", salutation);
        fields.put("acknowledgment_line", acknowledgmentLine);
        fields.put("question", asked);
        String text = "Revisión de su consulta\n"
                + "Necesitamos algunos datos\n"
                + "\n"
                + salutation + "\n"
                + "\n"
                + acknowledgmentLine + "\n"
                + "\n"
                + "Lo que necesitamos:\n"
                + "\n"
                + asked + "\n"
                + "\n"
                + "Quedamos atentos a su respuesta.\n"
                + "\n"
                + signOff(firmName, DISCLAIMER + " Puede responder directamente a este correo.");
        return new RenderedEmail(text, render(diagnosticsQuestionTemplate, fields));
    }

    /**
     * The Non-Engagement Notice in the same envelope: salutation, signature, disclaimer. The notice
     * itself is the letter's body and goes out verbatim — a firm that wrote a complete letter into
     * the field must not end up with two greetings, so nothing here reads, rewords or strips it.
     */
    RenderedEmail renderNonEngagementNotice(String clientName, String firmName, String notice) {
        String salutation = salutation(clientName);
        String body = nullToEmpty(notice).strip();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("firm_name", nullToEmpty(firmName).trim());
        fields.put("salutation", salutation);
        fields.put("non_engagement_notice", body);
        String text = "Respuesta a su consulta\n"
                + "Sobre su consulta\n"
                + "\n"
                + salutation + "\n"
                + "\n"
                + body + "\n"
                + "\n"
                // No "quedamos atentos": the firm is declining, not inviting a reply.
                + signOff(firmName, DISCLAIMER);
        return new RenderedEmail(text, render(nonEngagementTemplate, fields));
    }

    /**
     * The receipt a Client gets when their email goes to a lawyer. Firm Voice, every sentence fixed
     * (ADR-0007). A Client is represented, so unlike a potential client's letters this carries no
     * not-legal-advice disclaimer and is signed by the firm, not its intake team.
     *
     * @param salutationName the Client's name for a person, or null for an organization
     */
    public RenderedEmail renderClientReceipt(String salutationName, String firmName) {
        return new RenderedEmail(salutation(salutationName) + "\n"
                + "\n"
                + "Recibimos su mensaje y lo trasladamos a su abogado(a), quien le respondera directamente.\n"
                + "\n"
                + clientSignOff(firmName), null);
    }

    /**
     * The automatic answer to a Client's Status Inquiry: fixed sentences filled from the record and
     * nothing else, so no model ever states anything about a Client's matter (ADR-0007). Dates are
     * Bogota's; {@code today} is the day the answer reflects.
     *
     * @param salutationName the Client's name for a person, or null for an organization
     */
    public RenderedEmail renderStatusAnswer(String salutationName, String firmName, List<Activity> open, LocalDate today) {
        StringBuilder text = new StringBuilder(salutation(salutationName))
                .append("\n\nEste es el estado de sus asuntos con nosotros:\n");
        for (Activity activity : open) {
            text.append("\n").append(activity.displayName()).append("\n").append(statusLine(activity, today)).append("\n");
        }
        return new RenderedEmail(text.append("\n").append(clientSignOff(firmName)).toString(), null);
    }

    private static String statusLine(Activity activity, LocalDate today) {
        LocalDate expected = activity.expectedDate();
        return switch (activity.kind()) {
            case TURNAROUND -> "Inicio: " + ClientsModels.spanishDate(activity.startDate()) + ". Han transcurrido "
                    + days(Math.max(0, BusinessDays.between(activity.startDate(), today))) + " y faltan "
                    + Math.max(0, BusinessDays.between(today, expected)) + ". Fecha estimada de entrega: "
                    + ClientsModels.spanishDate(expected) + ".";
            case DATED -> "Programada para el " + ClientsModels.spanishDate(expected) + ".";
            case EXTERNAL -> expected == null
                    ? "Estamos a la espera de la decision del juzgado o la autoridad, que no tiene un termino fijo."
                    : today.isAfter(expected)
                            ? "El termino legal para la decision vencio el " + ClientsModels.spanishDate(expected)
                                    + " y estamos atentos a la respuesta del juzgado o la autoridad."
                            : "Estamos a la espera de la decision del juzgado o la autoridad. El termino legal vence el "
                                    + ClientsModels.spanishDate(expected) + ".";
        };
    }

    private static String days(int count) {
        return count == 1 ? "1 dia habil" : count + " dias habiles";
    }

    /**
     * A Client's email passed to their Responsible Lawyer, who replies straight to the Client.
     * Staff mail, but the Client sees it quoted in that reply (ADR-0009).
     */
    public RenderedEmail renderClientForward(String clientName, String contactAddress, String why, String subject,
            String body) {
        String text = """
                LegalGate le reenvia un correo de su cliente %s <%s>.
                Motivo: %s
                Responda a este correo para contestarle directamente al cliente.

                Asunto: %s

                %s
                """.formatted(clientName, contactAddress, why,
                trimmedOr(subject, "(sin asunto)"), trimmedOr(body, "(sin contenido)")).trim();
        return new RenderedEmail(text, null);
    }

    /** The three reminders a lawyer gets about stale work, each with its subject and its sentence. */
    public enum ActivityNudge {
        OVERDUE("Actividad vencida", """
                La actividad "%s" del cliente %s vencio el %s y sigue abierta.
                Si ya termino, marquela como terminada; si no, actualice su fecha."""),
        TERM_PASSED("Termino legal vencido", """
                El termino legal de "%s" del cliente %s termino el %s y no hay decision registrada.
                Puede ser momento de actuar, por ejemplo con un incidente de desacato."""),
        HELD_BACK("Consulta de estado retenida", """
                %2$s pregunto por el estado de su trabajo y LegalGate no respondio porque "%1$s" esta vencida desde el %3$s.
                El correo del cliente se le reenvio a su abogado responsable. Cierre la actividad o actualice su fecha.""");

        private final String subject;
        private final String sentence;

        ActivityNudge(String subject, String sentence) {
            this.subject = subject;
            this.sentence = sentence;
        }

        public String subject() {
            return subject;
        }
    }

    /** A reminder to a lawyer: LegalGate-branded, and nothing in it changes state. */
    public RenderedEmail renderActivityNudge(ActivityNudge nudge, String activityName, String clientName, LocalDate date,
            String consoleLink) {
        String text = "LegalGate:\n\n"
                + nudge.sentence.formatted(activityName, clientName, ClientsModels.spanishDate(date)) + "\n\n"
                + "Ver en la consola: " + consoleLink + "\n\n"
                + "Este correo es informativo: nada cambia hasta que alguien actualice la actividad en la consola.";
        return new RenderedEmail(text, null);
    }

    /** Client mail stays on the Client's own subject line, as a reply to it. */
    public String clientReplySubject(String originalSubject) {
        return replySubject(originalSubject, "Su mensaje");
    }

    /** How a letter to a Client ends: the firm's own name, or nothing — never LegalGate. */
    public String clientSignOff(String firmName) {
        return "Cordialmente,\n" + signatureFirmLine(firmName);
    }

    /** Keeps the Diagnostics question on the potential client's own subject line, as a reply to it. */
    String diagnosticsQuestionSubject(String originalSubject) {
        return replySubject(originalSubject, DIAGNOSTICS_QUESTION_SUBJECT);
    }

    /** The notice stays in the Consultation Thread, on the same subject line as everything else. */
    String nonEngagementSubject(String originalSubject) {
        return replySubject(originalSubject, NON_ENGAGEMENT_SUBJECT);
    }

    private String replySubject(String originalSubject, String fallback) {
        String subject = nullToEmpty(originalSubject).trim();
        if (subject.isEmpty()) {
            return fallback;
        }
        return subject.regionMatches(true, 0, "re:", 0, 3) ? subject : "Re: " + subject;
    }

    /**
     * The Acknowledgment: a receipt of the message, never a characterization of the matter. The
     * restatement is the model's, held to the potential client's own words; anything missing falls
     * back to the bare receipt, which says less but can never say something wrong.
     */
    private String acknowledgmentLine(String acknowledgment) {
        String subject = restatement(acknowledgment);
        if (subject.isEmpty()) {
            return NEUTRAL_ACKNOWLEDGMENT;
        }
        // The model punctuating its own restatement must not end the sentence twice; anything it
        // wrote is otherwise left alone, abbreviations and all.
        return "Recibimos su mensaje sobre " + subject + (subject.endsWith(".") ? "" : ".");
    }

    /**
     * What the potential client wrote about, with any echoed lead-in removed: the model is asked to
     * complete "Recibimos su mensaje sobre ..." and sometimes returns the whole sentence instead.
     * Stripped here rather than at the caller so the line can only ever carry one lead-in, and so
     * the length the Acknowledgment is held to measures the restatement and not the echo.
     */
    static String restatement(String acknowledgment) {
        return ECHOED_LEAD_IN.matcher(nullToEmpty(acknowledgment).trim()).replaceFirst("").trim();
    }

    /**
     * A name we cannot vouch for is worse in a salutation than no name at all: an email address or
     * a bare lowercase handle in "Estimado ..." reads as a mail merge, which is what this avoids.
     */
    public String salutation(String clientName) {
        String name = nullToEmpty(clientName).trim();
        String first = firstName(name);
        boolean trustworthy = !first.isEmpty()
                && !name.equalsIgnoreCase(IntakeService.UNKNOWN_CLIENT)
                && !name.contains("@")
                // An all-lowercase token is a mail handle someone typed, not a name they sign with.
                && !first.equals(first.toLowerCase(ES));
        // Gendered, and the firm knows nothing about the person: "Estimado(a)" either way.
        return trustworthy ? "Estimado(a) " + first + ":" : NEUTRAL_SALUTATION;
    }

    private String render(String template, Map<String, String> fields) {
        String rendered = template;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            rendered = rendered.replace("{{" + field.getKey() + "}}", htmlEscape(field.getValue()));
        }
        return rendered;
    }

    private String matterId(String consultationId) {
        if (consultationId == null || consultationId.isBlank()) {
            return "";
        }
        String compact = consultationId.replace("-", "");
        return compact.substring(0, Math.min(8, compact.length())).toUpperCase(Locale.ROOT);
    }

    private String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        return fullName.trim().split("\\s+")[0];
    }

    /** How every plaintext letter to a potential client ends: same signature, then the card's notice block. */
    private String signOff(String firmName, String notice) {
        return "Cordialmente,\n"
                // Never a lawyer: during Diagnostics nobody has been assigned or read the matter.
                + "Equipo de consultas\n"
                + signatureFirmLine(firmName)
                + "\n"
                + "---\n"
                + "Aviso: " + notice + "\n";
    }

    /** The firm's own name, or nothing: a signature must never leak LegalGate to a potential client. */
    private String signatureFirmLine(String firmName) {
        String firm = nullToEmpty(firmName).trim();
        return firm.isEmpty() ? "" : firm + "\n";
    }

    private String lawyerDateTime(Instant start, Instant end) {
        if (start == null) {
            return "";
        }
        ZonedDateTime zonedStart = ZonedDateTime.ofInstant(start, BUSINESS_ZONE);
        String base = zonedStart.format(LAWYER_DATE) + ", " + zonedStart.format(TIME);
        if (end != null) {
            base += "–" + ZonedDateTime.ofInstant(end, BUSINESS_ZONE).format(TIME);
        }
        return base + " (" + zonedStart.format(ZONE) + ")";
    }

    private String timeRange(ZonedDateTime start, ZonedDateTime end) {
        if (start == null) {
            return "";
        }
        return end == null ? start.format(TIME) : start.format(TIME) + "–" + end.format(TIME);
    }

    private String durationLabel(Instant start, Instant end) {
        if (start == null || end == null) {
            return "";
        }
        long minutes = Math.max(0, Duration.between(start, end).toMinutes());
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static String trimmedOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String htmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    private static String load(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load email template: " + path, ex);
        }
    }
}
