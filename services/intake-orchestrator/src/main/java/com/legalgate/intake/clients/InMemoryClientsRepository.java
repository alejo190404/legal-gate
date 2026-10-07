package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Public so the Seam 1 tests in the repository package can build it beside InMemoryIntakeRepository. */
@Repository
@ConditionalOnProperty(name = "legalgate.intake.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryClientsRepository implements ClientsRepository {

    private final Map<String, Map<String, Client>> clientsByTenant = new ConcurrentHashMap<>();
    private final Map<String, List<HistoryEntry>> historyByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ActivityType>> activityTypesByTenant = new ConcurrentHashMap<>();

    @Override
    public List<Client> clients(String tenantSlug) {
        return tenantClients(tenantSlug).values().stream()
                .sorted(Comparator.comparing(client -> client.name().toLowerCase()))
                .toList();
    }

    @Override
    public Optional<Client> client(String tenantSlug, String clientId) {
        return Optional.ofNullable(tenantClients(tenantSlug).get(clientId));
    }

    @Override
    public Optional<Client> clientForContactAddress(String tenantSlug, String address) {
        return tenantClients(tenantSlug).values().stream()
                .filter(client -> client.contactAddresses().stream().anyMatch(held -> held.equalsIgnoreCase(address)))
                .findFirst();
    }

    @Override
    public synchronized Client saveClient(String tenantSlug, Client client, List<HistoryEntry> history) {
        for (String address : client.contactAddresses()) {
            clientForContactAddress(tenantSlug, address)
                    .filter(holder -> !holder.id().equals(client.id()))
                    .ifPresent(holder -> {
                        throw new ContactAddressTakenException(address, holder.id(), holder.name());
                    });
        }
        Instant now = Instant.now();
        String id = client.id() == null ? UUID.randomUUID().toString() : client.id();
        Client existing = tenantClients(tenantSlug).get(id);
        Client saved = new Client(id, client.name(), client.kind(), client.idNumber(), client.phone(), client.notes(),
                client.responsibleLawyerId(), List.copyOf(client.contactAddresses()), 0,
                existing == null ? now : existing.createdAt(), now);
        tenantClients(tenantSlug).put(id, saved);
        appendHistory(tenantSlug, id, history);
        return saved;
    }

    @Override
    public List<HistoryEntry> history(String tenantSlug, String clientId) {
        return tenantHistory(tenantSlug).stream().filter(entry -> clientId.equals(entry.clientId())).toList();
    }

    @Override
    public List<ActivityType> activityTypes(String tenantSlug) {
        return activityTypesByTenant.getOrDefault(tenantSlug, Map.of()).values().stream()
                .sorted(Comparator.comparing(ActivityType::kind).thenComparing(ActivityType::name))
                .toList();
    }

    @Override
    public Optional<ActivityType> activityType(String tenantSlug, String activityTypeId) {
        return Optional.ofNullable(activityTypesByTenant.getOrDefault(tenantSlug, Map.of()).get(activityTypeId));
    }

    @Override
    public void seedActivityTypesOnce(String tenantSlug, List<ActivityType> starterSet) {
        activityTypesByTenant.computeIfAbsent(tenantSlug, ignored -> {
            Map<String, ActivityType> seeded = new ConcurrentHashMap<>();
            starterSet.forEach(type -> {
                String id = UUID.randomUUID().toString();
                seeded.put(id, withId(type, id));
            });
            return seeded;
        });
    }

    @Override
    public ActivityType saveActivityType(String tenantSlug, ActivityType activityType) {
        String id = activityType.id() == null ? UUID.randomUUID().toString() : activityType.id();
        ActivityType saved = withId(activityType, id);
        activityTypesByTenant.computeIfAbsent(tenantSlug, ignored -> new ConcurrentHashMap<>()).put(id, saved);
        return saved;
    }

    private static ActivityType withId(ActivityType type, String id) {
        return new ActivityType(id, type.name(), type.kind(), type.defaultTurnaroundDays(), type.defaultTermDays(), type.retiredAt());
    }

    private void appendHistory(String tenantSlug, String clientId, List<HistoryEntry> history) {
        Instant now = Instant.now();
        for (HistoryEntry entry : history) {
            tenantHistory(tenantSlug).add(new HistoryEntry(UUID.randomUUID().toString(), clientId, entry.activityId(),
                    entry.field(), entry.oldValue(), entry.newValue(), entry.actorUserId(), now));
        }
    }

    private Map<String, Client> tenantClients(String tenantSlug) {
        return clientsByTenant.computeIfAbsent(tenantSlug, ignored -> new ConcurrentHashMap<>());
    }

    private List<HistoryEntry> tenantHistory(String tenantSlug) {
        return historyByTenant.computeIfAbsent(tenantSlug, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()));
    }
}
