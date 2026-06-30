package com.legalgate.intake.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalgate.intake.billing.BillingModels.Coupon;
import com.legalgate.intake.billing.BillingModels.Payment;
import com.legalgate.intake.billing.BillingModels.Plan;
import com.legalgate.intake.billing.BillingModels.Subscription;
import com.legalgate.intake.billing.BillingModels.WebhookEvent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name = "legalgate.intake.persistence", havingValue = "jdbc")
class JdbcBillingRepository implements BillingRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcBillingRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<Plan> activePlans() {
        return jdbc.query("""
                select id, code, version, display_name, description, billing_interval, price_cop, display_order
                from billing_plans where active
                order by display_order, code, version desc
                """, this::mapPlan);
    }

    @Override
    public Optional<Plan> activePlan(String code) {
        if (code == null) return Optional.empty();
        return jdbc.query("""
                select id, code, version, display_name, description, billing_interval, price_cop, display_order
                from billing_plans where code = lower(?) and active
                limit 1
                """, this::mapPlan, code.trim()).stream().findFirst();
    }

    @Override
    public Optional<Coupon> validCoupon(String code, Instant now) {
        if (code == null || code.isBlank()) return Optional.empty();
        return jdbc.query("""
                select id, code, discount_type, discount_value, duration, duration_cycles
                from coupons
                where upper(code) = upper(?) and active
                  and (valid_from is null or valid_from <= ?)
                  and (valid_until is null or valid_until > ?)
                  and (max_redemptions is null or redemption_count < max_redemptions)
                limit 1
                """, this::mapCoupon, code.trim(), Timestamp.from(now), Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    public Optional<Subscription> currentSubscription(String tenantSlug) {
        return inTenant(tenantSlug, () -> jdbc.query(subscriptionSelect() + """
                where s.tenant_slug = ? and s.status in ('PENDING','ACTIVE','PAST_DUE','CANCELED')
                order by s.created_at desc limit 1
                """, this::mapSubscription, tenantSlug).stream().findFirst());
    }

    @Override
    public Optional<Subscription> subscriptionByIdempotency(String tenantSlug, String key) {
        return inTenant(tenantSlug, () -> jdbc.query(subscriptionSelect() + """
                where s.tenant_slug = ? and s.idempotency_key = ?
                limit 1
                """, this::mapSubscription, tenantSlug, key).stream().findFirst());
    }

    @Override
    public Optional<Subscription> subscriptionByExternalReference(String externalReference) {
        if (externalReference == null || externalReference.isBlank()) return Optional.empty();
        return inWorker(() -> jdbc.query(subscriptionSelect() + """
                where s.id = cast(? as uuid) limit 1
                """, this::mapSubscription, externalReference).stream().findFirst());
    }

    @Override
    public Optional<Subscription> subscriptionByProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) return Optional.empty();
        return inWorker(() -> jdbc.query(subscriptionSelect() + """
                where s.provider_subscription_id = ? limit 1
                """, this::mapSubscription, providerId).stream().findFirst());
    }

    @Override
    public Subscription createPending(
            String tenantSlug, Plan plan, Coupon coupon, BigDecimal amount,
            String payerEmail, String idempotencyKey, Instant expiresAt
    ) {
        return inTenant(tenantSlug, () -> {
            UUID tenantId = jdbc.queryForObject("select id from tenants where slug = ?", UUID.class, tenantSlug);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into subscriptions (
                      id, tenant_id, tenant_slug, plan_id, plan_code, plan_name, plan_interval,
                      plan_price_cop, coupon_id, coupon_code, coupon_type, coupon_value,
                      coupon_duration, coupon_duration_cycles, original_amount_cop,
                      current_amount_cop, status, payer_email, pending_expires_at, idempotency_key
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                    """,
                    id, tenantId, tenantSlug, plan.id(), plan.code(), plan.displayName(), plan.interval(),
                    plan.priceCop(), coupon == null ? null : coupon.id(), coupon == null ? null : coupon.code(),
                    coupon == null ? null : coupon.discountType(), coupon == null ? null : coupon.discountValue(),
                    coupon == null ? null : coupon.duration(), coupon == null ? null : coupon.durationCycles(),
                    plan.priceCop(), amount, payerEmail, Timestamp.from(expiresAt), idempotencyKey);
            if (coupon != null) {
                jdbc.update("update coupons set redemption_count = redemption_count + 1 where id = ?", coupon.id());
            }
            return subscriptionByIdempotency(tenantSlug, idempotencyKey).orElseThrow();
        });
    }

    @Override
    public void attachProvider(UUID id, String tenantSlug, String providerId, String providerStatus, String initPoint) {
        inTenant(tenantSlug, () -> {
            jdbc.update("""
                    update subscriptions set provider_subscription_id = ?, provider_status = ?,
                      provider_init_point = ?, last_provider_sync_at = now(), updated_at = now()
                    where id = ?
                    """, providerId, providerStatus, initPoint, id);
            return null;
        });
    }

    @Override
    public void updateProviderStatus(UUID id, String tenantSlug, String providerStatus) {
        inTenant(tenantSlug, () -> {
            jdbc.update("""
                    update subscriptions set provider_status = ?, last_provider_sync_at = now(), updated_at = now()
                    where id = ?
                    """, providerStatus, id);
            return null;
        });
    }

    @Override
    public void cancel(UUID id, String tenantSlug, Instant canceledAt) {
        inTenant(tenantSlug, () -> {
            jdbc.update("""
                    update subscriptions
                    set status = 'CANCELED', provider_status = 'canceled', canceled_at = ?,
                        cancel_at_period_end = true, updated_at = now(), last_provider_sync_at = now()
                    where id = ?
                    """, Timestamp.from(canceledAt), id);
            return null;
        });
    }

    @Override
    public List<Payment> payments(String tenantSlug, UUID subscriptionId) {
        return inTenant(tenantSlug, () -> jdbc.query("""
                select provider_payment_id, provider_status, amount_cop, paid_at, period_start, period_end
                from subscription_payments
                where subscription_id = ?
                order by coalesce(paid_at, created_at) desc
                """, this::mapPayment, subscriptionId));
    }

    @Override
    public boolean insertWebhook(
            String providerEventId, String eventType, String action, String resourceId,
            String requestId, JsonNode rawBody
    ) {
        return jdbc.update("""
                insert into billing_webhook_events (
                  provider_event_id, event_type, action, resource_id, request_id, raw_body
                ) values (?, ?, ?, ?, ?, cast(? as jsonb))
                on conflict (provider_event_id, event_type) do nothing
                """, providerEventId, eventType, action, resourceId, requestId, rawBody.toString()) > 0;
    }

    @Override
    public List<WebhookEvent> claimWebhookBatch(int limit) {
        return inWorker(() -> jdbc.query("""
                with claimed as (
                  select id from billing_webhook_events
                  where status in ('PENDING','FAILED') and next_attempt_at <= now()
                    and processing_attempts < 8
                  order by received_at
                  for update skip locked
                  limit ?
                )
                update billing_webhook_events e
                set status = 'PROCESSING', processing_attempts = processing_attempts + 1
                from claimed where e.id = claimed.id
                returning e.id, e.event_type, e.action, e.resource_id,
                          e.raw_body::text as raw_body, e.processing_attempts
                """, (rs, row) -> new WebhookEvent(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getString("action"),
                rs.getString("resource_id"),
                rs.getString("raw_body"),
                rs.getInt("processing_attempts")), limit));
    }

    @Override
    public void completeWebhook(UUID eventId) {
        jdbc.update("""
                update billing_webhook_events
                set status = 'PROCESSED', processed_at = now(), last_error = null
                where id = ?
                """, eventId);
    }

    @Override
    public void failWebhook(UUID eventId, int attempts, String error) {
        String status = attempts >= 8 ? "DEAD" : "FAILED";
        long delaySeconds = Math.min(3600, 15L * (1L << Math.min(attempts, 7)));
        jdbc.update("""
                update billing_webhook_events
                set status = ?, last_error = ?, next_attempt_at = now() + (? * interval '1 second')
                where id = ?
                """, status, truncate(error), delaySeconds, eventId);
    }

    @Override
    public void applyPayment(
            Subscription subscription,
            String paymentId,
            String authorizedPaymentId,
            String providerStatus,
            BigDecimal amount,
            Instant paidAt,
            Instant providerNextPayment,
            JsonNode payload,
            Duration gracePeriod
    ) {
        inTenant(subscription.tenantSlug(), () -> {
            boolean approved = "approved".equalsIgnoreCase(providerStatus);
            String previousStatus = jdbc.query(
                    "select provider_status from subscription_payments where provider_payment_id = ?",
                    (rs, row) -> rs.getString(1), paymentId).stream().findFirst().orElse(null);
            Instant periodStart = subscription.paidThrough() != null && subscription.paidThrough().isAfter(paidAt)
                    ? subscription.paidThrough() : paidAt;
            Instant fallbackEnd = "YEARLY".equals(subscription.plan().interval())
                    ? periodStart.atZone(ZoneOffset.UTC).plusMonths(12).toInstant()
                    : periodStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            Instant periodEnd = providerNextPayment != null && providerNextPayment.isAfter(periodStart)
                    ? providerNextPayment : fallbackEnd;
            int inserted = jdbc.update("""
                    insert into subscription_payments (
                      tenant_id, tenant_slug, subscription_id, provider_payment_id,
                      provider_authorized_payment_id, provider_status, amount_cop, paid_at,
                      period_start, period_end, raw_payload
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    on conflict (provider_payment_id) do nothing
                    """, subscription.tenantId(), subscription.tenantSlug(), subscription.id(), paymentId,
                    authorizedPaymentId, providerStatus, amount, timestamp(paidAt),
                    timestamp(periodStart), timestamp(periodEnd), payload.toString());
            if (inserted == 0) {
                jdbc.update("""
                        update subscription_payments
                        set provider_status = ?, amount_cop = ?, raw_payload = cast(? as jsonb), updated_at = now()
                        where provider_payment_id = ?
                        """, providerStatus, amount, payload.toString(), paymentId);
            }

            boolean currentOrNew = subscription.currentPeriodStart() == null
                    || !paidAt.isBefore(subscription.currentPeriodStart());
            boolean newlyApproved = approved && !"approved".equalsIgnoreCase(previousStatus);
            if (newlyApproved && currentOrNew) {
                int nextCycle = subscription.approvedCycleCount() + 1;
                boolean transition = subscription.coupon() != null
                        && (("ONCE".equals(subscription.coupon().duration()) && nextCycle >= 1)
                        || ("REPEATING".equals(subscription.coupon().duration())
                        && nextCycle >= subscription.coupon().durationCycles()));
                jdbc.update("""
                        update subscriptions
                        set status = 'ACTIVE', provider_status = 'authorized',
                            current_period_start = ?, paid_through = greatest(coalesce(paid_through, ?), ?),
                            grace_deadline = null, approved_cycle_count = ?,
                            amount_transition_pending = amount_transition_pending or ?,
                            updated_at = now(), last_provider_sync_at = now()
                        where id = ?
                        """, timestamp(periodStart), timestamp(periodStart), timestamp(periodEnd),
                        nextCycle, transition, subscription.id());
            } else if (currentOrNew && ("refunded".equalsIgnoreCase(providerStatus)
                    || "charged_back".equalsIgnoreCase(providerStatus))) {
                jdbc.update("""
                        update subscriptions
                        set status = 'REFUNDED', paid_through = least(coalesce(paid_through, now()), now()),
                            grace_deadline = null, updated_at = now(), last_provider_sync_at = now()
                        where id = ?
                        """, subscription.id());
            } else if (currentOrNew && isFailure(providerStatus)) {
                Instant grace = subscription.paidThrough() == null
                        ? null : subscription.paidThrough().plus(gracePeriod);
                jdbc.update("""
                        update subscriptions
                        set status = case when paid_through is null then status else 'PAST_DUE' end,
                            grace_deadline = ?, updated_at = now(), last_provider_sync_at = now()
                        where id = ?
                        """, timestamp(grace), subscription.id());
            }
            return null;
        });
    }

    @Override
    public List<Subscription> reconciliationCandidates(Instant staleBefore, int limit) {
        return inWorker(() -> jdbc.query(subscriptionSelect() + """
                where s.status in ('PENDING','ACTIVE','PAST_DUE')
                  and s.provider_subscription_id is not null
                  and (s.last_provider_sync_at is null or s.last_provider_sync_at < ?)
                order by coalesce(s.last_provider_sync_at, s.created_at)
                limit ?
                """, this::mapSubscription, Timestamp.from(staleBefore), limit));
    }

    @Override
    public void expirePendingAndGrace(Instant now) {
        inWorker(() -> {
            jdbc.update("""
                    update subscriptions set status = 'EXPIRED', updated_at = now()
                    where status = 'PENDING' and pending_expires_at < ?
                    """, Timestamp.from(now));
            jdbc.update("""
                    update subscriptions set status = 'EXPIRED', updated_at = now()
                    where status = 'PAST_DUE' and grace_deadline < ?
                    """, Timestamp.from(now));
            return null;
        });
    }

    @Override
    public List<Subscription> amountTransitionCandidates(int limit) {
        return inWorker(() -> jdbc.query(subscriptionSelect() + """
                where s.amount_transition_pending and s.provider_subscription_id is not null
                  and s.status in ('ACTIVE','PAST_DUE') and s.amount_transition_attempts < 8
                order by s.updated_at limit ?
                """, this::mapSubscription, limit));
    }

    @Override
    public void completeAmountTransition(Subscription subscription) {
        inTenant(subscription.tenantSlug(), () -> {
            jdbc.update("""
                    update subscriptions
                    set current_amount_cop = original_amount_cop, amount_transition_pending = false,
                        amount_transition_error = null, updated_at = now()
                    where id = ?
                    """, subscription.id());
            return null;
        });
    }

    @Override
    public void failAmountTransition(Subscription subscription, String error) {
        inTenant(subscription.tenantSlug(), () -> {
            jdbc.update("""
                    update subscriptions
                    set amount_transition_attempts = amount_transition_attempts + 1,
                        amount_transition_error = ?, updated_at = now()
                    where id = ?
                    """, truncate(error), subscription.id());
            return null;
        });
    }

    private <T> T inTenant(String tenantSlug, java.util.function.Supplier<T> work) {
        return transactions.execute(status -> {
            setContext(tenantSlug);
            return work.get();
        });
    }

    private <T> T inWorker(java.util.function.Supplier<T> work) {
        return inTenant("__worker__", work);
    }

    private void setContext(String tenantSlug) {
        jdbc.queryForObject("select set_config('app.tenant_slug', ?, true)", String.class, tenantSlug);
    }

    private String subscriptionSelect() {
        return """
                select s.*, p.version as plan_version, p.description as plan_description,
                       p.display_order as plan_display_order
                from subscriptions s
                join billing_plans p on p.id = s.plan_id
                """;
    }

    private Plan mapPlan(ResultSet rs, int row) throws SQLException {
        return new Plan(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("version"),
                rs.getString("display_name"), rs.getString("description"),
                rs.getString("billing_interval"), rs.getBigDecimal("price_cop"),
                rs.getInt("display_order"));
    }

    private Coupon mapCoupon(ResultSet rs, int row) throws SQLException {
        return new Coupon(
                rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("discount_type"), rs.getBigDecimal("discount_value"),
                rs.getString("duration"), (Integer) rs.getObject("duration_cycles"));
    }

    private Subscription mapSubscription(ResultSet rs, int row) throws SQLException {
        Plan plan = new Plan(
                rs.getObject("plan_id", UUID.class), rs.getString("plan_code"),
                rs.getInt("plan_version"), rs.getString("plan_name"),
                rs.getString("plan_description"), rs.getString("plan_interval"),
                rs.getBigDecimal("plan_price_cop"), rs.getInt("plan_display_order"));
        UUID couponId = rs.getObject("coupon_id", UUID.class);
        Coupon coupon = couponId == null ? null : new Coupon(
                couponId, rs.getString("coupon_code"), rs.getString("coupon_type"),
                rs.getBigDecimal("coupon_value"), rs.getString("coupon_duration"),
                (Integer) rs.getObject("coupon_duration_cycles"));
        return new Subscription(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_slug"), plan, coupon,
                rs.getBigDecimal("original_amount_cop"), rs.getBigDecimal("current_amount_cop"),
                rs.getString("status"), rs.getString("provider_status"),
                rs.getString("provider_subscription_id"), rs.getString("provider_init_point"),
                rs.getString("payer_email"), instant(rs, "current_period_start"),
                instant(rs, "paid_through"), instant(rs, "grace_deadline"),
                instant(rs, "pending_expires_at"), instant(rs, "canceled_at"),
                rs.getBoolean("cancel_at_period_end"), rs.getInt("approved_cycle_count"),
                rs.getBoolean("amount_transition_pending"), rs.getString("idempotency_key"),
                instant(rs, "created_at"));
    }

    private Payment mapPayment(ResultSet rs, int row) throws SQLException {
        return new Payment(
                rs.getString("provider_payment_id"), rs.getString("provider_status"),
                rs.getBigDecimal("amount_cop"), instant(rs, "paid_at"),
                instant(rs, "period_start"), instant(rs, "period_end"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static boolean isFailure(String status) {
        return "rejected".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)
                || "canceled".equalsIgnoreCase(status);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
