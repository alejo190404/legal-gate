package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.util.List;
import java.util.Optional;

public interface ClientsRepository {

    List<Client> clients(String tenantSlug);

    Optional<Client> client(String tenantSlug, String clientId);

    /** Case-insensitive match on a Contact Address. */
    Optional<Client> clientForContactAddress(String tenantSlug, String address);

    /**
     * Inserts or updates the Client, replacing its Contact Addresses, and appends {@code history}
     * in the same write. Throws {@link ContactAddressTakenException} when another Client holds one
     * of the addresses.
     */
    Client saveClient(String tenantSlug, Client client, List<HistoryEntry> history);

    /** Every Activity History entry about the Client or its Activities, oldest first. */
    List<HistoryEntry> history(String tenantSlug, String clientId);

    /** The whole catalog, retired types included. */
    List<ActivityType> activityTypes(String tenantSlug);

    Optional<ActivityType> activityType(String tenantSlug, String activityTypeId);

    /** Seeds {@code starterSet} unless this tenant was ever seeded; atomic, so seeding happens once. */
    void seedActivityTypesOnce(String tenantSlug, List<ActivityType> starterSet);

    /** Inserts or updates by id. */
    ActivityType saveActivityType(String tenantSlug, ActivityType activityType);

    /** Records a Client's email once per tenant and Message-ID; empty when it was already recorded. */
    Optional<Correspondence> recordCorrespondence(String tenantSlug, Correspondence correspondence);

    Optional<Correspondence> correspondence(String tenantSlug, String correspondenceId);

    /** Newest first. */
    List<Correspondence> correspondenceForClient(String tenantSlug, String clientId);

    /** Leases unresolved entries that are due, across tenants, so a second worker skips them. */
    List<Correspondence> claimDueCorrespondence(int limit);

    /**
     * Saves the entry's new state and queues {@code notifications} in the same write, so an outcome is never recorded without the mail it caused, or the reverse.
     */
    void saveCorrespondence(String tenantSlug, Correspondence correspondence, List<NotificationOutboxItem> notifications);

    Optional<String> engagedClientId(String tenantSlug, String consultationId);

    /**
     * Links the Consultation to the Client and appends {@code history}; false, changing nothing,
     * when the Consultation was already engaged.
     */
    boolean engage(String tenantSlug, String consultationId, String clientId, List<HistoryEntry> history);
}
