package com.legalgate.intake.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record ConsultationResponse(
        String id,
        String tenantId,
        String clientName,
        String clientEmail,
        String summary,
        String preferredWindow,
        String status,
        String urgency,
        String consultationType,
        String assignedLawyerEmail,
        ClassificationResult classification,
        NotificationStatus notifications,
        String sourceEventId,
        String sourceMessageId,
        Instant createdAt,
        String eventId,
        EventResponse event,
        /** The diagnostics summary of the matter, when one has been extracted. Read-only: it is
         * joined in from the diagnostics session, never written back with the consultation. */
        String diagnosticsSummary
) {
    public ConsultationResponse(
            String id,
            String tenantId,
            String clientName,
            String clientEmail,
            String summary,
            String preferredWindow,
            String status,
            String urgency,
            String consultationType,
            String assignedLawyerEmail,
            ClassificationResult classification,
            NotificationStatus notifications,
            String sourceEventId,
            String sourceMessageId,
            Instant createdAt
    ) {
        this(id, tenantId, clientName, clientEmail, summary, preferredWindow, status, urgency, consultationType,
                assignedLawyerEmail, classification, notifications, sourceEventId, sourceMessageId, createdAt, null, null, null);
    }

    public ConsultationResponse(
            String id,
            String tenantId,
            String clientName,
            String clientEmail,
            String summary,
            String preferredWindow,
            String status,
            String urgency,
            String consultationType,
            String assignedLawyerEmail,
            ClassificationResult classification,
            NotificationStatus notifications,
            String sourceEventId,
            String sourceMessageId,
            Instant createdAt,
            String eventId,
            EventResponse event
    ) {
        this(id, tenantId, clientName, clientEmail, summary, preferredWindow, status, urgency, consultationType,
                assignedLawyerEmail, classification, notifications, sourceEventId, sourceMessageId, createdAt,
                eventId, event, null);
    }

    /** Every value {@link #stage()} can return. */
    public static final Set<String> STAGES = Set.of(
            "new", "diagnostics", "classified", "scheduled", "confirmed", "unrouted", "rejected", "abandoned");

    /** Where this Consultation stands in the firm's workflow (see the Intake glossary). */
    @JsonProperty(value = "stage", access = JsonProperty.Access.READ_ONLY)
    public String stage() {
        String consultationStatus = status == null ? "" : status.toUpperCase(Locale.ROOT);
        // Diagnostics states are authoritative: these Consultations have no Event by design.
        switch (consultationStatus) {
            case "DIAGNOSTICS_PENDING": return "diagnostics";
            case "DIAGNOSTICS_REJECTED": return "rejected";
            case "DIAGNOSTICS_ABANDONED": return "abandoned";
            default: break;
        }
        if (event != null && event.scheduledStart() != null) {
            String eventStatus = event.status() == null ? "" : event.status().toUpperCase(Locale.ROOT);
            return Boolean.TRUE.equals(event.scheduledWithinSla())
                    || eventStatus.contains("CONFIRM")
                    || consultationStatus.contains("CONFIRM")
                    ? "confirmed"
                    : "scheduled";
        }
        if (event != null && (present(event.lawyerDisplayName()) || present(event.lawyerEmail()))
                || present(assignedLawyerEmail)) {
            return "classified";
        }
        if (classification != null && classification.confidence() != null && classification.confidence() < 0.5) {
            return "unrouted";
        }
        if (consultationStatus.contains("UNROUT") || consultationStatus.contains("FAIL")) {
            return "unrouted";
        }
        return "new";
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    public ConsultationResponse withStatus(String newStatus) {
        return new ConsultationResponse(id, tenantId, clientName, clientEmail, summary, preferredWindow, newStatus,
                urgency, consultationType, assignedLawyerEmail, classification, notifications, sourceEventId,
                sourceMessageId, createdAt, eventId, event, diagnosticsSummary);
    }
}
