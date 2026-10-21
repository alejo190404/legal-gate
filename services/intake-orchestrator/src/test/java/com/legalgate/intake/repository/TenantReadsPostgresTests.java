package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Reads never write: the console's list and lookup calls must not upsert the Tenant row on every request. */
@Testcontainers(disabledWithoutDocker = true)
class TenantReadsPostgresTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("legalgate")
                    .withUsername("legalgate")
                    .withPassword("legalgate");

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

    @Test
    void readsForAnUnknownTenantFindNothingAndCreateNoTenant() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "intake_app", "intake_app");
        JdbcIntakeRepository repository = new JdbcIntakeRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        String slug = "firma-desconocida";
        String id = UUID.randomUUID().toString();

        assertThat(repository.settingsFor(slug, null)).isNull();
        assertThat(repository.consultationsForTenant(slug, null, null, null, 50).items()).isEmpty();
        assertThat(repository.consultationById(slug, id)).isEmpty();
        assertThat(repository.consultationForEventId(slug, id)).isEmpty();
        assertThat(repository.consultationForSourceMessageId(slug, "<m1@mail>")).isEmpty();
        assertThat(repository.lawyersForTenant(slug)).isEmpty();
        assertThat(repository.eventsForLawyer(slug, id)).isEmpty();

        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement();
             ResultSet tenants = sql.executeQuery("select count(*) from tenants")) {
            tenants.next();
            assertThat(tenants.getInt(1)).isZero();
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
