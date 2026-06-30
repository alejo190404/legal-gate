package com.legalgate.intake.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

@Testcontainers(disabledWithoutDocker = true)
class BillingMigrationPostgresTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("legalgate")
                    .withUsername("legalgate")
                    .withPassword("legalgate");

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    create role billing_app login password 'billing_app';
                    grant usage on schema public to billing_app;
                    grant select, insert, update, delete on all tables in schema public to billing_app;
                    grant usage, select on all sequences in schema public to billing_app;
                    grant execute on all functions in schema public to billing_app;
                    insert into tenants (slug, display_name, provisioning_status)
                    values ('tenant-a', 'Tenant A', 'ACTIVE'), ('tenant-b', 'Tenant B', 'ACTIVE');
                    insert into billing_plans
                      (code, version, display_name, billing_interval, price_cop)
                    values ('monthly', 1, 'Monthly', 'MONTHLY', 100000);
                    """);
            insertSubscription(sql, "tenant-a", "provider-a", "key-a");
            insertSubscription(sql, "tenant-b", "provider-b", "key-b");
        }
    }

    @Test
    void migrationEnforcesCurrentSubscriptionUniquenessAndPlanImmutability() throws Exception {
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            assertThatThrownBy(() -> insertSubscription(sql, "tenant-a", "provider-a-2", "key-a-2"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_subscriptions_current_per_tenant");
            assertThatThrownBy(() -> sql.execute(
                    "update billing_plans set price_cop = 120000 where code = 'monthly'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("billing plans are immutable");
        }
    }

    @Test
    void forcedRlsIsolatesTenantsAndWorkerCanSeeAll() throws Exception {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            assertThat(countForContext(connection, "tenant-a")).isEqualTo(1);
            assertThat(countForContext(connection, "tenant-b")).isEqualTo(1);
            assertThat(countForContext(connection, "__worker__")).isEqualTo(2);
            connection.rollback();
        }
    }

    @Test
    void restrictedProviderLookupResolvesBeforeTenantContextIsKnown() throws Exception {
        try (Connection connection = appConnection(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute("select set_config('app.tenant_slug', 'tenant-a', true)");
            try (ResultSet result = sql.executeQuery(
                    "select tenant_slug from app_billing_tenant_for_provider_subscription('provider-b')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("tenant-b");
            }
            connection.rollback();
        }
    }

    @Test
    void duplicatedTenantIdentityCannotDiverge() throws Exception {
        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            assertThatThrownBy(() -> sql.execute("""
                    insert into subscriptions (
                      tenant_id, tenant_slug, plan_id, plan_code, plan_name, plan_interval,
                      plan_price_cop, original_amount_cop, current_amount_cop, status,
                      payer_email, idempotency_key
                    )
                    select a.id, b.slug, p.id, p.code, p.display_name, p.billing_interval,
                           p.price_cop, p.price_cop, p.price_cop, 'CANCELED',
                           'payer@example.com', 'mismatched-subscription'
                    from tenants a, tenants b, billing_plans p
                    where a.slug = 'tenant-a' and b.slug = 'tenant-b' and p.code = 'monthly'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("subscriptions_tenant_identity_fk");

            assertThatThrownBy(() -> sql.execute("""
                    insert into subscription_payments (
                      tenant_id, tenant_slug, subscription_id, provider_payment_id, provider_status
                    )
                    select b.id, b.slug, s.id, 'mismatched-payment', 'approved'
                    from tenants b, subscriptions s
                    where b.slug = 'tenant-b' and s.provider_subscription_id = 'provider-a'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("subscription_payments_tenant_identity_fk");
        }
    }

    @Test
    void reconciliationAndAmountTransitionsAreClaimedAcrossReplicas() throws Exception {
        JdbcBillingRepository firstReplica = billingRepository();
        JdbcBillingRepository secondReplica = billingRepository();

        assertThat(firstReplica.claimReconciliationCandidates(java.time.Instant.now(), 1)).hasSize(1);
        assertThat(secondReplica.claimReconciliationCandidates(java.time.Instant.now(), 10)).hasSize(1);
        assertThat(firstReplica.claimReconciliationCandidates(java.time.Instant.now(), 10)).isEmpty();

        try (Connection connection = ownerConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    update subscriptions
                    set amount_transition_pending = true
                    where provider_subscription_id = 'provider-a'
                    """);
        }

        assertThat(firstReplica.claimAmountTransitionCandidates(1)).hasSize(1);
        assertThat(secondReplica.claimAmountTransitionCandidates(1)).isEmpty();
    }

    private static int countForContext(Connection connection, String tenant) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("select set_config('app.tenant_slug', '" + tenant + "', true)");
            try (ResultSet result = sql.executeQuery("select count(*) from subscriptions")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void insertSubscription(
            Statement sql, String tenantSlug, String providerId, String idempotencyKey
    ) throws SQLException {
        sql.execute("""
                insert into subscriptions (
                  tenant_id, tenant_slug, plan_id, plan_code, plan_name, plan_interval,
                  plan_price_cop, original_amount_cop, current_amount_cop, status,
                  provider_subscription_id, payer_email, idempotency_key
                )
                select t.id, t.slug, p.id, p.code, p.display_name, p.billing_interval,
                       p.price_cop, p.price_cop, p.price_cop, 'ACTIVE', '%s',
                       'payer@example.com', '%s'
                from tenants t cross join billing_plans p
                where t.slug = '%s' and p.code = 'monthly'
                """.formatted(providerId, idempotencyKey, tenantSlug));
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "billing_app", "billing_app");
    }

    private static JdbcBillingRepository billingRepository() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "billing_app", "billing_app");
        return new JdbcBillingRepository(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }
}
