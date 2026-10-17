package com.legalgate.intake.clients;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.legalgate.intake.service.BusinessDays;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The Clients context's records; vocabulary in clients/CONTEXT.md. */
public final class ClientsModels {

    public static final String PERSON = "PERSON";
    public static final String ORGANIZATION = "ORGANIZATION";

    /** What LegalGate did with a Client's email. */
    public static final String ANSWERED = "ANSWERED";
    public static final String WOULD_HAVE_ANSWERED = "WOULD_HAVE_ANSWERED";
    public static final String FORWARDED = "FORWARDED";
    public static final String HELD_BACK = "HELD_BACK";
    public static final String IGNORED = "IGNORED";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"));

    private ClientsModels() {
    }

    /** How an Activity's expected date is set: the firm's Turnaround, a fixed day, or an authority's term. */
    public enum ActivityKind { TURNAROUND, DATED, EXTERNAL }

    /** A date as Client and lawyer mail write it, e.g. "3 de marzo de 2026"; empty for none. */
    static String spanishDate(LocalDate date) {
        return date == null ? "" : date.format(DATE);
    }

    /** {@code openActivities} is read-only: counted on read, never written back. */
    public record Client(
            String id,
            String name,
            String kind,
            String idNumber,
            String phone,
            String notes,
            String responsibleLawyerId,
            List<String> contactAddresses,
            int openActivities,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ClientRequest(
            String name,
            String kind,
            String idNumber,
            String phone,
            String notes,
            String responsibleLawyerId,
            List<String> contactAddresses
    ) {
    }

    /**
     * One line of the Activity History. The subject is the Activity when {@code activityId} is set, else the Client.
     * {@code actorName} is who {@code actorUserId} is, resolved when the History is read; never stored.
     */
    public record HistoryEntry(
            String id,
            String clientId,
            String activityId,
            String field,
            String oldValue,
            String newValue,
            String actorUserId,
            Instant createdAt,
            String actorName
    ) {
        public static HistoryEntry of(String clientId, String activityId, String field, String oldValue,
                String newValue, String actorUserId) {
            return new HistoryEntry(null, clientId, activityId, field, oldValue, newValue, actorUserId, null, null);
        }

        public HistoryEntry withActorName(String name) {
            return new HistoryEntry(id, clientId, activityId, field, oldValue, newValue, actorUserId, createdAt, name);
        }
    }

    /** A kind of work in the firm's own catalog. Retired, never removed, once work of the type exists. */
    public record ActivityType(
            String id,
            String name,
            ActivityKind kind,
            Integer defaultTurnaroundDays,
            Integer defaultTermDays,
            Instant retiredAt
    ) {
    }

    public record ActivityTypeRequest(String name, String kind, Integer defaultTurnaroundDays, Integer defaultTermDays) {
    }

    /**
     * One email a Client sent, and what LegalGate did about it. {@code outcome} is null while the
     * worker has yet to act; {@code body} is null once purged. {@code tenantId} is the tenant slug.
     */
    public record Correspondence(
            String id,
            String tenantId,
            String clientId,
            String contactAddress,
            String messageId,
            String subject,
            String body,
            Instant receivedAt,
            String intent,
            String outcome,
            String answerText,
            Instant purgedAt,
            int attempts,
            Instant nextAttemptAt
    ) {
        public Correspondence resolved(String intent, String outcome, String answerText) {
            return new Correspondence(id, tenantId, clientId, contactAddress, messageId, subject, body, receivedAt,
                    intent, outcome, answerText, purgedAt, attempts, null);
        }

        public Correspondence retrying(Instant next) {
            return new Correspondence(id, tenantId, clientId, contactAddress, messageId, subject, body, receivedAt,
                    intent, outcome, answerText, purgedAt, attempts + 1, next);
        }

        public Correspondence withNextAttemptAt(Instant next) {
            return new Correspondence(id, tenantId, clientId, contactAddress, messageId, subject, body, receivedAt,
                    intent, outcome, answerText, purgedAt, attempts, next);
        }

        /** What the Client wrote is gone; what LegalGate did stays. */
        public Correspondence purged(Instant at) {
            return new Correspondence(id, tenantId, clientId, contactAddress, messageId, subject, null, receivedAt,
                    intent, outcome, answerText, at, attempts, nextAttemptAt);
        }
    }

    /** Engage a Consultation into the existing Client {@code clientId}, or into a new one from {@code client}. */
    public record EngagementRequest(String clientId, ClientRequest client) {
    }

    /** A Consultation's Engagement: the Client it became, or a draft prefilled from it when not yet engaged. */
    public record Engagement(String consultationId, String clientId, ClientRequest draft) {
    }

    /**
     * One piece of work for one Client. Its kind and Turnaround are snapshotted when it is opened,
     * so the firm's catalog never moves a promise already made. {@code overdueNudgedFor} and
     * {@code termNudgedFor} hold the date a reminder was sent for; each is sent once per Activity.
     */
    public record Activity(
            String id,
            String tenantId,
            String clientId,
            String activityTypeId,
            String typeName,
            ActivityKind kind,
            String label,
            LocalDate startDate,
            Integer turnaroundDays,
            LocalDate dueDate,
            LocalDate scheduledDate,
            Integer termDays,
            String assignedLawyerId,
            boolean done,
            Instant doneAt,
            LocalDate overdueNudgedFor,
            LocalDate termNudgedFor,
            Instant createdAt
    ) {
        /** What the Client reads: the label, else the type's name. */
        @JsonProperty
        public String displayName() {
            return label == null || label.isBlank() ? typeName : label;
        }

        /** Turnaround: the due date, else start plus Turnaround; Dated: its day; External: its term's end, if any. */
        @JsonProperty
        public LocalDate expectedDate() {
            return switch (kind) {
                case TURNAROUND -> dueDate != null ? dueDate : BusinessDays.add(startDate, turnaroundDays);
                case DATED -> scheduledDate;
                case EXTERNAL -> termDays == null ? null : BusinessDays.add(startDate, termDays);
            };
        }

        /** The firm is late, or nobody closed it. Never External: that clock is not the firm's. */
        @JsonProperty
        public boolean overdue() {
            return kind != ActivityKind.EXTERNAL && expectedDatePassed();
        }

        @JsonProperty
        public boolean termPassed() {
            return kind == ActivityKind.EXTERNAL && expectedDatePassed();
        }

        private boolean expectedDatePassed() {
            return !done && expectedDate() != null && BusinessDays.today().isAfter(expectedDate());
        }

        public Activity with(String label, LocalDate startDate, LocalDate dueDate, LocalDate scheduledDate,
                Integer termDays, String assignedLawyerId, boolean done, Instant doneAt) {
            return new Activity(id, tenantId, clientId, activityTypeId, typeName, kind, label, startDate,
                    turnaroundDays, dueDate, scheduledDate, termDays, assignedLawyerId, done, doneAt,
                    overdueNudgedFor, termNudgedFor, createdAt);
        }

        public Activity withTypeName(String typeName) {
            return new Activity(id, tenantId, clientId, activityTypeId, typeName, kind, label, startDate,
                    turnaroundDays, dueDate, scheduledDate, termDays, assignedLawyerId, done, doneAt,
                    overdueNudgedFor, termNudgedFor, createdAt);
        }

        public Activity nudged(ClientsRepository.NudgeTrigger trigger, LocalDate forDate) {
            boolean overdue = trigger == ClientsRepository.NudgeTrigger.OVERDUE;
            return new Activity(id, tenantId, clientId, activityTypeId, typeName, kind, label, startDate,
                    turnaroundDays, dueDate, scheduledDate, termDays, assignedLawyerId, done, doneAt,
                    overdue ? forDate : overdueNudgedFor, overdue ? termNudgedFor : forDate, createdAt);
        }

        /** The reminder for {@code trigger} already went out. */
        public boolean nudgedFor(ClientsRepository.NudgeTrigger trigger) {
            return (trigger == ClientsRepository.NudgeTrigger.OVERDUE ? overdueNudgedFor : termNudgedFor) != null;
        }
    }

    /**
     * Opening or editing an Activity; the type is only read when it is opened. An edit replaces
     * label, due date and term (null clears them), and keeps start, scheduled date and lawyer when null.
     */
    public record ActivityRequest(
            String activityTypeId,
            String label,
            LocalDate startDate,
            LocalDate dueDate,
            LocalDate scheduledDate,
            Integer termDays,
            String assignedLawyerId
    ) {
    }
}
