package com.legalgate.intake.clients;

import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
                order by h.created_at, h.id
                """, (rs, row) -> new HistoryEntry(rs.getString("id"), rs.getString("client_id"),
                rs.getString("activity_id"), rs.getString("field"), rs.getString("old_value"),
                rs.getString("new_value"), rs.getString("actor_user_id"), instant(rs, "created_at")),
                tenantSlug, UUID.fromString(clientId)));
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
                       c.created_at, c.updated_at, 0 as open_activities
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
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
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
