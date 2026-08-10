package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.classifier.ConsultationClassifierRequest;
import com.legalgate.intake.model.ClassificationResult;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.DiagnosticsMessage;
import com.legalgate.intake.model.DiagnosticsSession;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.model.NotificationStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/**
 * The diagnostics tables and their JDBC path against real Postgres: the SQL the in-memory
 * repository cannot exercise (RLS, the worker's claim query, partial-index dedupe, purge).
 */
@Testcontainers(disabledWithoutDocker = true)
class DiagnosticsMigrationPostgresTests {

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
    void aPendingConsultationPersistsWithNoEventAndItsSessionRoundTrips() {
        JdbcIntakeRepository repository = repository();
        ConsultationResponse pending = savePending(repository, "tenant-pending");

        DiagnosticsSession session = repository.saveDiagnosticsSession("tenant-pending",
                session(pending, "0123456789abcdef0123456789abcdef"),
                List.of(DiagnosticsMessage.fromLegalGate("Cual fue la fecha?")),
                List.of());

        assertThat(repository.consultationById("tenant-pending", pending.id()).orElseThrow().eventId()).isNull();
        assertThat(session.promptSnapshot()).isEqualTo("Tomamos casos laborales.");
        assertThat(session.originalEmail().subject()).isEqualTo("Consulta laboral");
        assertThat(repository.diagnosticsMessages("tenant-pending", session.id()))
                .extracting(DiagnosticsMessage::body)
                .containsExactly("Cual fue la fecha?");
    }

    @Test
    void theWorkerClaimsOnlySessionsThatAreDueAndLeasesThemOnce() {
        JdbcIntakeRepository repository = repository();
        ConsultationResponse due = savePending(repository, "tenant-due");
        ConsultationResponse waiting = savePending(repository, "tenant-waiting");
        repository.saveDiagnosticsSession("tenant-due",
                session(due, "11111111111111111111111111111111"), List.of(), List.of());
        DiagnosticsSession awaiting = repository.saveDiagnosticsSession("tenant-waiting",
                session(waiting, "22222222222222222222222222222222"), List.of(), List.of());
        repository.saveDiagnosticsSession("tenant-waiting",
                awaiting.awaitingReply(Instant.now(), "Falta la fecha.", "Resumen."), List.of(), List.of());

        List<DiagnosticsSession> claimed = repository.claimDueDiagnosticsSessions(10);

        assertThat(claimed).extracting(DiagnosticsSession::consultationId).containsExactly(due.id());
        assertThat(repository.claimDueDiagnosticsSessions(10)).isEmpty();
        assertThat(repository.silentDiagnosticsSessions(Instant.now().plus(Duration.ofDays(8)), 10))
                .extracting(DiagnosticsSession::consultationId)
                .containsExactly(waiting.id());
    }

    @Test
    void aReplyTokenResolvesItsSessionWithoutKnowingTheTenant() {
        JdbcIntakeRepository repository = repository();
        ConsultationResponse pending = savePending(repository, "tenant-token");
        repository.saveDiagnosticsSession("tenant-token",
                session(pending, "9f2c7a1e4b8d0356af71c2e5d8093b4a"), List.of(), List.of());

        DiagnosticsSession found = repository
                .diagnosticsSessionForReplyToken("9f2c7a1e4b8d0356af71c2e5d8093b4a").orElseThrow();

        assertThat(found.tenantId()).isEqualTo("tenant-token");
        assertThat(found.consultationId()).isEqualTo(pending.id());
    }

