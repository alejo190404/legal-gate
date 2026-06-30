package com.legalgate.intake.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.billing.BillingModels.Coupon;
import com.legalgate.intake.billing.BillingModels.Plan;
import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.service.WorkosClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
