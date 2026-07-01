package com.legalgate.intake.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalgate.intake.billing.BillingModels.Coupon;
import com.legalgate.intake.billing.BillingModels.Payment;
import com.legalgate.intake.billing.BillingModels.Plan;
import com.legalgate.intake.billing.BillingModels.Subscription;
import com.legalgate.intake.billing.BillingModels.WebhookEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingRepository {
    List<Plan> activePlans();
    Optional<Plan> activePlan(String code);
    Optional<Coupon> validCoupon(String code, Instant now);
    Optional<Subscription> currentSubscription(String tenantSlug);
    Optional<Subscription> entitledSubscription(String tenantSlug, Instant now);
    Optional<Subscription> subscriptionByIdempotency(String tenantSlug, String idempotencyKey);
    Optional<Subscription> subscriptionByExternalReference(String externalReference);
    Optional<Subscription> subscriptionByProviderId(String providerId);
    Subscription createPending(
            String tenantSlug, Plan plan, Coupon coupon, BigDecimal amount,
            String payerEmail, String idempotencyKey, Instant expiresAt);
    void attachProvider(UUID subscriptionId, String tenantSlug, String providerId, String providerStatus, String initPoint);
    void updateProviderStatus(UUID subscriptionId, String tenantSlug, String providerStatus);
    void cancel(UUID subscriptionId, String tenantSlug, Instant canceledAt);
    List<Payment> payments(String tenantSlug, UUID subscriptionId);
    boolean insertWebhook(
            String providerEventId, String eventType, String action, String resourceId,
            String requestId, JsonNode rawBody);
    List<WebhookEvent> claimWebhookBatch(int limit);
    void completeWebhook(UUID eventId);
    void failWebhook(UUID eventId, int attempts, String error);
    void applyPayment(
            Subscription subscription, String paymentId, String authorizedPaymentId, String providerStatus,
            BigDecimal amount, Instant paidAt, Instant providerNextPayment, JsonNode payload,
            java.time.Duration gracePeriod);
    List<Subscription> claimReconciliationCandidates(Instant staleBefore, int limit);
    void completeReconciliation(Subscription subscription);
    void failReconciliation(Subscription subscription);
    void expirePendingAndGrace(Instant now);
    List<Subscription> claimAmountTransitionCandidates(int limit);
    void completeAmountTransition(Subscription subscription);
    void failAmountTransition(Subscription subscription, String error);
}

class CouponCapacityExceededException extends RuntimeException {
    CouponCapacityExceededException() {
        super("coupon_redemption_limit_reached");
    }
}