    @Test
    void diagnosticsNotificationsPersistWithoutAnEventOrCalendarInvite() {
        JdbcIntakeRepository repository = repository();
        ConsultationResponse pending = savePending(repository, "tenant-outbox");
        repository.saveDiagnosticsSession("tenant-outbox",
                session(pending, "33333333333333333333333333333333"), List.of(),
                List.of(new NotificationOutboxItem(pending.id(), null, "DIAGNOSTICS_QUESTION", "CLIENT",
                        "maria@example.com", "tenant-outbox+d33333333333333333333333333333333@intake.legal-gate.co",
                        "Necesitamos datos", "Cual fue la fecha?", null, null)));

        List<NotificationOutboxItem> claimed = repository.claimPendingNotifications(10);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).eventId()).isNull();
        assertThat(claimed.get(0).icsContent()).isNull();
        assertThat(claimed.get(0).fromEmail()).startsWith("tenant-outbox+d");
    }

    @Test
    void purgingDropsTheTranscriptButKeepsTheVerdictReasonAndPromptSnapshot() {
        JdbcIntakeRepository repository = repository();
        ConsultationResponse pending = savePending(repository, "tenant-purge");
        DiagnosticsSession session = repository.saveDiagnosticsSession("tenant-purge",
                session(pending, "44444444444444444444444444444444"),
                List.of(DiagnosticsMessage.fromClient("Mi problema es...")), List.of());
        repository.saveDiagnosticsSession("tenant-purge",
                session.resolved(DiagnosticsSession.REJECTED, "reject", "Fuera de alcance.", "Resumen.",
                        Instant.now().minus(Duration.ofDays(200))),
                List.of(), List.of());

        assertThat(repository.purgeDiagnosticsTranscripts(Instant.now().minus(Duration.ofDays(180)))).isEqualTo(1);

        assertThat(repository.diagnosticsMessages("tenant-purge", session.id())).isEmpty();
        DiagnosticsSession purged = repository
                .diagnosticsSessionForConsultation("tenant-purge", pending.id()).orElseThrow();
        assertThat(purged.verdict()).isEqualTo("reject");
        assertThat(purged.reason()).isEqualTo("Fuera de alcance.");
        assertThat(purged.promptSnapshot()).isEqualTo("Tomamos casos laborales.");
        assertThat(purged.originalEmail()).isNull();
        // A second sweep finds nothing left to purge.
        assertThat(repository.purgeDiagnosticsTranscripts(Instant.now())).isZero();
    }

    @Test
    void forcedRlsKeepsOneFirmsSessionsOutOfAnothersReach() throws Exception {
        JdbcIntakeRepository repository = repository();
        ConsultationResponse mine = savePending(repository, "tenant-rls-a");
        ConsultationResponse theirs = savePending(repository, "tenant-rls-b");
        repository.saveDiagnosticsSession("tenant-rls-a",
                session(mine, "55555555555555555555555555555555"), List.of(), List.of());
        repository.saveDiagnosticsSession("tenant-rls-b",
                session(theirs, "66666666666666666666666666666666"), List.of(), List.of());

        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            assertThat(sessionCountFor(connection, "tenant-rls-a")).isEqualTo(1);
            assertThat(sessionCountFor(connection, "tenant-rls-b")).isEqualTo(1);
            assertThat(sessionCountFor(connection, "__worker__")).isGreaterThanOrEqualTo(2);
            connection.rollback();
        }
    }

    private static int sessionCountFor(Connection connection, String tenant) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("select set_config('app.tenant_slug', '" + tenant + "', true)");
            try (ResultSet result = sql.executeQuery("select count(*) from diagnostics_sessions")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private ConsultationResponse savePending(JdbcIntakeRepository repository, String tenantSlug) {
        ConsultationResponse consultation = new ConsultationResponse(
                UUID.randomUUID().toString(), tenantSlug, "Maria Perez", "maria@example.com",
                "Me despidieron.", null, "DIAGNOSTICS_PENDING", "PENDING", null, null,
                new ClassificationResult("DIAGNOSTICS", List.of(), null, "Qualifying.", null),
                new NotificationStatus(false, false, null, null),
                null, "<" + UUID.randomUUID() + "@example.com>", Instant.now(), null, null);
        return repository.saveConsultation(tenantSlug, consultation, List.of(), List.of());
    }

    private DiagnosticsSession session(ConsultationResponse consultation, String replyToken) {
        Instant now = Instant.now();
        return new DiagnosticsSession(null, consultation.tenantId(), consultation.id(), replyToken,
                DiagnosticsSession.PENDING, null, null, null, "Tomamos casos laborales.",
                new ConsultationClassifierRequest.InboundEmail("Consulta laboral", "Me despidieron.", null,
                        "Maria Perez <maria@example.com>", List.of("firma@intake.legal-gate.co"), "<m@example.com>"),
                0, 0, now, null, null, null, now);
    }

    private JdbcIntakeRepository repository() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "intake_app", "intake_app");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new JdbcIntakeRepository(
                jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper());
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "intake_app", "intake_app");
    }
}
