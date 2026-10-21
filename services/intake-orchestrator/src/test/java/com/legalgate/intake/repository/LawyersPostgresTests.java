package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.clients.ClientsRepository;
import com.legalgate.intake.clients.ClientsService;
import com.legalgate.intake.model.LawyerProfile;
import com.legalgate.intake.service.ClerkClient;
import com.legalgate.intake.service.EmailTemplateRenderer;
import com.legalgate.intake.service.FirmNameResolver;
import com.legalgate.intake.service.IntakeService;
import java.sql.Connection;
import java.sql.DriverManager;
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

/**
 * Saving the team against real Postgres: the foreign keys and the per-firm unique email the in-memory
 * repository does not enforce. The releasable guard is covered by LawyerGuardTests, so it sees no Clients here.
 */
@Testcontainers(disabledWithoutDocker = true)
class LawyersPostgresTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("legalgate")
                    .withUsername("legalgate")
                    .withPassword("legalgate");

    static final String TENANT = "firma-equipo";
    static final String NEW_ID = "44444444-4444-4444-4444-444444444444";

    IntakeService intakeService;

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
    void freshFirm() throws Exception {
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            sql.execute("truncate table tenants cascade");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "intake_app", "intake_app");
        JdbcIntakeRepository repository = new JdbcIntakeRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        intakeService = new IntakeService(repository, ClientsFixtures.properties(), null, new EmailTemplateRenderer(),
                new FirmNameResolver(repository, ClientsFixtures.properties()),
                new ClientsService(mock(ClientsRepository.class), repository, mock(ClerkClient.class)));
        saveLawyers(ClientsFixtures.lawyers().toArray(LawyerProfile[]::new));
    }

    @Test
    void aFormerLawyerWithFinishedWorkIsAddedBackUnderTheirEmail() throws Exception {
        finishedActivityFor(ClientsFixtures.ANA);
        saveLawyers(luis("luis@firm.co"));

        saveLawyers(new LawyerProfile(NEW_ID, "Ana Abogada", "ana@firm.co", true, 60, null), luis("luis@firm.co"));

        assertThat(intakeService.settingsForTenant(TENANT).lawyers())
                .extracting(LawyerProfile::id, LawyerProfile::active)
                .contains(tuple(ClientsFixtures.ANA, true))
                .doesNotContain(tuple(NEW_ID, true));
    }

    @Test
    void twoLawyersTradeEmailsInOneSave() {
        saveLawyers(ana("luis@firm.co"), luis("ana@firm.co"));

        assertThat(intakeService.settingsForTenant(TENANT).lawyers())
                .extracting(LawyerProfile::id, LawyerProfile::email)
                .contains(tuple(ClientsFixtures.ANA, "luis@firm.co"), tuple(ClientsFixtures.LUIS, "ana@firm.co"));
    }

    @Test
    void aNewLawyerTakesTheEmailAnotherGivesUpInTheSameSave() {
        saveLawyers(new LawyerProfile(NEW_ID, "Nueva Abogada", "ana@firm.co", true, 60, null),
                ana("ana.abogada@firm.co"), luis("luis@firm.co"));

        assertThat(intakeService.settingsForTenant(TENANT).lawyers())
                .extracting(LawyerProfile::id, LawyerProfile::email)
                .contains(tuple(NEW_ID, "ana@firm.co"), tuple(ClientsFixtures.ANA, "ana.abogada@firm.co"));
    }

    private void saveLawyers(LawyerProfile... lawyers) {
        intakeService.saveSettings(TENANT, ClientsFixtures.settingsRequest(null, List.of(lawyers)));
    }

    private static void finishedActivityFor(String lawyerId) throws SQLException {
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    with firm as (select id from tenants where slug = '%1$s'),
                    client as (
                        insert into clients (tenant_id, name, kind, responsible_lawyer_id)
                        select id, 'Acme SAS', 'ORGANIZATION', '%2$s' from firm returning id, tenant_id),
                    type as (
                        insert into activity_types (tenant_id, name, kind, default_turnaround_days)
                        select id, 'Contrato', 'TURNAROUND', 5 from firm returning id)
                    insert into activities (tenant_id, tenant_slug, client_id, activity_type_id, kind, start_date,
                        turnaround_days, assigned_lawyer_id, done, done_at)
                    select client.tenant_id, '%1$s', client.id, type.id, 'TURNAROUND', current_date, 5, '%3$s', true, now()
                    from client, type
                    """.formatted(TENANT, ClientsFixtures.LUIS, lawyerId));
        }
    }

    private static LawyerProfile ana(String email) {
        return new LawyerProfile(ClientsFixtures.ANA, "Ana Abogada", email, true, 60, null);
    }

    private static LawyerProfile luis(String email) {
        return new LawyerProfile(ClientsFixtures.LUIS, "Luis Litigante", email, true, 60, null);
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
