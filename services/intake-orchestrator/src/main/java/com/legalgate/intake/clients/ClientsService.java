package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every console operation on Clients. Each mutation appends to the Activity History, with the
 * acting Firm Administrator taken from the Forwarded Identity.
 */
@Service
public class ClientsService {

    static final String FIELD_CLIENT = "client";
    static final String FIELD_CONTACT_ADDRESS = "contactAddress";

    private static final Pattern EMAIL = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    private final ClientsRepository clients;
    private final IntakeRepository intake;

    public ClientsService(ClientsRepository clients, IntakeRepository intake) {
        this.clients = clients;
        this.intake = intake;
    }

    public Client create(String tenant, String actor, ClientRequest request) {
        Client client = validated(tenant, null, request);
        List<HistoryEntry> history = new ArrayList<>();
        history.add(HistoryEntry.of(null, null, FIELD_CLIENT, null, client.name(), actor));
        client.contactAddresses().forEach(address ->
                history.add(HistoryEntry.of(null, null, FIELD_CONTACT_ADDRESS, null, address, actor)));
        return clients.saveClient(tenant, client, history);
    }

    public Client update(String tenant, String actor, String clientId, ClientRequest request) {
        Client before = client(tenant, clientId);
        Client after = validated(tenant, before, request);
        List<HistoryEntry> history = new ArrayList<>();
        diff(history, clientId, actor, "name", before.name(), after.name());
        diff(history, clientId, actor, "kind", before.kind(), after.kind());
        diff(history, clientId, actor, "idNumber", before.idNumber(), after.idNumber());
        diff(history, clientId, actor, "phone", before.phone(), after.phone());
        diff(history, clientId, actor, "notes", before.notes(), after.notes());
        diff(history, clientId, actor, "responsibleLawyerId", before.responsibleLawyerId(), after.responsibleLawyerId());
        after.contactAddresses().stream()
                .filter(address -> !before.contactAddresses().contains(address))
                .forEach(address -> history.add(HistoryEntry.of(clientId, null, FIELD_CONTACT_ADDRESS, null, address, actor)));
        before.contactAddresses().stream()
                .filter(address -> !after.contactAddresses().contains(address))
                .forEach(address -> history.add(HistoryEntry.of(clientId, null, FIELD_CONTACT_ADDRESS, address, null, actor)));
        return clients.saveClient(tenant, after, history);
    }

    public Client client(String tenant, String clientId) {
        return clients.client(tenant, clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "client_not_found"));
    }

    // ponytail: filters the whole tenant's list in memory; move the match into SQL if a firm's
    // Client list ever grows past a few thousand.
    public List<Client> search(String tenant, String query, boolean idleOnly) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return clients.clients(tenant).stream()
                .filter(client -> !idleOnly || client.openActivities() == 0)
                .filter(client -> needle.isEmpty()
                        || client.name().toLowerCase(Locale.ROOT).contains(needle)
                        || (client.idNumber() != null && client.idNumber().toLowerCase(Locale.ROOT).contains(needle))
                        || client.contactAddresses().stream().anyMatch(address -> address.contains(needle)))
                .toList();
    }

    public List<HistoryEntry> history(String tenant, String clientId) {
        client(tenant, clientId);
        return clients.history(tenant, clientId);
    }

    /** The active lawyer with that id, or a 400: Responsible and Assigned Lawyers are always active. */
    LawyerProfile activeLawyer(String tenant, String lawyerId, String reason) {
        return intake.lawyersForTenant(tenant).stream()
                .filter(lawyer -> lawyer.id().equals(lawyerId))
                .filter(lawyer -> lawyer.active() == null || lawyer.active())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, reason));
    }

    private Client validated(String tenant, Client existing, ClientRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "client_required");
        }
        String name = trimmed(request.name());
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "client_name_required");
        }
        String kind = request.kind() == null ? ClientsModels.PERSON : request.kind().trim().toUpperCase(Locale.ROOT);
        if (!ClientsModels.PERSON.equals(kind) && !ClientsModels.ORGANIZATION.equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_client_kind");
        }
        String lawyerId = activeLawyer(tenant, trimmed(request.responsibleLawyerId()), "responsible_lawyer_required").id();
        return new Client(existing == null ? null : existing.id(), name, kind, trimmed(request.idNumber()),
                trimmed(request.phone()), trimmed(request.notes()), lawyerId, contactAddresses(request.contactAddresses()),
                existing == null ? 0 : existing.openActivities(),
                existing == null ? null : existing.createdAt(), existing == null ? null : existing.updatedAt());
    }

    static List<String> contactAddresses(List<String> submitted) {
        Set<String> addresses = new LinkedHashSet<>();
        for (String address : submitted == null ? List.<String>of() : submitted) {
            String normalized = normalizeAddress(address);
            if (normalized == null) {
                continue;
            }
            if (!EMAIL.matcher(normalized).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_contact_address");
            }
            addresses.add(normalized);
        }
        if (addresses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contact_address_required");
        }
        return List.copyOf(addresses);
    }

    static String normalizeAddress(String address) {
        String trimmed = trimmed(address);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    static void diff(List<HistoryEntry> history, String clientId, String actor, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            history.add(HistoryEntry.of(clientId, null, field, before, after, actor));
        }
    }

    static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
