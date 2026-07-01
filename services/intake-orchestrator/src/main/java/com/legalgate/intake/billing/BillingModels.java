package com.legalgate.intake.billing;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BillingModels {
    private BillingModels() {
    }

    public record CheckoutRequest(@NotBlank String planCode, String couponCode) {
    }

    public record Plan(
            UUID id,
            String code,
            int version,
            String displayName,
            String description,
            String interval,
            BigDecimal priceCop,
            int displayOrder
    ) {
    }

    public record Coupon(
            UUID id,
            String code,
            String discountType,
            BigDecimal discountValue,
            String duration,
            Integer durationCycles
    ) {
    }

    public record Quote(
            Plan plan,
            String couponCode,
            BigDecimal originalAmountCop,
            BigDecimal recurringAmountCop,
            BigDecimal discountAmountCop,
            String currency,
            String couponDuration,
            Integer discountedCycles
    ) {
    }

    public record CheckoutResponse(
            UUID subscriptionId,
            String status,
            String checkoutUrl,
            Instant expiresAt
    ) {
    }

    public record Payment(
            String providerPaymentId,
            String status,
            BigDecimal amountCop,
            Instant paidAt,
            Instant periodStart,
            Instant periodEnd
    ) {
    }

    public record Subscription(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            Plan plan,
            Coupon coupon,
            BigDecimal originalAmountCop,
            BigDecimal currentAmountCop,
            String status,
            String providerStatus,
            String providerSubscriptionId,
            String checkoutUrl,
            String payerEmail,
            Instant currentPeriodStart,
            Instant paidThrough,
            Instant graceDeadline,
            Instant pendingExpiresAt,
            Instant canceledAt,
            boolean cancelAtPeriodEnd,
            int approvedCycleCount,
            boolean amountTransitionPending,
            String idempotencyKey,
            Instant createdAt
    ) {
    }

    public record Status(
            boolean billingEnabled,
            boolean enforcementEnabled,
            boolean entitled,
            String status,
            Subscription subscription,
            List<Payment> payments,
            Instant accessEndsAt,
            String message
    ) {
    }

    public record WebhookEvent(
            UUID id,
            String eventType,
            String action,
            String resourceId,
            String rawBody,
            int attempts
    ) {
    }
}
