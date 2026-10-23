package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityKind;
import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.Page;
import com.legalgate.intake.repository.NotificationOutboxSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name = "legalgate.intake.persistence", havingValue = "jdbc")
class JdbcClientsRepository implements ClientsRepository {

    static final String WORKER = "__worker__";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcClientsRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<Client> clients(String tenantSlug) {
        return inTenant(tenantSlug, () -> withAddresses(jdbc.query(
                clientSelect() + " where t.slug = ? order by lower(c.name)", this::mapClient, tenantSlug)));
    }

    // Keyset on (lower(name), id): both sides of the comparison are lowered by Postgres, so the
    // cursor sorts exactly like the rows. strpos matches the query literally, % and _ included,
    // and unaccent on both sides lets "jose" find "José" and back.
    @Override
    public Page<Client> clients(String tenantSlug, String query, boolean idleOnly, String cursor, int limit) {
        StringBuilder sql = new StringBuilder(clientSelect()).append(" where t.slug = ?");
        List<Object> args = new ArrayList<>(List.of(tenantSlug));
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            sql.append("""
                     and (strpos(unaccent(lower(c.name)), unaccent(?)) > 0
                       or strpos(unaccent(lower(coalesce(c.id_number, ''))), unaccent(?)) > 0
                       or exists (select 1 from client_contact_addresses a
                                  where a.client_id = c.id and strpos(unaccent(lower(a.address)), unaccent(?)) > 0))
                    """);
            args.addAll(List.of(needle, needle, needle));
        }
        if (idleOnly) {
            sql.append(" and not exists (select 1 from activities a where a.client_id = c.id and not a.done)");
        }
        if (cursor != null) {
            List<String> after = Page.sortKey(cursor, 2);
            sql.append(" and (lower(c.name), c.id) > (lower(?), ?)");
            args.addAll(List.of(after.get(0), Page.uuid(after.get(1))));
        }
        sql.append(" order by lower(c.name), c.id limit ?");
        args.add(limit + 1);
        return inTenant(tenantSlug, () -> Page.of(withAddresses(jdbc.query(sql.toString(), this::mapClient, args.toArray())),
                limit, client -> List.of(client.name(), client.id())));
    }

    @Override
    public Optional<Client> client(String tenantSlug, String clientId) {
        UUID id = uuid(clientId);
        if (id == null) {
            return Optional.empty();
        }
        return inTenant(tenantSlug, () -> withAddresses(jdbc.query(
                clientSelect() + " where t.slug = ? and c.id = ?", this::mapClient, tenantSlug, id)).stream().findFirst());
    }

    @Override
    public Optional<Client> clientForContactAddress(String tenantSlug, String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        return inTenant(tenantSlug, () -> withAddresses(jdbc.query(clientSelect() + """
                 where t.slug = ? and c.id = (
                   select a.client_id from client_contact_addresses a
                   where a.tenant_id = t.id and lower(a.address) = lower(?))
                """, this::mapClient, tenantSlug, address.trim())).stream().findFirst());
    }

    @Override
    public Client saveClient(String tenantSlug, Client client, List<HistoryEntry> history) {
        UUID id = client.id() == null ? UUID.randomUUID() : UUID.fromString(client.id());
        try {
            inTenant(tenantSlug, () -> {
                UUID tenantId = tenantId(tenantSlug);
                for (String address : client.contactAddresses()) {
                    jdbc.query("""
                            select c.id, c.name from client_contact_addresses a join clients c on c.id = a.client_id
                            where a.tenant_id = ? and lower(a.address) = lower(?) and a.client_id <> ?
                            """, (ResultSet rs, int row) -> {
                        throw new ContactAddressTakenException(address, rs.getString("id"), rs.getString("name"));
                    }, tenantId, address, id);
                }
                jdbc.update("""
                        insert into clients (id, tenant_id, name, kind, id_number, phone, notes, responsible_lawyer_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (id) do update set
                          name = excluded.name, kind = excluded.kind, id_number = excluded.id_number,
                          phone = excluded.phone, notes = excluded.notes,
                          responsible_lawyer_id = excluded.responsible_lawyer_id, updated_at = now()
                        """, id, tenantId, client.name(), client.kind(), client.idNumber(), client.phone(),
                        client.notes(), UUID.fromString(client.responsibleLawyerId()));
                jdbc.update("delete from client_contact_addresses where client_id = ? and not (address = any(?))",
                        id, client.contactAddresses().toArray(String[]::new));
                for (String address : client.contactAddresses()) {
                    jdbc.update("""
                            insert into client_contact_addresses (tenant_id, client_id, address)
                            values (?, ?, ?) on conflict (client_id, address) do nothing
                            """, tenantId, id, address);
                }
                insertHistory(tenantId, id, history);
                return null;
            });
        } catch (DuplicateKeyException race) {
            // Another write took the address between the check and the insert.
            for (String address : client.contactAddresses()) {
                clientForContactAddress(tenantSlug, address)
                        .filter(holder -> !holder.id().equals(id.toString()))
                        .ifPresent(holder -> {
                            throw new ContactAddressTakenException(address, holder.id(), holder.name());
                        });
            }
            throw race;
        }
        return client(tenantSlug, id.toString()).orElseThrow();
    }

    @Override
    public List<HistoryEntry> history(String tenantSlug, String clientId) {
        return inTenant(tenantSlug, () -> jdbc.query("""
                select h.id, h.client_id, h.activity_id, h.field, h.old_value, h.new_value, h.actor_user_id, h.created_at
                from activity_history h join tenants t on t.id = h.tenant_id
                where t.slug = ? and h.client_id = ?
                order by h.created_at desc, h.id desc
                """, (rs, row) -> new HistoryEntry(rs.getString("id"), rs.getString("client_id"),
                rs.getString("activity_id"), rs.getString("field"), rs.getString("old_value"),
                rs.getString("new_value"), rs.getString("actor_user_id"), instant(rs, "created_at"), null),
                tenantSlug, UUID.fromString(clientId)));
    }

    @Override
    public List<ActivityType> activityTypes(String tenantSlug) {
        return inTenant(tenantSlug, () -> jdbc.query(activityTypeSelect() + " where t.slug = ? order by a.kind, a.name",
                this::mapActivityType, tenantSlug));
    }

    @Override
    public Optional<ActivityType> activityType(String tenantSlug, String activityTypeId) {
        UUID id = uuid(activityTypeId);
        if (id == null) {
            return Optional.empty();
        }
        return inTenant(tenantSlug, () -> jdbc.query(activityTypeSelect() + " where t.slug = ? and a.id = ?",
                this::mapActivityType, tenantSlug, id).stream().findFirst());
    }

    @Override
    public void seedActivityTypesOnce(String tenantSlug, List<ActivityType> starterSet) {
        inTenant(tenantSlug, () -> {
            UUID tenantId = tenantId(tenantSlug);
            int claimed = jdbc.update("insert into activity_type_catalogs (tenant_id) values (?) on conflict do nothing", tenantId);
            if (claimed == 1) {
                starterSet.forEach(type -> insertActivityType(tenantId, UUID.randomUUID(), type));
            }
            return null;
        });
    }

    @Override
    public ActivityType saveActivityType(String tenantSlug, ActivityType activityType) {
        UUID id = activityType.id() == null ? UUID.randomUUID() : UUID.fromString(activityType.id());
        inTenant(tenantSlug, () -> {
            insertActivityType(tenantId(tenantSlug), id, activityType);
            return null;
        });
        return activityType(tenantSlug, id.toString()).orElseThrow();
    }

    private void insertActivityType(UUID tenantId, UUID id, ActivityType type) {
        jdbc.update("""
                insert into activity_types (id, tenant_id, name, kind, default_turnaround_days, default_term_days, retired_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set
                  name = excluded.name, kind = excluded.kind,
                  default_turnaround_days = excluded.default_turnaround_days,
                  default_term_days = excluded.default_term_days,
                  retired_at = excluded.retired_at, updated_at = now()
                """, id, tenantId, type.name(), type.kind().name(), type.defaultTurnaroundDays(), type.defaultTermDays(),
                type.retiredAt() == null ? null : Timestamp.from(type.retiredAt()));
    }

    private String activityTypeSelect() {
        return """
                select a.id, a.name, a.kind, a.default_turnaround_days, a.default_term_days, a.retired_at
                from activity_types a join tenants t on t.id = a.tenant_id
                """;
    }

    private ActivityType mapActivityType(ResultSet rs, int row) throws SQLException {
        return new ActivityType(rs.getString("id"), rs.getString("name"), ActivityKind.valueOf(rs.getString("kind")),
                (Integer) rs.getObject("default_turnaround_days"), (Integer) rs.getObject("default_term_days"),
                instant(rs, "retired_at"));
    }

    @Override
    public Optional<Correspondence> recordCorrespondence(String tenantSlug, Correspondence entry) {
        return inTenant(tenantSlug, () -> jdbc.query("""
                insert into client_correspondence (
                  tenant_id, tenant_slug, client_id, contact_address, message_id, subject, body, received_at,
                  intent, outcome, answer_text, next_attempt_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, message_id) do nothing
                returning id
                """, (rs, row) -> rs.getString("id"), tenantId(tenantSlug), tenantSlug, UUID.fromString(entry.clientId()),
                entry.contactAddress(), entry.messageId(), entry.subject(), entry.body(),
                Timestamp.from(entry.receivedAt()), entry.intent(), entry.outcome(), entry.answerText(),
                timestamp(entry.nextAttemptAt())).stream().findFirst())
                .flatMap(id -> correspondence(tenantSlug, id));
    }

    @Override
    public Optional<Correspondence> correspondence(String tenantSlug, String correspondenceId) {
        UUID id = uuid(correspondenceId);
        if (id == null) {
            return Optional.empty();
        }
        return inTenant(tenantSlug, () -> jdbc.query(correspondenceSelect() + " where c.tenant_slug = ? and c.id = ?",
                this::mapCorrespondence, tenantSlug, id).stream().findFirst());
    }

    @Override
    public List<Correspondence> correspondenceForClient(String tenantSlug, String clientId) {
        return inTenant(tenantSlug, () -> jdbc.query(
                correspondenceSelect() + " where c.tenant_slug = ? and c.client_id = ? order by c.received_at desc",
                this::mapCorrespondence, tenantSlug, UUID.fromString(clientId)));
    }

    @Override
    public List<Correspondence> claimDueCorrespondence(int limit) {
        return inTenant(WORKER, () -> jdbc.query("""
                with claimed as (
                  update client_correspondence
                  set next_attempt_at = now() + interval '5 minutes'
                  where id in (
                    select id from client_correspondence
                    where outcome is null and next_attempt_at <= now()
                    order by received_at
                    limit ?
                    for update skip locked
                  )
                  returning *
                )
                """ + correspondenceSelect().replace("from client_correspondence c", "from claimed c")
                + " order by c.received_at", this::mapCorrespondence, Math.max(1, limit)));
    }

    @Override
    public void saveCorrespondence(String tenantSlug, Correspondence entry, List<NotificationOutboxItem> notifications) {
        inTenant(tenantSlug, () -> {
            jdbc.update("""
                    update client_correspondence
                    set intent = ?, outcome = ?, answer_text = ?, attempts = ?, next_attempt_at = ?
                    where id = ?
                    """, entry.intent(), entry.outcome(), entry.answerText(), entry.attempts(),
                    timestamp(entry.nextAttemptAt()), UUID.fromString(entry.id()));
            NotificationOutboxSql.insert(jdbc, tenantId(tenantSlug), tenantSlug, notifications);
            return null;
        });
    }

    @Override
    public Optional<String> engagedClientId(String tenantSlug, String consultationId) {
        UUID id = uuid(consultationId);
        if (id == null) {
            return Optional.empty();
        }
        return inTenant(tenantSlug, () -> jdbc.query("""
                select c.engaged_client_id from consultations c join tenants t on t.id = c.tenant_id
                where t.slug = ? and c.id = ? and c.engaged_client_id is not null
                """, (rs, row) -> rs.getString("engaged_client_id"), tenantSlug, id).stream().findFirst());
    }

    @Override
    public boolean engage(String tenantSlug, String consultationId, String clientId, List<HistoryEntry> history) {
        return inTenant(tenantSlug, () -> {
            int linked = jdbc.update("""
                    update consultations set engaged_client_id = ?
                    where id = ? and engaged_client_id is null
                    """, UUID.fromString(clientId), UUID.fromString(consultationId));
            if (linked == 0) {
                return false;
            }
            insertHistory(tenantId(tenantSlug), UUID.fromString(clientId), history);
            return true;
        });
    }

    @Override
    public List<Activity> activities(String tenantSlug, String clientId) {
        return inTenant(tenantSlug, () -> jdbc.query(activitySelect()
                        + " where a.tenant_slug = ? and a.client_id = ? order by a.done, a.start_date",
                this::mapActivity, tenantSlug, UUID.fromString(clientId)));
    }

    @Override
    public Optional<Activity> activity(String tenantSlug, String activityId) {
        UUID id = uuid(activityId);
        if (id == null) {
            return Optional.empty();
        }
        return inTenant(tenantSlug, () -> jdbc.query(activitySelect() + " where a.tenant_slug = ? and a.id = ?",
                this::mapActivity, tenantSlug, id).stream().findFirst());
    }

    @Override
    public Activity saveActivity(String tenantSlug, Activity activity, List<HistoryEntry> history) {
        UUID id = activity.id() == null ? UUID.randomUUID() : UUID.fromString(activity.id());
        inTenant(tenantSlug, () -> {
            UUID tenantId = tenantId(tenantSlug);
            jdbc.update("""
                    insert into activities (
                      id, tenant_id, tenant_slug, client_id, activity_type_id, kind, label, start_date, turnaround_days,
                      due_date, scheduled_date, term_days, assigned_lawyer_id, done, done_at,
                      overdue_nudged_for, term_nudged_for
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do update set
                      label = excluded.label, start_date = excluded.start_date, due_date = excluded.due_date,
                      scheduled_date = excluded.scheduled_date, term_days = excluded.term_days,
                      assigned_lawyer_id = excluded.assigned_lawyer_id,
                      done = excluded.done, done_at = excluded.done_at,
                      overdue_nudged_for = excluded.overdue_nudged_for, term_nudged_for = excluded.term_nudged_for,
                      updated_at = now()
                    """, id, tenantId, tenantSlug, UUID.fromString(activity.clientId()),
                    UUID.fromString(activity.activityTypeId()), activity.kind().name(), activity.label(),
                    activity.startDate(), activity.turnaroundDays(), activity.dueDate(), activity.scheduledDate(),
                    activity.termDays(), UUID.fromString(activity.assignedLawyerId()), activity.done(),
                    timestamp(activity.doneAt()), activity.overdueNudgedFor(), activity.termNudgedFor());
            insertHistory(tenantId, UUID.fromString(activity.clientId()), history.stream()
                    .map(entry -> HistoryEntry.of(activity.clientId(), id.toString(), entry.field(), entry.oldValue(),
                            entry.newValue(), entry.actorUserId()))
                    .toList());
            return null;
        });
        return activity(tenantSlug, id.toString()).orElseThrow();
    }

    @Override
    public List<Activity> openActivities(String tenantSlug) {
        return inTenant(tenantSlug, () -> jdbc.query(activitySelect() + " where a.tenant_slug = ? and not a.done",
                this::mapActivity, tenantSlug));
    }

    @Override
    public List<Activity> openActivitiesAcrossTenants() {
        // ponytail: reads every open Activity each sweep and lets Java decide which are due, because
        // Business Days are computed in Java; push a date prefilter into SQL if this grows large.
        return inTenant(WORKER, () -> jdbc.query(activitySelect() + " where not a.done", this::mapActivity));
    }

    @Override
    public boolean claimNudge(String tenantSlug, String activityId, NudgeTrigger trigger, LocalDate forDate,
            List<NotificationOutboxItem> notifications) {
        String column = trigger == NudgeTrigger.OVERDUE ? "overdue_nudged_for" : "term_nudged_for";
        return inTenant(tenantSlug, () -> {
            int claimed = jdbc.update("update activities set " + column + " = ? where id = ? and not done and "
                    + column + " is null", forDate, UUID.fromString(activityId));
            if (claimed == 0) {
                return false;
            }
            NotificationOutboxSql.insert(jdbc, tenantId(tenantSlug), tenantSlug, notifications);
            return true;
        });
    }

    @Override
    public int purgeCorrespondenceBodies(Instant cutoff) {
        // The forward copied the Client's words into the outbox, card and plaintext, so they go from there too.
        return inTenant(WORKER, () -> jdbc.queryForObject("""
                with purged as (
                  update client_correspondence set body = null, purged_at = now()
                  where received_at < ? and purged_at is null
                  returning id
                ), forwards as (
                  update notification_outbox set body = '(texto del cliente eliminado tras un año)', html_body = null
                  where notification_type = 'CLIENT_MAIL_FORWARD' and correspondence_id in (select id from purged)
                )
                select count(*) from purged
                """, Integer.class, Timestamp.from(cutoff)));
    }

    @Override
    public void deleteClient(String tenantSlug, String clientId) {
        // Everything else goes by foreign key: addresses, Activities, history, correspondence and
        // the mail about them cascade, and an engaged Consultation's link is set null.
        inTenant(tenantSlug, () -> jdbc.update("delete from clients where id = ?", UUID.fromString(clientId)));
    }

    private String activitySelect() {
        return """
                select a.id, a.tenant_slug, a.client_id, a.activity_type_id, t.name as type_name, a.kind, a.label,
                       a.start_date, a.turnaround_days, a.due_date, a.scheduled_date, a.term_days, a.assigned_lawyer_id,
                       a.done, a.done_at, a.overdue_nudged_for, a.term_nudged_for, a.created_at
                from activities a join activity_types t on t.id = a.activity_type_id
                """;
    }

    private Activity mapActivity(ResultSet rs, int row) throws SQLException {
        return new Activity(rs.getString("id"), rs.getString("tenant_slug"), rs.getString("client_id"),
                rs.getString("activity_type_id"), rs.getString("type_name"), ActivityKind.valueOf(rs.getString("kind")),
                rs.getString("label"),
                rs.getObject("start_date", LocalDate.class), (Integer) rs.getObject("turnaround_days"),
                rs.getObject("due_date", LocalDate.class), rs.getObject("scheduled_date", LocalDate.class),
                (Integer) rs.getObject("term_days"), rs.getString("assigned_lawyer_id"), rs.getBoolean("done"),
                instant(rs, "done_at"), rs.getObject("overdue_nudged_for", LocalDate.class),
                rs.getObject("term_nudged_for", LocalDate.class), instant(rs, "created_at"));
    }

    private String correspondenceSelect() {
        return """
                select c.id, c.tenant_slug, c.client_id, c.contact_address, c.message_id, c.subject, c.body,
                       c.received_at, c.intent, c.outcome, c.answer_text, c.purged_at, c.attempts, c.next_attempt_at
                from client_correspondence c
                """;
    }

    private Correspondence mapCorrespondence(ResultSet rs, int row) throws SQLException {
        return new Correspondence(rs.getString("id"), rs.getString("tenant_slug"), rs.getString("client_id"),
                rs.getString("contact_address"), rs.getString("message_id"), rs.getString("subject"),
                rs.getString("body"), instant(rs, "received_at"), rs.getString("intent"), rs.getString("outcome"),
                rs.getString("answer_text"), instant(rs, "purged_at"), rs.getInt("attempts"),
                instant(rs, "next_attempt_at"));
    }

    static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    void insertHistory(UUID tenantId, UUID clientId, List<HistoryEntry> history) {
        for (HistoryEntry entry : history) {
            jdbc.update("""
                    insert into activity_history (tenant_id, client_id, activity_id, field, old_value, new_value, actor_user_id)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, clientId, uuid(entry.activityId()), entry.field(), entry.oldValue(),
                    entry.newValue(), entry.actorUserId());
        }
    }

    private String clientSelect() {
        return """
                select c.id, c.name, c.kind, c.id_number, c.phone, c.notes, c.responsible_lawyer_id,
                       c.created_at, c.updated_at,
                       (select count(*) from activities a where a.client_id = c.id and not a.done) as open_activities
                from clients c join tenants t on t.id = c.tenant_id
                """;
    }

    private Client mapClient(ResultSet rs, int row) throws SQLException {
        return new Client(rs.getString("id"), rs.getString("name"), rs.getString("kind"), rs.getString("id_number"),
                rs.getString("phone"), rs.getString("notes"), rs.getString("responsible_lawyer_id"), List.of(),
                rs.getInt("open_activities"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    /** Runs inside the caller's tenant transaction. */
    private List<Client> withAddresses(List<Client> clients) {
        if (clients.isEmpty()) {
            return clients;
        }
        Map<String, List<String>> addresses = new HashMap<>();
        jdbc.query("select client_id, address from client_contact_addresses where client_id = any(?) order by created_at, address",
                (ResultSet rs) -> {
                    addresses.computeIfAbsent(rs.getString("client_id"), ignored -> new ArrayList<>()).add(rs.getString("address"));
                }, (Object) clients.stream().map(client -> UUID.fromString(client.id())).toArray(UUID[]::new));
        return clients.stream().map(client -> new Client(client.id(), client.name(), client.kind(), client.idNumber(),
                client.phone(), client.notes(), client.responsibleLawyerId(),
                List.copyOf(addresses.getOrDefault(client.id(), List.of())), client.openActivities(),
                client.createdAt(), client.updatedAt())).toList();
    }

    UUID tenantId(String tenantSlug) {
        return jdbc.queryForObject("select id from tenants where slug = ?", UUID.class, tenantSlug);
    }

    <T> T inTenant(String tenantSlug, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.queryForObject("select set_config('app.tenant_slug', ?, true)", String.class, tenantSlug);
            return work.get();
        });
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    static UUID uuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
