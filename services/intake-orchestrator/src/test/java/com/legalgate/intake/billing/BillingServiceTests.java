package com.legalgate.intake.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.billing.BillingModels.Coupon;
import com.legalgate.intake.billing.BillingModels.Plan;
import com.legalgate.intake.billing.BillingModels.Subscription;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.service.WorkosClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentMatchers;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

class BillingServiceTests {
    private final BillingRepository repository = mock(BillingRepository.class);
    private final SubscriptionProviderClient provider = mock(SubscriptionProviderClient.class);
    private final WorkosClient workos = mock(WorkosClient.class);
    private BillingService service;
    private Plan monthly;

    @BeforeEach
    void setUp() {
        BillingProperties properties = new BillingProperties(
                true, false, "token", "secret", "https://api.example.test",
                "https://app.example.test/?billing=return", Duration.ofSeconds(3),
                Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofDays(7));
        IntakeProperties intake = mock(IntakeProperties.class);
        when(intake.persistence()).thenReturn("jdbc");
        service = new BillingService(
                properties, repository, mock(BillingAccessService.class),
                provider, workos,
                new ObjectMapper(), intake);
        monthly = new Plan(
                UUID.randomUUID(), "monthly", 1, "Monthly", null,
                "MONTHLY", new BigDecimal("100000.00"), 1);
        when(repository.activePlan("monthly")).thenReturn(Optional.of(monthly));
    }

    @Test
    void calculatesPercentageAndFixedCouponsFromServerCatalog() {
        Coupon percentage = new Coupon(
                UUID.randomUUID(), "WELCOME20", "PERCENTAGE", new BigDecimal("20"),
                "ONCE", null);
        when(repository.validCoupon("WELCOME20", Instant.now())).thenReturn(Optional.of(percentage));
        // Match any clock instant used by the service.
        when(repository.validCoupon(org.mockito.ArgumentMatchers.eq("WELCOME20"),
                org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(percentage));

        BillingModels.Quote quote = service.quote("monthly", "WELCOME20");

        assertThat(quote.discountAmountCop()).isEqualByComparingTo("20000.00");
        assertThat(quote.recurringAmountCop()).isEqualByComparingTo("80000.00");
        assertThat(quote.discountedCycles()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownCouponsAndNonPositivePrices() {
        when(repository.validCoupon(
                org.mockito.ArgumentMatchers.eq("EXPIRED"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.quote("monthly", "EXPIRED"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_coupon");

        Coupon tooLarge = new Coupon(
                UUID.randomUUID(), "TOO-LARGE", "FIXED", new BigDecimal("100000"),
                "FOREVER", null);
        when(repository.validCoupon(
                org.mockito.ArgumentMatchers.eq("TOO-LARGE"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(tooLarge));
        assertThatThrownBy(() -> service.quote("monthly", "TOO-LARGE"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("coupon_results_in_non_positive_price");
    }

    @Test
    void enabledBillingRequiresJdbcPersistence() {
        IntakeProperties intake = mock(IntakeProperties.class);
        when(intake.persistence()).thenReturn("memory");
        BillingProperties properties = new BillingProperties(
                true, false, "token", "secret", "https://api.example.test",
                "https://app.example.test", Duration.ofSeconds(3), Duration.ofMinutes(5),
                Duration.ofHours(24), Duration.ofDays(7));
        assertThatThrownBy(() -> new BillingService(
                properties, repository, mock(BillingAccessService.class),
                mock(SubscriptionProviderClient.class), mock(WorkosClient.class),
                new ObjectMapper(), intake))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGALGATE_INTAKE_PERSISTENCE=jdbc");
    }

    @Test
    void checkoutMapsCouponExpiryBetweenValidationAndInsertToBadRequest() {
        Coupon coupon = new Coupon(
                UUID.randomUUID(), "FLASH", "PERCENTAGE", new BigDecimal("10"),
                "ONCE", null);
        when(repository.validCoupon(
                org.mockito.ArgumentMatchers.eq("FLASH"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(coupon), Optional.empty());
        when(workos.userEmail("user-1")).thenReturn(Optional.of("payer@example.com"));

        assertThatThrownBy(() -> service.checkout(
                "tenant", "user-1", "monthly", "FLASH", "attempt-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_coupon");
    }

    @Test
    void checkoutMapsCouponCapacityRaceToBadRequest() {
        Coupon coupon = new Coupon(
                UUID.randomUUID(), "FLASH", "PERCENTAGE", new BigDecimal("10"),
                "ONCE", null);
        when(repository.validCoupon(
                org.mockito.ArgumentMatchers.eq("FLASH"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(coupon));
        when(workos.userEmail("user-1")).thenReturn(Optional.of("payer@example.com"));
        when(repository.createPending(
                org.mockito.ArgumentMatchers.eq("tenant"),
                org.mockito.ArgumentMatchers.eq(monthly),
                org.mockito.ArgumentMatchers.eq(coupon),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("payer@example.com"),
                org.mockito.ArgumentMatchers.eq("attempt-1"),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new CouponCapacityExceededException());

        assertThatThrownBy(() -> service.checkout(
                "tenant", "user-1", "monthly", "FLASH", "attempt-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_coupon");
    }

    @Test
    void cancelCallsProviderBeforeMarkingLocalSubscription() {
        Subscription subscription = activeSubscription();
        when(repository.currentSubscription("tenant")).thenReturn(Optional.of(subscription));

        service.cancel("tenant");

        InOrder ordered = inOrder(provider, repository);
        ordered.verify(provider).cancel("provider-subscription-1");
        ordered.verify(repository).cancel(
                ArgumentMatchers.eq(subscription.id()),
                ArgumentMatchers.eq("tenant"),
                ArgumentMatchers.any());
    }

    @Test
    void cancelLeavesLocalSubscriptionActiveWhenProviderFails() {
        Subscription subscription = activeSubscription();
        when(repository.currentSubscription("tenant")).thenReturn(Optional.of(subscription));
        doThrow(new RestClientException("mercado pago unreachable"))
                .when(provider).cancel("provider-subscription-1");

        assertThatThrownBy(() -> service.cancel("tenant"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("provider_cancel_failed");

        verify(repository, never()).cancel(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    private Subscription activeSubscription() {
        return new Subscription(
                UUID.randomUUID(), UUID.randomUUID(), "tenant", monthly, null,
                monthly.priceCop(), monthly.priceCop(), "ACTIVE", "authorized",
                "provider-subscription-1", "https://checkout.example.test",
                "payer@example.com", Instant.now(), Instant.now().plus(Duration.ofDays(30)),
                null, null, null, false, 1, false, "attempt-1", Instant.now());
    }
}
