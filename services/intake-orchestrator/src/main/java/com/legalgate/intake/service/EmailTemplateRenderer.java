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
 * Renders the outbound consultation emails into rich HTML from the classpath templates.
 * Merge fields are flat {@code {{key}}} tokens, so a plain string replace over an escaped
 * value map is enough — no template engine dependency.
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

    String renderClient(ConsultationResponse consultation, EventResponse event) {
        ZonedDateTime start = ZonedDateTime.ofInstant(event.scheduledStart(), BUSINESS_ZONE);
        ZonedDateTime end = ZonedDateTime.ofInstant(event.scheduledEnd(), BUSINESS_ZONE);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("client_first_name", firstName(consultation.clientName()));
        fields.put("date_day", start.format(DAY));
        fields.put("date_month", start.format(MONTH));
        fields.put("date_year", start.format(YEAR));
        fields.put("time_range", start.format(TIME) + "–" + end.format(TIME));
        fields.put("timezone", start.format(ZONE));
        fields.put("duration", durationLabel(event.scheduledStart(), event.scheduledEnd()));
        fields.put("lawyer_name", firstNonBlank(event.lawyerDisplayName(), "Abogado LegalGate"));
        fields.put("summary", nullToEmpty(consultation.summary()));
        return render(clientTemplate, fields);
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
