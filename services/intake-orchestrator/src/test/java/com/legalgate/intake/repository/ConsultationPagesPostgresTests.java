package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.EventResponse;
import com.legalgate.intake.model.Page;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Bandeja's pages and per-Stage counts, as Postgres computes them. */
@Testcontainers(disabledWithoutDocker = true)
class ConsultationPagesPostgresTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("legalgate")
                    .withUsername("legalgate")
                    .withPassword("legalgate");

    static final String TENANT = "firma-bandeja";
    static final Instant START = Instant.parse("2026-10-01T10:00:00Z");

    JdbcIntakeRepository repository;

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
        repository = new JdbcIntakeRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
    }

    @Test
    void pagesRunNewestFirstAndAConsultationArrivingMidwayIsNeitherSkippedNorRepeated() {
        String oldest = save("RECEIVED", START).id();
        String middle = save("RECEIVED", START.plusSeconds(60)).id();
        String newest = save("RECEIVED", START.plusSeconds(120)).id();

        Page<ConsultationResponse> first = repository.consultationsForTenant(TENANT, null, null, null, 2);
        save("RECEIVED", START.plusSeconds(180));
        Page<ConsultationResponse> second = repository.consultationsForTenant(TENANT, null, null, first.nextCursor(), 2);

        assertThat(first.items()).extracting(ConsultationResponse::id).containsExactly(newest, middle);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.items()).extracting(ConsultationResponse::id).containsExactly(oldest);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void consultationsCreatedInTheSameInstantArePagedByIdWithoutSkippingAny() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(save("RECEIVED", START).id());
        }

        Page<ConsultationResponse> first = repository.consultationsForTenant(TENANT, null, null, null, 2);
        Page<ConsultationResponse> second = repository.consultationsForTenant(TENANT, null, null, first.nextCursor(), 2);

        List<String> paged = new ArrayList<>(first.items().stream().map(ConsultationResponse::id).toList());
        second.items().forEach(consultation -> paged.add(consultation.id()));
        assertThat(paged).containsExactlyInAnyOrderElementsOf(ids).doesNotHaveDuplicates();
    }

    // One Consultation per rule in ConsultationStageTests: the SQL that filters and counts by Stage must
    // agree with ConsultationResponse.stage(), which the console shows on each row.
    @Test
    void theServerFiltersAndCountsByTheSameStageEachConsultationReports() throws Exception {
        save("DIAGNOSTICS_PENDING", null, null, null);
        save("DIAGNOSTICS_REJECTED", null, null, null);
        save("DIAGNOSTICS_ABANDONED", null, null, null);
        save("LLM_CLASSIFIED", null, null, scheduledEvent(null, "TENTATIVE", false));
        save("LLM_CLASSIFIED", null, null, scheduledEvent(null, "TENTATIVE", null));
        save("LLM_CLASSIFIED", null, null, scheduledEvent(null, "TENTATIVE", true));
        save("LLM_CLASSIFIED", null, null, scheduledEvent(null, "CONFIRMED", false));
        save("CONFIRMED", null, null, scheduledEvent(null, "TENTATIVE", false));
        save("LLM_CLASSIFIED", null, null, unscheduledEvent(lawyer()));
        save("LLM_CLASSIFIED", "laura@firma.co", confidence(0.2), null);
        save("LLM_CLASSIFIED", null, confidence(0.49), null);
        save("LLM_CLASSIFIED", null, confidence(0.5), null);
        save("LLM_FAILED", null, null, null);
        save("RECEIVED", null, null, null);

        assertThat(repository.stageCounts(TENANT)).isEqualTo(Map.of(
                "diagnostics", 1L, "rejected", 1L, "abandoned", 1L, "scheduled", 2L,
                "confirmed", 3L, "classified", 2L, "unrouted", 2L, "new", 2L));
        for (String stage : List.of("diagnostics", "rejected", "abandoned", "scheduled", "confirmed", "classified",
                "unrouted", "new")) {
            assertThat(repository.consultationsForTenant(TENANT, stage, null, null, 50).items())
                    .as(stage)
                    .isNotEmpty()
                    .allSatisfy(consultation -> assertThat(consultation.stage()).isEqualTo(stage));
        }
    }

    @Test
    void aStagePagesWithItsOwnCursor() {
        String oldest = save("LLM_FAILED", START).id();
        save("RECEIVED", START.plusSeconds(60));
        String middle = save("LLM_FAILED", START.plusSeconds(120)).id();
        String newest = save("LLM_FAILED", START.plusSeconds(180)).id();

        Page<ConsultationResponse> first = repository.consultationsForTenant(TENANT, "unrouted", null, null, 2);
        Page<ConsultationResponse> second = repository.consultationsForTenant(TENANT, "unrouted", null, first.nextCursor(), 2);

        assertThat(first.items()).extracting(ConsultationResponse::id).containsExactly(newest, middle);
        assertThat(second.items()).extracting(ConsultationResponse::id).containsExactly(oldest);
        assertThat(second.nextCursor()).isNull();
    }

    // Bandeja's search box: the whole firm, not just the loaded pages, by client, mail, id or summary.
    @Test
    void searchRunsInTheDatabaseWithTheStageAndPagesTheSameWay() {
        String byName = save("LLM_FAILED", START, "Perez Gomez", "ana@example.com", "Contrato").id();
        String byMail = save("LLM_FAILED", START.plusSeconds(60), "Ana", "PEREZ@firma.co", "Contrato").id();
        String bySummary = save("LLM_FAILED", START.plusSeconds(120), "Ana", "ana@example.com", "Despido de perez").id();
        save("RECEIVED", START.plusSeconds(180), "Perez", "ana@example.com", "Contrato");
        save("LLM_FAILED", START.plusSeconds(240), "Ana", "ana@example.com", "Contrato");
        String byId = save("LLM_FAILED", START.plusSeconds(300), "Ana", "ana@example.com", "Contrato").id();

        Page<ConsultationResponse> first = repository.consultationsForTenant(TENANT, "unrouted", " pErEz ", null, 2);
        Page<ConsultationResponse> second = repository.consultationsForTenant(TENANT, "unrouted", " pErEz ", first.nextCursor(), 2);

        assertThat(first.items()).extracting(ConsultationResponse::id).containsExactly(bySummary, byMail);
        assertThat(second.items()).extracting(ConsultationResponse::id).containsExactly(byName);
        assertThat(second.nextCursor()).isNull();
        assertThat(repository.consultationsForTenant(TENANT, null, byId.substring(0, 8), null, 50).items())
                .extracting(ConsultationResponse::id).containsExactly(byId);
        assertThat(repository.consultationsForTenant(TENANT, null, "100%", null, 50).items()).isEmpty();
    }

    // Users in Colombia often type without accents: "fontibon" finds "Fontibón" and back.
    @Test
    void searchIgnoresAccentsBothWays() {
        String accented = save("RECEIVED", START, "José", "ana@example.com", "Arrendamiento de bodega en Fontibón").id();
        String plain = save("RECEIVED", START.plusSeconds(60), "Ana", "ana@example.com", "Bodega en Fontibon").id();

        assertThat(repository.consultationsForTenant(TENANT, null, "FONTIBON", null, 50).items())
                .extracting(ConsultationResponse::id).containsExactly(plain, accented);
        assertThat(repository.consultationsForTenant(TENANT, null, "fontibón", null, 50).items())
                .extracting(ConsultationResponse::id).containsExactly(plain, accented);
        assertThat(repository.consultationsForTenant(TENANT, null, "jose", null, 50).items())
                .extracting(ConsultationResponse::id).containsExactly(accented);
    }

    private ConsultationResponse save(String status, Instant createdAt, String clientName, String clientEmail, String summary) {
        return repository.saveConsultation(TENANT, new ConsultationResponse(UUID.randomUUID().toString(), TENANT,
                clientName, clientEmail, summary, null, status, "NORMAL", null, null, null, null, null,
                null, createdAt));
    }

    private ConsultationResponse save(String status, Instant createdAt) {
        return repository.saveConsultation(TENANT, new ConsultationResponse(UUID.randomUUID().toString(), TENANT,
                "Ana", "ana@example.com", "Contrato", null, status, "NORMAL", null, null, null, null, null,
                null, createdAt));
    }

    private void save(String status, String assignedLawyerEmail, ClassificationResult classification, EventResponse event) {
        repository.saveConsultation(TENANT, new ConsultationResponse(UUID.randomUUID().toString(), TENANT, "Ana",
                "ana@example.com", "Contrato", null, status, "NORMAL", null, assignedLawyerEmail, classification,
                null, null, null, START, event == null ? null : event.id(), event));
    }

    /** A lawyer of the firm; the firm itself exists once its first Consultation is saved. */
    private String lawyer() throws SQLException {
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement();
             ResultSet row = sql.executeQuery("insert into lawyers (tenant_id, display_name, email) "
                     + "select id, 'Laura', 'laura@firma.co' from tenants where slug = '" + TENANT + "' returning id")) {
            row.next();
            return row.getString(1);
        }
    }

    private static ClassificationResult confidence(double confidence) {
        return new ClassificationResult("Laboral", List.of(), "despido", "", confidence);
    }

    private static EventResponse unscheduledEvent(String lawyerId) {
        return new EventResponse(UUID.randomUUID().toString(), lawyerId, null, null, "Laboral", "Normal", 5,
                Instant.parse("2026-10-08T23:00:00Z"), 10, null, null, null, null, "TENTATIVE", "LEGALGATE");
    }

    private static EventResponse scheduledEvent(String lawyerId, String status, Boolean withinSla) {
        Instant start = Instant.parse("2026-10-05T15:00:00Z");
        return new EventResponse(UUID.randomUUID().toString(), lawyerId, null, null, "Laboral", "Normal", 5,
                Instant.parse("2026-10-08T23:00:00Z"), 10, start, start.plusSeconds(1800), null, withinSla,
                status, "LEGALGATE");
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
