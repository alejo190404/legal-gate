package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
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
}
