package com.legalgate.intake.clients;

import static org.assertj.core.api.Assertions.*;

import com.legalgate.intake.clients.ClientsModels.Activity;
import com.legalgate.intake.clients.ClientsModels.ActivityType;
import com.legalgate.intake.clients.ClientsModels.Client;
import com.legalgate.intake.clients.ClientsModels.Correspondence;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.time.Instant;
import com.legalgate.intake.clients.ClientsModels.HistoryEntry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Seam 2 for Clients: the SQL the in-memory repository cannot exercise. */
@Testcontainers(disabledWithoutDocker = true)
class ClientsMigrationPostgresTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("legalgate")
                    .withUsername("legalgate")
                    .withPassword("legalgate");

    static final String LAWYER_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String LAWYER_B = "bbbbbbbb-0000-0000-0000-000000000001";

    @BeforeAll
    static void migrate() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    create role intake_app login password 'intake_app';
                    grant usage on schema public to intake_app;
                    grant select, insert, update, delete on all tables in schema public to intake_app;
                    grant usage, select on all sequences in schema public to intake_app;
                    grant execute on all functions in schema public to intake_app;
                    """);
        }
    }

    @BeforeEach
    void freshTenants() throws Exception {
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            sql.execute("truncate table tenants cascade");
            sql.execute("""
                    insert into tenants (slug, display_name) values ('tenant-a', 'Firma A'), ('tenant-b', 'Firma B');
                    insert into lawyers (id, tenant_id, display_name, email, active, default_event_duration_minutes)
                    select '%s', id, 'Ana', 'ana@a.co', true, 60 from tenants where slug = 'tenant-a';
                    insert into lawyers (id, tenant_id, display_name, email, active, default_event_duration_minutes)
                    select '%s', id, 'Beto', 'beto@b.co', true, 60 from tenants where slug = 'tenant-b';
                    """.formatted(LAWYER_A, LAWYER_B));
        }
    }

    @Test
    void aClientRoundTripsWithItsAddressesAndHistory() {
        JdbcClientsRepository repository = repository();

        Client saved = repository.saveClient("tenant-a", client(null, "Acme SAS", LAWYER_A, "gc@acme.co", "ceo@acme.co"),
                List.of(HistoryEntry.of(null, null, "client", null, "Acme SAS", "user_1")));

        assertThat(repository.client("tenant-a", saved.id()).orElseThrow().contactAddresses())
                .containsExactlyInAnyOrder("gc@acme.co", "ceo@acme.co");
        assertThat(repository.clientForContactAddress("tenant-a", "GC@ACME.CO")).map(Client::id).contains(saved.id());
        assertThat(repository.history("tenant-a", saved.id()))
                .extracting(HistoryEntry::field, HistoryEntry::actorUserId)
                .containsExactly(tuple("client", "user_1"));
    }

    @Test
    void theDatabaseHoldsAContactAddressForOneClientPerTenant() throws Exception {
        JdbcClientsRepository repository = repository();
        Client acme = repository.saveClient("tenant-a", client(null, "Acme SAS", LAWYER_A, "gc@acme.co"), List.of());
        Client other = repository.saveClient("tenant-a", client(null, "Otro", LAWYER_A, "otro@acme.co"), List.of());

        assertThatThrownBy(() -> repository.saveClient("tenant-a",
                client(other.id(), "Otro", LAWYER_A, "otro@acme.co", "GC@acme.co"), List.of()))
                .isInstanceOfSatisfying(ContactAddressTakenException.class,
                        taken -> assertThat(taken.clientName()).isEqualTo("Acme SAS"));
        // The constraint itself, past the repository's own check.
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            assertThatThrownBy(() -> sql.execute("""
                    insert into client_contact_addresses (tenant_id, client_id, address)
                    select tenant_id, id, 'GC@ACME.CO' from clients where id = '%s'
                    """.formatted(other.id()))).isInstanceOf(SQLException.class).hasMessageContaining("uq_client_contact_addresses");
        }
        // Another firm may have the same address on file.
        assertThatCode(() -> repository.saveClient("tenant-b", client(null, "Acme SAS", LAWYER_B, "gc@acme.co"), List.of()))
                .doesNotThrowAnyException();
        assertThat(acme.id()).isNotEqualTo(other.id());
    }

    @Test
    void forcedRlsKeepsOneFirmsClientsOutOfAnothersReach() throws Exception {
        JdbcClientsRepository repository = repository();
        repository.saveClient("tenant-a", client(null, "Acme SAS", LAWYER_A, "gc@acme.co"),
                List.of(HistoryEntry.of(null, null, "client", null, "Acme SAS", "user_1")));
        repository.saveClient("tenant-b", client(null, "Beta", LAWYER_B, "b@beta.co"),
                List.of(HistoryEntry.of(null, null, "client", null, "Beta", "user_2")));

        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            for (String table : List.of("clients", "client_contact_addresses", "activity_history")) {
                assertThat(countFor(connection, "tenant-a", table)).as(table).isEqualTo(1);
                assertThat(countFor(connection, "__worker__", table)).as(table).isEqualTo(2);
                assertThat(countFor(connection, "nobody", table)).as(table).isZero();
            }
            connection.rollback();
        }
        assertThat(repository.clients("tenant-b")).extracting(Client::name).containsExactly("Beta");
    }

    @Test
    void theStarterCatalogIsSeededOncePerTenantAndStaysInsideIt() throws Exception {
        JdbcClientsRepository repository = repository();

        repository.seedActivityTypesOnce("tenant-a", ClientsService.STARTER_CATALOG);
        repository.seedActivityTypesOnce("tenant-a", ClientsService.STARTER_CATALOG);
        ActivityType first = repository.activityTypes("tenant-a").get(0);
        repository.saveActivityType("tenant-a", new ActivityType(first.id(), first.name(), first.kind(),
                first.defaultTurnaroundDays(), first.defaultTermDays(), java.time.Instant.now()));

        assertThat(repository.activityTypes("tenant-a")).hasSize(12);
        assertThat(repository.activityType("tenant-a", first.id()).orElseThrow().retiredAt()).isNotNull();
        assertThat(repository.activityTypes("tenant-b")).isEmpty();
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            assertThat(countFor(connection, "tenant-b", "activity_types")).isZero();
            assertThat(countFor(connection, "tenant-b", "activity_type_catalogs")).isZero();
            assertThat(countFor(connection, "tenant-a", "activity_type_catalogs")).isEqualTo(1);
            connection.rollback();
        }
    }

    @Test
    void correspondenceIsRecordedOncePerMessageIdAndClaimedOnceByTheWorker() throws Exception {
        JdbcClientsRepository repository = repository();
        Client acme = repository.saveClient("tenant-a", client(null, "Acme SAS", LAWYER_A, "gc@acme.co"), List.of());

        Correspondence first = repository.recordCorrespondence("tenant-a", pending(acme, "<m-1@acme.co>")).orElseThrow();
        assertThat(repository.recordCorrespondence("tenant-a", pending(acme, "<m-1@acme.co>"))).isEmpty();

        List<Correspondence> claimed = repository.claimDueCorrespondence(10);
        assertThat(claimed).extracting(Correspondence::id).containsExactly(first.id());
        assertThat(claimed.get(0).tenantId()).isEqualTo("tenant-a");
        assertThat(repository.claimDueCorrespondence(10)).isEmpty();

        repository.saveCorrespondence("tenant-a", first.resolved(null, ClientsModels.FORWARDED, null), List.of(
                NotificationOutboxItem.forCorrespondence(first.id(), "CLIENT_RECEIPT", "CLIENT", "gc@acme.co",
                        "tenant-a@intake.legal-gate.co", null, "Re: Hola", "Recibimos su mensaje."),
                NotificationOutboxItem.forCorrespondence(first.id(), "CLIENT_MAIL_FORWARD", "LAWYER", "ana@a.co",
                        null, "gc@acme.co", "Correo de cliente", "Hola.")));

        assertThat(repository.correspondenceForClient("tenant-a", acme.id()))
                .extracting(Correspondence::outcome).containsExactly(ClientsModels.FORWARDED);
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery(
                     "select consultation_id, correspondence_id, reply_to from notification_outbox order by notification_type")) {
            rows.next();
            assertThat(rows.getString("consultation_id")).isNull();
            assertThat(rows.getString("correspondence_id")).isEqualTo(first.id());
            assertThat(rows.getString("reply_to")).isEqualTo("gc@acme.co");
        }
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            assertThat(countFor(connection, "tenant-b", "client_correspondence")).isZero();
            assertThat(countFor(connection, "tenant-a", "client_correspondence")).isEqualTo(1);
            connection.rollback();
        }
    }

    @Test
    void aConsultationIsEngagedOnce() throws Exception {
        JdbcClientsRepository repository = repository();
        Client acme = repository.saveClient("tenant-a", client(null, "Acme SAS", LAWYER_A, "gc@acme.co"), List.of());
        String consultationId = java.util.UUID.randomUUID().toString();
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    insert into consultations (id, tenant_id, client_name, client_email, summary, status, urgency,
                      classification, notifications, created_at)
                    select '%s', id, 'Maria', 'maria@example.com', 'Hola', 'RECEIVED', 'NORMAL', '{}', '{}', now()
                    from tenants where slug = 'tenant-a'
                    """.formatted(consultationId));
        }

        assertThat(repository.engage("tenant-a", consultationId, acme.id(),
                List.of(HistoryEntry.of(acme.id(), null, "engagement", null, consultationId, "user_1")))).isTrue();
        assertThat(repository.engage("tenant-a", consultationId, acme.id(), List.of())).isFalse();

        assertThat(repository.engagedClientId("tenant-a", consultationId)).contains(acme.id());
        assertThat(repository.engagedClientId("tenant-b", consultationId)).isEmpty();
        assertThat(repository.history("tenant-a", acme.id())).extracting(HistoryEntry::field).containsExactly("engagement");
    }

    @Test
    void activitiesCountTowardsTheirClientAndStayInsideTheirFirm() throws Exception {
        JdbcClientsRepository repository = repository();
        Client acme = repository.saveClient("tenant-a", client(null, "Acme SAS", LAWYER_A, "gc@acme.co"), List.of());
        repository.seedActivityTypesOnce("tenant-a", ClientsService.STARTER_CATALOG);
        ActivityType hearing = repository.activityTypes("tenant-a").stream()
                .filter(type -> type.name().equals("Audiencia")).findFirst().orElseThrow();

        Activity saved = repository.saveActivity("tenant-a", new Activity(null, "tenant-a", acme.id(), hearing.id(), null,
                ClientsModels.DATED, "Audiencia inicial", java.time.LocalDate.of(2026, 10, 1), null, null,
                java.time.LocalDate.of(2026, 10, 20), null, LAWYER_A, false, null, null, null, null),
                List.of(HistoryEntry.of(acme.id(), null, "activity", null, "Audiencia inicial", "user_1")));

        assertThat(saved.typeName()).isEqualTo("Audiencia");
        assertThat(saved.expectedDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 20));
        assertThat(repository.client("tenant-a", acme.id()).orElseThrow().openActivities()).isEqualTo(1);
        assertThat(repository.history("tenant-a", acme.id())).extracting(HistoryEntry::activityId).containsExactly(saved.id());
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            assertThat(countFor(connection, "tenant-b", "activities")).isZero();
            assertThat(countFor(connection, "tenant-a", "activities")).isEqualTo(1);
            assertThat(countFor(connection, "__worker__", "activities")).isEqualTo(1);
            connection.rollback();
        }
    }

    @Test
    void theReminderWorkerSeesEveryFirmsOpenWorkAndClaimsEachReminderOnce() throws Exception {
        JdbcClientsRepository repository = repository();
        Client acme = repository.saveClient("tenant-a", client(null, "Acme SAS", LAWYER_A, "gc@acme.co"), List.of());
        Client beta = repository.saveClient("tenant-b", client(null, "Beta", LAWYER_B, "b@beta.co"), List.of());
        Activity late = repository.saveActivity("tenant-a", turnaround(repository, "tenant-a", acme, LAWYER_A), List.of());
        repository.saveActivity("tenant-b", turnaround(repository, "tenant-b", beta, LAWYER_B), List.of());

        assertThat(repository.openActivitiesAcrossTenants()).extracting(Activity::tenantId)
                .containsExactlyInAnyOrder("tenant-a", "tenant-b");

        java.time.LocalDate due = late.expectedDate();
        List<NotificationOutboxItem> reminder = List.of(NotificationOutboxItem.forCorrespondence(null, "ACTIVITY_NUDGE",
                "LAWYER", "ana@a.co", null, null, "Actividad vencida", "LegalGate"));
        assertThat(repository.claimNudge("tenant-a", late.id(), ClientsRepository.NudgeTrigger.OVERDUE, due, reminder)).isTrue();
        assertThat(repository.claimNudge("tenant-a", late.id(), ClientsRepository.NudgeTrigger.OVERDUE, due, reminder)).isFalse();
        assertThat(repository.claimNudge("tenant-a", late.id(), ClientsRepository.NudgeTrigger.OVERDUE, due.plusDays(1), reminder)).isTrue();
        assertThat(repository.activity("tenant-a", late.id()).orElseThrow().overdueNudgedFor()).isEqualTo(due.plusDays(1));
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery("select count(*) from notification_outbox where notification_type = 'ACTIVITY_NUDGE'")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(2);
        }
    }

    static Activity turnaround(JdbcClientsRepository repository, String tenant, Client client, String lawyerId) {
        repository.seedActivityTypesOnce(tenant, ClientsService.STARTER_CATALOG);
        ActivityType type = repository.activityTypes(tenant).stream()
                .filter(candidate -> candidate.kind().equals(ClientsModels.TURNAROUND)).findFirst().orElseThrow();
        return new Activity(null, tenant, client.id(), type.id(), null, ClientsModels.TURNAROUND, null,
                java.time.LocalDate.of(2026, 1, 5), type.defaultTurnaroundDays(), null, null, null, lawyerId,
                false, null, null, null, null);
    }

    static Correspondence pending(Client client, String messageId) {
        return new Correspondence(null, "tenant-a", client.id(), "gc@acme.co", messageId, "Hola", "Hola.",
                Instant.now(), null, null, null, null, 0, Instant.now().minusSeconds(1));
    }

    static int countFor(Connection connection, String tenant, String table) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("select set_config('app.tenant_slug', '" + tenant + "', true)");
            try (ResultSet result = sql.executeQuery("select count(*) from " + table)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    static Client client(String id, String name, String lawyerId, String... addresses) {
        return new Client(id, name, ClientsModels.ORGANIZATION, null, null, null, lawyerId, List.of(addresses), 0, null, null);
    }

    static JdbcClientsRepository repository() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "intake_app", "intake_app");
        return new JdbcClientsRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "intake_app", "intake_app");
    }

}
