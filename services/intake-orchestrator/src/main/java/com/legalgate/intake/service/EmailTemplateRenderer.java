package com.legalgate.intake.service;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Renders the outbound consultation emails: rich HTML from the classpath templates for the
 * transactional notices, plaintext for the conversational mail a potential client receives
 * (ADR 0004). Merge fields are flat {@code {{key}}} tokens, so a plain string replace over an
 * escaped value map is enough — no template engine dependency.
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
    private static final String DIAGNOSTICS_QUESTION_SUBJECT = "Necesitamos algunos datos para revisar su consulta";
    private static final String NON_ENGAGEMENT_SUBJECT = "Sobre su consulta";
    private static final String DISCLAIMER =
            "Este mensaje no constituye asesoria legal y no crea una relacion abogado-cliente.\n";

    private final String lawyerTemplate;
    private final String clientTemplate;

    public EmailTemplateRenderer() {
        this.lawyerTemplate = load("templates/email/lawyer-scheduled.html");
        this.clientTemplate = load("templates/email/client-scheduled.html");
    }

    String renderLawyer(ConsultationResponse consultation, EventResponse event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("matter_id", matterId(consultation.id()));
        fields.put("client_name", nullToEmpty(consultation.clientName()));
        fields.put("client_email", nullToEmpty(consultation.clientEmail()));
        fields.put("route", nullToEmpty(event.routeName()));
        fields.put("urgency", nullToEmpty(event.urgencyName()));
        fields.put("datetime", lawyerDateTime(event.scheduledStart(), event.scheduledEnd()));
        fields.put("summary", nullToEmpty(consultation.summary()));
        return render(lawyerTemplate, fields);
    }

    /**
     * The scheduling receipt a potential client gets. It carries the firm's name and no LegalGate
     * mark of any kind (ADR 0004): the firm is who they wrote to, and LegalGate is a supplier they
     * have never heard of. {@code firmName} is resolved by the caller with the sender-name fallback,
     * unlike a signature: a card whose header is blank is worse than one naming the same sender the
     * message already arrives from, and a tenant with no display name has none to show.
     */
    String renderClient(ConsultationResponse consultation, EventResponse event, String firmName) {
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
        return render(clientTemplate, fields);
    }

    /**
     * The Diagnostics question as firm correspondence: plaintext, in the Firm Voice, with every
     * fixed sentence supplied here rather than by the model (ADR 0004). The model contributes the
     * question and the Acknowledgment and nothing else, so no generation can put a sentence of its
     * own into a firm's first contact with a stranger.
     *
     * <p>{@code acknowledgment} is what the potential client wrote about, in their own terms; the
     * caller passes null when the model gave nothing usable and the neutral receipt is used instead.
     */
    String renderDiagnosticsQuestion(String clientName, String firmName, String acknowledgment, String question) {
        return salutation(clientName) + "\n"
                + "\n"
                + acknowledgmentLine(acknowledgment) + "\n"
                + "\n"
                + "Para poder revisar su consulta necesitamos algunos datos adicionales:\n"
                + "\n"
                + nullToEmpty(question).trim() + "\n"
                + "\n"
                + "Quedamos atentos a su respuesta.\n"
                + "\n"
                + signOff(firmName);
    }

    /**
     * The Non-Engagement Notice in the same envelope: salutation, signature, disclaimer. The notice
     * itself is the letter's body and goes out verbatim — a firm that wrote a complete letter into
     * the field must not end up with two greetings, so nothing here reads, rewords or strips it.
     */
    String renderNonEngagementNotice(String clientName, String firmName, String notice) {
        return salutation(clientName) + "\n"
                + "\n"
                + nullToEmpty(notice).strip() + "\n"
                + "\n"
                // No "quedamos atentos": the firm is declining, not inviting a reply.
                + signOff(firmName);
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
        String subject = nullToEmpty(acknowledgment).trim();
        if (subject.isEmpty()) {
            return NEUTRAL_ACKNOWLEDGMENT;
        }
        // The model punctuating its own restatement must not end the sentence twice; anything it
        // wrote is otherwise left alone, abbreviations and all.
        return "Recibimos su mensaje sobre " + subject + (subject.endsWith(".") ? "" : ".");
    }

    /**
     * A name we cannot vouch for is worse in a salutation than no name at all: an email address or
     * a bare lowercase handle in "Estimado ..." reads as a mail merge, which is what this avoids.
     */
    private String salutation(String clientName) {
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

    /** How every plaintext letter to a potential client ends: same signature, same disclaimer. */
    private String signOff(String firmName) {
        return "Cordialmente,\n"
                // Never a lawyer: during Diagnostics nobody has been assigned or read the matter.
                + "Equipo de consultas\n"
                + signatureFirmLine(firmName)
                + "\n"
                + "---\n"
                + DISCLAIMER;
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

    private String nullToEmpty(String value) {
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
