package com.legalgate.intake.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalgate.intake.billing.BillingModels.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "legalgate.intake.persistence", havingValue = "memory", matchIfMissing = true)
class InMemoryBillingRepository implements BillingRepository {
    public List<Plan> activePlans() { return List.of(); }
    public Optional<Plan> activePlan(String code) { return Optional.empty(); }
    public Optional<Coupon> validCoupon(String code, Instant now) { return Optional.empty(); }
    public Optional<Subscription> currentSubscription(String tenantSlug) { return Optional.empty(); }
    public Optional<Subscription> subscriptionByIdempotency(String tenantSlug, String key) { return Optional.empty(); }
    public Optional<Subscription> subscriptionByExternalReference(String ref) { return Optional.empty(); }
    public Optional<Subscription> subscriptionByProviderId(String providerId) { return Optional.empty(); }
    public Subscription createPending(String slug, Plan p, Coupon c, BigDecimal a, String e, String k, Instant x) {
        throw new IllegalStateException("Billing requires JDBC persistence.");
    }
    public void attachProvider(UUID id, String slug, String providerId, String status, String initPoint) {}
    public void updateProviderStatus(UUID id, String slug, String status) {}
    public void cancel(UUID id, String slug, Instant at) {}
    public List<Payment> payments(String slug, UUID id) { return List.of(); }
    public boolean insertWebhook(String id, String type, String action, String resource, String request, JsonNode body) { return false; }
    public List<WebhookEvent> claimWebhookBatch(int limit) { return List.of(); }
    public void completeWebhook(UUID id) {}
    public void failWebhook(UUID id, int attempts, String error) {}
    public void applyPayment(Subscription s, String p, String a, String status, BigDecimal amount, Instant paid,
                             Instant next, JsonNode payload, Duration grace) {}
    public List<Subscription> reconciliationCandidates(Instant stale, int limit) { return List.of(); }
    public void expirePendingAndGrace(Instant now) {}
    public List<Subscription> amountTransitionCandidates(int limit) { return List.of(); }
    public void completeAmountTransition(Subscription s) {}
    public void failAmountTransition(Subscription s, String error) {}
}
