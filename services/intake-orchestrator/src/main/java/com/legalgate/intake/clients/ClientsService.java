package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityKind;
import com.legalgate.intake.clients.ClientsModels.ActivityRequest;
import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.ActivityTypeRequest;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.ClientRequest;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.clients.ClientsModels.Engagement;
import com.legalgate.intake.clients.ClientsModels.EngagementRequest;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.model.TenantRoutingRule;
import com.legalgate.intake.repository.IntakeRepository;
import com.legalgate.intake.service.BusinessDays;
import com.legalgate.intake.service.ClerkClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** Suggestions for the firm to review, seeded the first time its catalog is read. */
    static final List<ActivityType> STARTER_CATALOG = List.of(
            new ActivityType(null, "Redacción de contrato", ActivityKind.TURNAROUND, 5, null, null),
            new ActivityType(null, "Revisión de contrato", ActivityKind.TURNAROUND, 3, null, null),
            new ActivityType(null, "Concepto jurídico", ActivityKind.TURNAROUND, 5, null, null),
            new ActivityType(null, "Redacción de demanda", ActivityKind.TURNAROUND, 10, null, null),
            new ActivityType(null, "Redacción de tutela", ActivityKind.TURNAROUND, 3, null, null),
            new ActivityType(null, "Derecho de petición", ActivityKind.TURNAROUND, 3, null, null),
            new ActivityType(null, "Reunión con el abogado", ActivityKind.DATED, null, null, null),
            new ActivityType(null, "Audiencia", ActivityKind.DATED, null, null, null),
            new ActivityType(null, "Firma en notaría", ActivityKind.DATED, null, null, null),
            new ActivityType(null, "Fallo de tutela", ActivityKind.EXTERNAL, null, 10, null),
            new ActivityType(null, "Respuesta a derecho de petición", ActivityKind.EXTERNAL, null, 15, null),
            new ActivityType(null, "Registro de marca", ActivityKind.EXTERNAL, null, null, null));

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientsService.class);
    private static final int MAX_DAYS = 365;

    private final ClientsRepository clients;
    private final IntakeRepository intake;
    private final ClerkClient clerk;

    public ClientsService(ClientsRepository clients, IntakeRepository intake, ClerkClient clerk) {
        this.clients = clients;
        this.intake = intake;
        this.clerk = clerk;
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

    /**
     * Deletion is the only way out of being a Client: a mistake, or a request under Ley 1581. The
     * person goes through Diagnostics if they write again.
     */
    public void deleteClient(String tenant, String clientId) {
        client(tenant, clientId);
        clients.deleteClient(tenant, clientId);
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

    // ponytail: resolves actor names one Clerk call at a time, so a Clerk outage costs roughly
    // ClerkClient's connect + read timeout per distinct firm user; stop calling Clerk after the
    // first timeout in a request if Histories with many users get slow.
    public List<HistoryEntry> history(String tenant, String clientId) {
        client(tenant, clientId);
        List<LawyerProfile> lawyers = intake.lawyersForTenant(tenant);
        Map<String, String> names = new HashMap<>();
        return clients.history(tenant, clientId).stream()
                .map(entry -> entry.actorUserId() == null ? entry : entry.withActorName(
                        names.computeIfAbsent(entry.actorUserId(), actor -> actorName(actor, lawyers))))
                .toList();
    }

    /** The firm user's Lawyer name, else their email, else the raw id: a Clerk failure never fails the History. */
    private String actorName(String actor, List<LawyerProfile> lawyers) {
        Optional<String> email;
        try {
            email = clerk.userEmail(actor);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not resolve History actor {} through Clerk; showing the raw id", actor, e);
            return actor;
        }
        return email.map(address -> lawyers.stream()
                        .filter(lawyer -> address.equalsIgnoreCase(lawyer.email()))
                        .map(LawyerProfile::displayName)
                        .findFirst()
                        .orElse(address))
                .orElse(actor);
    }

    /** Whom the Consultation became, or a Client draft prefilled from it. */
    public Engagement engagement(String tenant, String consultationId) {
        ConsultationResponse consultation = consultation(tenant, consultationId);
        String engaged = clients.engagedClientId(tenant, consultationId).orElse(null);
        return new Engagement(consultationId, engaged, engaged != null ? null : new ClientRequest(
                consultation.clientName(), ClientsModels.PERSON, null, null, null,
                prefilledLawyer(tenant, consultation), List.of(consultation.clientEmail())));
    }

    /**
     * The firm takes the potential client on: a deliberate act, offered on any Consultation out of
     * Diagnostics and at most once. Into an existing Client, the sender address is added to it.
     */
    public Client engage(String tenant, String actor, String consultationId, EngagementRequest request) {
        ConsultationResponse consultation = consultation(tenant, consultationId);
        if ("DIAGNOSTICS_PENDING".equals(consultation.status())) {
            // "Accept now" stays the one way to end Diagnostics early.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "consultation_in_diagnostics");
        }
        if (clients.engagedClientId(tenant, consultationId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "consultation_already_engaged");
        }
        if (request == null || (request.clientId() == null && request.client() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "client_required");
        }
        Client client;
        if (request.clientId() != null) {
            Client existing = client(tenant, request.clientId());
            List<String> addresses = new ArrayList<>(existing.contactAddresses());
            String sender = normalizeAddress(consultation.clientEmail());
            if (sender != null && !addresses.contains(sender)) {
                addresses.add(sender);
            }
            client = update(tenant, actor, existing.id(), new ClientRequest(existing.name(), existing.kind(),
                    existing.idNumber(), existing.phone(), existing.notes(), existing.responsibleLawyerId(), addresses));
        } else {
            client = create(tenant, actor, request.client());
        }
        // ponytail: create-then-link is two writes; two admins engaging the same Consultation at
        // once can leave the loser's new Client unlinked. Rare, visible, and deletable.
        if (!clients.engage(tenant, consultationId, client.id(),
                List.of(HistoryEntry.of(client.id(), null, "engagement", null, consultationId, actor)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "consultation_already_engaged");
        }
        return client;
    }

    private ConsultationResponse consultation(String tenant, String consultationId) {
        return intake.consultationById(tenant, consultationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "consultation_not_found"));
    }

    /** The Routing Rule's lawyer, else the Event's, else the firm's first active lawyer. */
    private String prefilledLawyer(String tenant, ConsultationResponse consultation) {
        List<String> active = intake.lawyersForTenant(tenant).stream()
                .filter(lawyer -> lawyer.active() == null || lawyer.active())
                .map(LawyerProfile::id)
                .toList();
        var settings = intake.settingsFor(tenant, null);
        List<TenantRoutingRule> rules = settings == null || settings.routingRules() == null ? List.of() : settings.routingRules();
        List<String> candidates = new ArrayList<>();
        rules.stream().filter(rule -> rule.name().equals(consultation.consultationType()))
                .map(TenantRoutingRule::lawyerId).forEach(candidates::add);
        if (consultation.event() != null) {
            candidates.add(consultation.event().lawyerId());
        }
        return candidates.stream().filter(active::contains).findFirst()
                .orElse(active.isEmpty() ? null : active.get(0));
    }

    /** Every email the Client sent and what LegalGate did with it, newest first. */
    public List<Correspondence> correspondence(String tenant, String clientId) {
        client(tenant, clientId);
        return clients.correspondenceForClient(tenant, clientId);
    }

    public List<ActivityType> activityTypes(String tenant) {
        clients.seedActivityTypesOnce(tenant, STARTER_CATALOG);
        return clients.activityTypes(tenant);
    }

    /** What a new Activity may use: the catalog without its retired types. */
    public List<ActivityType> offeredActivityTypes(String tenant) {
        return activityTypes(tenant).stream().filter(type -> type.retiredAt() == null).toList();
    }

    public ActivityType createActivityType(String tenant, ActivityTypeRequest request) {
        clients.seedActivityTypesOnce(tenant, STARTER_CATALOG);
        return clients.saveActivityType(tenant, validatedType(null, null, request));
    }

    /** Changes the catalog only: Activities already open keep the defaults they were created with. */
    public ActivityType updateActivityType(String tenant, String activityTypeId, ActivityTypeRequest request) {
        ActivityType existing = activityType(tenant, activityTypeId);
        return clients.saveActivityType(tenant, validatedType(existing.id(), existing.retiredAt(), request));
    }

    public ActivityType retireActivityType(String tenant, String activityTypeId) {
        ActivityType existing = activityType(tenant, activityTypeId);
        if (existing.retiredAt() != null) {
            return existing;
        }
        return withRetiredAt(tenant, existing, Instant.now());
    }

    /** Undoes a retirement: the type is offered for new Activities again. */
    public ActivityType restoreActivityType(String tenant, String activityTypeId) {
        ActivityType existing = activityType(tenant, activityTypeId);
        if (existing.retiredAt() == null) {
            return existing;
        }
        return withRetiredAt(tenant, existing, null);
    }

    private ActivityType withRetiredAt(String tenant, ActivityType existing, Instant retiredAt) {
        return clients.saveActivityType(tenant, new ActivityType(existing.id(), existing.name(), existing.kind(),
                existing.defaultTurnaroundDays(), existing.defaultTermDays(), retiredAt));
    }

    ActivityType activityType(String tenant, String activityTypeId) {
        return clients.activityType(tenant, activityTypeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "activity_type_not_found"));
    }

    private ActivityType validatedType(String id, Instant retiredAt, ActivityTypeRequest request) {
        if (request == null || trimmed(request.name()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activity_type_name_required");
        }
        ActivityKind kind = activityKind(request.kind());
        return switch (kind) {
            case TURNAROUND -> {
                if (request.defaultTurnaroundDays() == null || request.defaultTurnaroundDays() < 1
                        || request.defaultTurnaroundDays() > MAX_DAYS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "turnaround_days_required");
                }
                yield new ActivityType(id, request.name().trim(), kind, request.defaultTurnaroundDays(), null, retiredAt);
            }
            case EXTERNAL -> new ActivityType(id, request.name().trim(), kind, null,
                    termDays(request.defaultTermDays()), retiredAt);
            case DATED -> new ActivityType(id, request.name().trim(), kind, null, null, retiredAt);
        };
    }

    private static ActivityKind activityKind(String kind) {
        try {
            return ActivityKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_activity_kind");
        }
    }

    /** A statutory term is optional; when given it is a positive number of Business Days. */
    static Integer termDays(Integer days) {
        if (days != null && (days < 1 || days > MAX_DAYS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_term_days");
        }
        return days;
    }

    public List<Activity> activities(String tenant, String clientId) {
        client(tenant, clientId);
        return clients.activities(tenant, clientId);
    }

    /**
     * Opens an Activity of an offered type. The type's kind and Turnaround are snapshotted here,
     * the start defaults to today and may be backdated, and the Assigned Lawyer defaults to the
     * Client's Responsible Lawyer.
     */
    public Activity createActivity(String tenant, String actor, String clientId, ActivityRequest request) {
        Client client = client(tenant, clientId);
        if (request == null || request.activityTypeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activity_type_required");
        }
        ActivityType type = activityType(tenant, request.activityTypeId());
        if (type.retiredAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activity_type_retired");
        }
        String lawyerId = request.assignedLawyerId() == null ? client.responsibleLawyerId() : request.assignedLawyerId();
        Activity activity = new Activity(null, tenant, clientId, type.id(), type.name(), type.kind(),
                trimmed(request.label()),
                request.startDate() == null ? BusinessDays.today() : request.startDate(),
                type.kind() == ActivityKind.TURNAROUND ? type.defaultTurnaroundDays() : null,
                type.kind() == ActivityKind.TURNAROUND ? request.dueDate() : null,
                type.kind() == ActivityKind.DATED ? request.scheduledDate() : null,
                type.kind() == ActivityKind.EXTERNAL ? termDays(request.termDays()) : null,
                activeLawyer(tenant, lawyerId, "assigned_lawyer_required").id(),
                false, null, null, null, null);
        requireDate(activity);
        return clients.saveActivity(tenant, activity,
                List.of(HistoryEntry.of(clientId, null, "activity", null, activity.displayName(), actor)));
    }

    /** Label, dates, term and Assigned Lawyer follow reality; a done Activity is never edited. */
    public Activity updateActivity(String tenant, String actor, String clientId, String activityId, ActivityRequest request) {
        Activity before = openActivity(tenant, clientId, activityId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activity_required");
        }
        String lawyerId = request.assignedLawyerId() == null ? before.assignedLawyerId()
                : activeLawyer(tenant, request.assignedLawyerId(), "assigned_lawyer_required").id();
        Activity after = before.with(trimmed(request.label()),
                request.startDate() == null ? before.startDate() : request.startDate(),
                before.kind() == ActivityKind.TURNAROUND ? request.dueDate() : null,
                before.kind() == ActivityKind.DATED && request.scheduledDate() != null ? request.scheduledDate() : before.scheduledDate(),
                before.kind() == ActivityKind.EXTERNAL ? termDays(request.termDays()) : null,
                lawyerId, false, null);
        requireDate(after);
        List<HistoryEntry> history = new ArrayList<>();
        diff(history, before.clientId(), actor, "label", before.label(), after.label());
        diff(history, before.clientId(), actor, "startDate", text(before.startDate()), text(after.startDate()));
        diff(history, before.clientId(), actor, "dueDate", text(before.dueDate()), text(after.dueDate()));
        diff(history, before.clientId(), actor, "scheduledDate", text(before.scheduledDate()), text(after.scheduledDate()));
        diff(history, before.clientId(), actor, "termDays", Objects.toString(before.termDays(), null),
                Objects.toString(after.termDays(), null));
        diff(history, before.clientId(), actor, "assignedLawyerId", before.assignedLawyerId(), after.assignedLawyerId());
        return clients.saveActivity(tenant, after, history);
    }

    /** Done is final: a closed piece of work never reappears in a Client's answer. */
    public Activity completeActivity(String tenant, String actor, String clientId, String activityId) {
        Activity before = openActivity(tenant, clientId, activityId);
        return clients.saveActivity(tenant,
                before.with(before.label(), before.startDate(), before.dueDate(), before.scheduledDate(),
                        before.termDays(), before.assignedLawyerId(), true, Instant.now()),
                List.of(HistoryEntry.of(before.clientId(), null, "status", "OPEN", "DONE", actor)));
    }

    private Activity openActivity(String tenant, String clientId, String activityId) {
        Activity activity = clients.activity(tenant, activityId)
                .filter(candidate -> candidate.clientId().equals(clientId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "activity_not_found"));
        if (activity.done()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "activity_done");
        }
        return activity;
    }

    private static void requireDate(Activity activity) {
        if (activity.kind() == ActivityKind.DATED && activity.scheduledDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduled_date_required");
        }
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * Refuses a settings save that deactivates or removes a lawyer who is still a Responsible
     * Lawyer or the Assigned Lawyer on open work, naming what must be reassigned first.
     */
    public void assertLawyersReleasable(String tenant, List<LawyerProfile> current, List<LawyerProfile> next) {
        List<LawyerInChargeException.Blocker> blockers = new ArrayList<>();
        List<Client> all = clients.clients(tenant);
        List<Activity> open = clients.openActivities(tenant);
        for (LawyerProfile lawyer : current) {
            boolean stays = next.stream().anyMatch(candidate -> candidate.id().equals(lawyer.id())
                    && (candidate.active() == null || candidate.active()));
            if (stays) {
                continue;
            }
            List<String> responsibleFor = all.stream()
                    .filter(client -> lawyer.id().equals(client.responsibleLawyerId()))
                    .map(Client::name)
                    .toList();
            List<String> assigned = open.stream()
                    .filter(activity -> lawyer.id().equals(activity.assignedLawyerId()))
                    .map(activity -> activity.displayName() + " (" + all.stream()
                            .filter(client -> client.id().equals(activity.clientId()))
                            .map(Client::name).findFirst().orElse("?") + ")")
                    .toList();
            if (!responsibleFor.isEmpty() || !assigned.isEmpty()) {
                blockers.add(new LawyerInChargeException.Blocker(lawyer.id(), lawyer.displayName(), responsibleFor, assigned));
            }
        }
        if (!blockers.isEmpty()) {
            throw new LawyerInChargeException(blockers);
        }
    }

    /** The active lawyer with that id, or a 400: Responsible and Assigned Lawyers are always active. */
    LawyerProfile activeLawyer(String tenant, String lawyerId, String reason) {
        return intake.lawyer(tenant, lawyerId)
                .filter(lawyer -> lawyer.active() == null || lawyer.active())
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
