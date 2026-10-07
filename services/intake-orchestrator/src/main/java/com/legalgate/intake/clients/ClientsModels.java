package com.legalgate.intake.clients;

import java.time.Instant;
import java.util.List;

/** The Clients context's records; vocabulary in clients/CONTEXT.md. */
public final class ClientsModels {

    public static final String PERSON = "PERSON";
    public static final String ORGANIZATION = "ORGANIZATION";

    public static final String TURNAROUND = "TURNAROUND";
    public static final String DATED = "DATED";
    public static final String EXTERNAL = "EXTERNAL";

    /** What LegalGate did with a Client's email. */
    public static final String ANSWERED = "ANSWERED";
    public static final String WOULD_HAVE_ANSWERED = "WOULD_HAVE_ANSWERED";
    public static final String FORWARDED = "FORWARDED";
    public static final String HELD_BACK = "HELD_BACK";
    public static final String IGNORED = "IGNORED";

    private ClientsModels() {
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

    /** One line of the Activity History. The subject is the Activity when {@code activityId} is set, else the Client. */
    public record HistoryEntry(
            String id,
            String clientId,
            String activityId,
            String field,
            String oldValue,
            String newValue,
            String actorUserId,
            Instant createdAt
    ) {
        public static HistoryEntry of(String clientId, String activityId, String field, String oldValue,
                String newValue, String actorUserId) {
            return new HistoryEntry(null, clientId, activityId, field, oldValue, newValue, actorUserId, null);
        }
    }

    /** A kind of work in the firm's own catalog. Retired, never removed, once work of the type exists. */
    public record ActivityType(
            String id,
            String name,
            String kind,
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
    }
}
