package com.legalgate.intake.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.legalgate.intake.billing.BillingModels.Plan;
import com.legalgate.intake.billing.BillingModels.Subscription;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillingAccessServiceTests {
    private final BillingRepository repository = mock(BillingRepository.class);
    private final BillingAccessService access = new BillingAccessService(
            new BillingProperties(
                    true, true, "token", "secret", "https://api.example.test",
                    "https://app.example.test", Duration.ofSeconds(3), Duration.ofMinutes(5),
                    Duration.ofHours(24), Duration.ofDays(7)),
            repository);

    @Test
    void blocksMissingSubscriptionsAndHonorsPastDueGrace() {
        when(repository.currentSubscription("tenant")).thenReturn(Optional.empty());
        assertThat(access.status("tenant").entitled()).isFalse();

        Subscription withinGrace = subscription(
                "PAST_DUE", Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(6)));
        when(repository.currentSubscription("tenant")).thenReturn(Optional.of(withinGrace));
        when(repository.payments("tenant", withinGrace.id())).thenReturn(List.of());
        assertThat(access.status("tenant").entitled()).isTrue();

        Subscription expiredGrace = subscription(
                "PAST_DUE", Instant.now().minus(Duration.ofDays(8)), Instant.now().minusSeconds(1));
        when(repository.currentSubscription("tenant")).thenReturn(Optional.of(expiredGrace));
        when(repository.payments("tenant", expiredGrace.id())).thenReturn(List.of());
        assertThat(access.status("tenant").entitled()).isFalse();
    }

    @Test
    void canceledRenewalRetainsAccessOnlyThroughPaidPeriod() {
        Subscription canceled = subscription(
                "CANCELED", Instant.now().plus(Duration.ofDays(3)), null);
        when(repository.currentSubscription("tenant")).thenReturn(Optional.of(canceled));
        when(repository.payments("tenant", canceled.id())).thenReturn(List.of());

        assertThat(access.status("tenant").entitled()).isTrue();
        assertThat(access.status("tenant").accessEndsAt()).isEqualTo(canceled.paidThrough());
    }

    private Subscription subscription(String status, Instant paidThrough, Instant graceDeadline) {
        Plan plan = new Plan(
                UUID.randomUUID(), "monthly", 1, "Monthly", null,
                "MONTHLY", new BigDecimal("100000"), 1);
        return new Subscription(
                UUID.randomUUID(), UUID.randomUUID(), "tenant", plan, null,
                plan.priceCop(), plan.priceCop(), status, "authorized", "provider-id", null,
                "payer@example.com", Instant.now().minus(Duration.ofDays(1)), paidThrough,
                graceDeadline, null, "CANCELED".equals(status) ? Instant.now() : null,
                "CANCELED".equals(status), 1, false, "key", Instant.now().minus(Duration.ofDays(10)));
    }
}
