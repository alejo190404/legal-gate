package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
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
    private final Map<String, Correspondence> correspondenceById = new ConcurrentHashMap<>();
    private final Map<String, String> engagedClientByConsultation = new ConcurrentHashMap<>();
    private final Map<String, Activity> activitiesById = new ConcurrentHashMap<>();
    private final IntakeRepository intake;

    /** Queues mail through the in-memory intake repository, which owns the outbox. */
    public InMemoryClientsRepository(IntakeRepository intake) {
        this.intake = intake;
    }

    @Override
    public List<Client> clients(String tenantSlug) {
        return tenantClients(tenantSlug).values().stream()
                .sorted(Comparator.comparing(client -> client.name().toLowerCase()))
                .map(this::withOpenActivities)
                .toList();
    }

    @Override
    public Optional<Client> client(String tenantSlug, String clientId) {
        return Optional.ofNullable(tenantClients(tenantSlug).get(clientId)).map(this::withOpenActivities);
    }

    @Override
    public Optional<Client> clientForContactAddress(String tenantSlug, String address) {
        return tenantClients(tenantSlug).values().stream()
                .filter(client -> client.contactAddresses().stream().anyMatch(held -> held.equalsIgnoreCase(address)))
                .findFirst()
                .map(this::withOpenActivities);
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

    @Override
    public synchronized Optional<Correspondence> recordCorrespondence(String tenantSlug, Correspondence correspondence) {
        boolean duplicate = correspondenceById.values().stream().anyMatch(existing ->
                existing.tenantId().equals(tenantSlug) && existing.messageId().equals(correspondence.messageId()));
        if (duplicate) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        Correspondence saved = new Correspondence(id, tenantSlug, correspondence.clientId(), correspondence.contactAddress(),
                correspondence.messageId(), correspondence.subject(), correspondence.body(), correspondence.receivedAt(),
                correspondence.intent(), correspondence.outcome(), correspondence.answerText(), correspondence.purgedAt(),
                correspondence.attempts(), correspondence.nextAttemptAt());
        correspondenceById.put(id, saved);
        return Optional.of(saved);
    }

    @Override
    public Optional<Correspondence> correspondence(String tenantSlug, String correspondenceId) {
        return Optional.ofNullable(correspondenceById.get(correspondenceId)).filter(entry -> entry.tenantId().equals(tenantSlug));
    }

    @Override
    public List<Correspondence> correspondenceForClient(String tenantSlug, String clientId) {
        return correspondenceById.values().stream()
                .filter(entry -> entry.tenantId().equals(tenantSlug) && entry.clientId().equals(clientId))
                .sorted(Comparator.comparing(Correspondence::receivedAt).reversed())
                .toList();
    }

    @Override
    public synchronized List<Correspondence> claimDueCorrespondence(int limit) {
        Instant now = Instant.now();
        List<Correspondence> due = correspondenceById.values().stream()
                .filter(entry -> entry.outcome() == null && entry.nextAttemptAt() != null && !entry.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(Correspondence::receivedAt))
                .limit(limit)
                .toList();
        due.forEach(entry -> correspondenceById.put(entry.id(), leased(entry, now.plusSeconds(300))));
        return due;
    }

    @Override
    public void saveCorrespondence(String tenantSlug, Correspondence correspondence, List<NotificationOutboxItem> notifications) {
        correspondenceById.put(correspondence.id(), correspondence);
        intake.queueNotifications(tenantSlug, notifications);
    }

    @Override
    public Optional<String> engagedClientId(String tenantSlug, String consultationId) {
        return Optional.ofNullable(engagedClientByConsultation.get(tenantSlug + ":" + consultationId));
    }

    @Override
    public boolean engage(String tenantSlug, String consultationId, String clientId, List<HistoryEntry> history) {
        if (engagedClientByConsultation.putIfAbsent(tenantSlug + ":" + consultationId, clientId) != null) {
            return false;
        }
        appendHistory(tenantSlug, clientId, history);
        return true;
    }

    @Override
    public List<Activity> activities(String tenantSlug, String clientId) {
        return activitiesById.values().stream()
                .filter(activity -> activity.tenantId().equals(tenantSlug) && activity.clientId().equals(clientId))
                .sorted(Comparator.comparing(Activity::done).thenComparing(Activity::startDate))
                .map(activity -> withTypeName(tenantSlug, activity))
                .toList();
    }

    @Override
    public Optional<Activity> activity(String tenantSlug, String activityId) {
        return Optional.ofNullable(activitiesById.get(activityId))
                .filter(activity -> activity.tenantId().equals(tenantSlug))
                .map(activity -> withTypeName(tenantSlug, activity));
    }

    @Override
    public Activity saveActivity(String tenantSlug, Activity activity, List<HistoryEntry> history) {
        String id = activity.id() == null ? UUID.randomUUID().toString() : activity.id();
        Activity saved = new Activity(id, tenantSlug, activity.clientId(), activity.activityTypeId(), null,
                activity.kind(), activity.label(), activity.startDate(), activity.turnaroundDays(), activity.dueDate(),
                activity.scheduledDate(), activity.termDays(), activity.assignedLawyerId(), activity.done(),
                activity.doneAt(), activity.overdueNudgedFor(), activity.termNudgedFor(),
                activity.createdAt() == null ? Instant.now() : activity.createdAt());
        activitiesById.put(id, saved);
        appendHistory(tenantSlug, activity.clientId(), history.stream()
                .map(entry -> HistoryEntry.of(activity.clientId(), id, entry.field(), entry.oldValue(), entry.newValue(), entry.actorUserId()))
                .toList());
        return activity(tenantSlug, id).orElseThrow();
    }

    @Override
    public List<Activity> openActivities(String tenantSlug) {
        return activitiesById.values().stream()
                .filter(activity -> activity.tenantId().equals(tenantSlug) && !activity.done())
                .map(activity -> withTypeName(tenantSlug, activity))
                .toList();
    }

    @Override
    public List<Activity> openActivitiesAcrossTenants() {
        return activitiesById.values().stream()
                .filter(activity -> !activity.done())
                .map(activity -> withTypeName(activity.tenantId(), activity))
                .toList();
    }

    @Override
    public synchronized boolean claimNudge(String tenantSlug, String activityId, NudgeTrigger trigger,
            java.time.LocalDate forDate, List<NotificationOutboxItem> notifications) {
        Activity activity = activitiesById.get(activityId);
        boolean overdue = trigger == NudgeTrigger.OVERDUE;
        if (activity == null || forDate.equals(overdue ? activity.overdueNudgedFor() : activity.termNudgedFor())) {
            return false;
        }
        activitiesById.put(activityId, new Activity(activity.id(), activity.tenantId(), activity.clientId(),
                activity.activityTypeId(), activity.typeName(), activity.kind(), activity.label(), activity.startDate(),
                activity.turnaroundDays(), activity.dueDate(), activity.scheduledDate(), activity.termDays(),
                activity.assignedLawyerId(), activity.done(), activity.doneAt(),
                overdue ? forDate : activity.overdueNudgedFor(), overdue ? activity.termNudgedFor() : forDate,
                activity.createdAt()));
        intake.queueNotifications(tenantSlug, notifications);
        return true;
    }

    @Override
    public synchronized int purgeCorrespondenceBodies(Instant cutoff) {
        List<Correspondence> due = correspondenceById.values().stream()
                .filter(entry -> entry.purgedAt() == null && entry.receivedAt().isBefore(cutoff))
                .toList();
        Instant now = Instant.now();
        due.forEach(entry -> correspondenceById.put(entry.id(), new Correspondence(entry.id(), entry.tenantId(),
                entry.clientId(), entry.contactAddress(), entry.messageId(), entry.subject(), null, entry.receivedAt(),
                entry.intent(), entry.outcome(), entry.answerText(), now, entry.attempts(), entry.nextAttemptAt())));
        return due.size();
    }

    @Override
    public synchronized void deleteClient(String tenantSlug, String clientId) {
        tenantClients(tenantSlug).remove(clientId);
        activitiesById.values().removeIf(activity -> activity.clientId().equals(clientId));
        tenantHistory(tenantSlug).removeIf(entry -> entry.clientId().equals(clientId));
        correspondenceById.values().removeIf(entry -> entry.clientId().equals(clientId));
        engagedClientByConsultation.values().removeIf(clientId::equals);
    }

    private Activity withTypeName(String tenantSlug, Activity activity) {
        String typeName = activityType(tenantSlug, activity.activityTypeId()).map(ActivityType::name).orElse(null);
        return new Activity(activity.id(), activity.tenantId(), activity.clientId(), activity.activityTypeId(), typeName,
                activity.kind(), activity.label(), activity.startDate(), activity.turnaroundDays(), activity.dueDate(),
                activity.scheduledDate(), activity.termDays(), activity.assignedLawyerId(), activity.done(),
                activity.doneAt(), activity.overdueNudgedFor(), activity.termNudgedFor(), activity.createdAt());
    }

    private Client withOpenActivities(Client client) {
        int open = (int) activitiesById.values().stream()
                .filter(activity -> activity.clientId().equals(client.id()) && !activity.done())
                .count();
        return new Client(client.id(), client.name(), client.kind(), client.idNumber(), client.phone(), client.notes(),
                client.responsibleLawyerId(), client.contactAddresses(), open, client.createdAt(), client.updatedAt());
    }

    private static Correspondence leased(Correspondence entry, Instant until) {
        return new Correspondence(entry.id(), entry.tenantId(), entry.clientId(), entry.contactAddress(), entry.messageId(),
                entry.subject(), entry.body(), entry.receivedAt(), entry.intent(), entry.outcome(), entry.answerText(),
                entry.purgedAt(), entry.attempts(), until);
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
