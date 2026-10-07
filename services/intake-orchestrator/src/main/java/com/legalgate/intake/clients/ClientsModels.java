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
}
