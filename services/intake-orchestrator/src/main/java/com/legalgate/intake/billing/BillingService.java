package com.legalgate.intake.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.billing.BillingModels.CheckoutResponse;
import com.legalgate.intake.billing.BillingModels.Coupon;
import com.legalgate.intake.billing.BillingModels.Plan;
import com.legalgate.intake.billing.BillingModels.Quote;
import com.legalgate.intake.billing.BillingModels.Status;
import com.legalgate.intake.billing.BillingModels.Subscription;
import com.legalgate.intake.billing.BillingModels.WebhookEvent;
import com.legalgate.intake.service.WorkosClient;
import com.legalgate.intake.config.IntakeProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private final BillingProperties properties;
    private final BillingRepository repository;
    private final BillingAccessService accessService;
    private final SubscriptionProviderClient provider;
    private final WorkosClient workos;
    private final ObjectMapper objectMapper;

    public BillingService(
            BillingProperties properties,
            BillingRepository repository,
            BillingAccessService accessService,
            SubscriptionProviderClient provider,
            WorkosClient workos,
            ObjectMapper objectMapper,
            IntakeProperties intakeProperties
    ) {
        this.properties = properties;
        this.repository = repository;
        this.accessService = accessService;
        this.provider = provider;
        this.workos = workos;
        this.objectMapper = objectMapper;
        if (properties.enabled() && !"jdbc".equalsIgnoreCase(intakeProperties.persistence())) {
            throw new IllegalStateException(
                    "LEGALGATE_BILLING_ENABLED requires LEGALGATE_INTAKE_PERSISTENCE=jdbc.");
        }
    }

    public List<Plan> plans() {
        return properties.enabled() ? repository.activePlans() : List.of();
    }

    public Quote quote(String planCode, String couponCode) {
        requireEnabled();
        Plan plan = repository.activePlan(planCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_plan"));
        Coupon coupon = null;
        if (couponCode != null && !couponCode.isBlank()) {
            coupon = repository.validCoupon(couponCode, Instant.now())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_coupon"));
        }
        BigDecimal discount = BigDecimal.ZERO;
        if (coupon != null) {
            discount = "PERCENTAGE".equals(coupon.discountType())
                    ? plan.priceCop().multiply(coupon.discountValue())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : coupon.discountValue().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal recurring = plan.priceCop().subtract(discount).setScale(2, RoundingMode.HALF_UP);
        if (recurring.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "coupon_results_in_non_positive_price");
        }
        return new Quote(
                plan, coupon == null ? null : coupon.code(), plan.priceCop(), recurring,
                discount, "COP", coupon == null ? null : coupon.duration(),
                coupon == null ? null : discountedCycles(coupon));
    }

    public CheckoutResponse checkout(
            String tenantSlug, String userId, String planCode, String couponCode, String idempotencyKey
    ) {
        requireEnabled();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
        Optional<Subscription> retry = repository.subscriptionByIdempotency(tenantSlug, idempotencyKey.trim());
        if (retry.isPresent()) return recoverOrReturn(retry.get(), idempotencyKey.trim());

        repository.expirePendingAndGrace(Instant.now());
        Optional<Subscription> current = repository.currentSubscription(tenantSlug);
        if (current.isPresent() && List.of("PENDING", "ACTIVE", "PAST_DUE").contains(current.get().status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "subscription_already_exists");
        }

        Quote quote = quote(planCode, couponCode);
        String payerEmail = workos.userEmail(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payer_email_unavailable"));
        Coupon coupon = quote.couponCode() == null ? null
                : repository.validCoupon(quote.couponCode(), Instant.now()).orElseThrow();
        Subscription pending;
        try {
            pending = repository.createPending(
                    tenantSlug, quote.plan(), coupon, quote.recurringAmountCop(), payerEmail,
                    idempotencyKey.trim(), Instant.now().plus(properties.pendingCheckoutTtl()));
        } catch (DuplicateKeyException conflict) {
            return repository.subscriptionByIdempotency(tenantSlug, idempotencyKey.trim())
                    .map(value -> recoverOrReturn(value, idempotencyKey.trim()))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "subscription_already_exists", conflict));
        }

        try {
            JsonNode created = provider.createPending(
                    quote, payerEmail, pending.id().toString(), idempotencyKey.trim());
            attachProviderResponse(pending, created);
            return response(repository.subscriptionByIdempotency(tenantSlug, idempotencyKey.trim()).orElse(pending));
        } catch (RestClientException ambiguous) {
            Optional<JsonNode> recovered = safeFind(pending.id().toString());
            if (recovered.isPresent()) {
                attachProviderResponse(pending, recovered.get());
                return response(repository.subscriptionByIdempotency(tenantSlug, idempotencyKey.trim()).orElse(pending));
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "provider_checkout_pending_reconciliation", ambiguous);
        }
    }

    public Status status(String tenantSlug) {
        return accessService.status(tenantSlug);
    }

    public Status cancel(String tenantSlug) {
        requireEnabled();
        Subscription subscription = repository.currentSubscription(tenantSlug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription_not_found"));
        if ("CANCELED".equals(subscription.status())) return accessService.status(tenantSlug);
        if (subscription.providerSubscriptionId() == null) {
            repository.cancel(subscription.id(), tenantSlug, Instant.now());
            return accessService.status(tenantSlug);
        }
        provider.cancel(subscription.providerSubscriptionId());
        repository.cancel(subscription.id(), tenantSlug, Instant.now());
        return accessService.status(tenantSlug);
    }

    public boolean acceptWebhook(
            String rawBody, String dataId, String requestId, String signature,
            MercadoPagoWebhookSignatureValidator validator
    ) {
        requireEnabled();
        if (!validator.isValid(dataId, requestId, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_webhook_signature");
        }
        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            String type = payload.path("type").asText("");
            String action = payload.path("action").asText("");
            String eventId = payload.path("id").asText(requestId == null ? dataId : requestId);
            if (type.isBlank() || dataId == null || dataId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_payload");
            }
            return repository.insertWebhook(eventId, type, action, dataId, requestId, payload);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_payload", exception);
        }
    }

    public void processWebhookBatch() {
        if (!properties.enabled()) return;
        for (WebhookEvent event : repository.claimWebhookBatch(25)) {
            try {
                processWebhook(event);
                repository.completeWebhook(event.id());
            } catch (Exception exception) {
                log.warn("Mercado Pago webhook processing failed event={} attempt={}",
                        event.id(), event.attempts(), exception);
                repository.failWebhook(event.id(), event.attempts(), exception.getMessage());
            }
        }
    }

    public void reconcile() {
        if (!properties.enabled()) return;
        repository.expirePendingAndGrace(Instant.now());
        Instant staleBefore = Instant.now().minus(properties.reconciliationInterval());
        for (Subscription subscription : repository.reconciliationCandidates(staleBefore, 50)) {
            try {
                JsonNode canonical = provider.subscription(subscription.providerSubscriptionId());
                reconcileSubscription(subscription, canonical);
                JsonNode invoices = provider.authorizedPayments(subscription.providerSubscriptionId());
                if (invoices != null && invoices.path("results").isArray()) {
                    List<JsonNode> orderedInvoices = java.util.stream.StreamSupport
                            .stream(invoices.path("results").spliterator(), false)
                            .sorted(java.util.Comparator.comparing(
                                    invoice -> Optional.ofNullable(instant(invoice.path("date_created").asText(null)))
                                            .orElse(Instant.EPOCH)))
                            .toList();
                    for (JsonNode invoice : orderedInvoices) {
                        String paymentId = invoice.path("payment").path("id").asText("");
                        if (!paymentId.isBlank()) {
                            Subscription latest = repository
                                    .subscriptionByProviderId(subscription.providerSubscriptionId())
                                    .orElse(subscription);
                            applyCanonicalPayment(
                                    latest,
                                    provider.payment(paymentId),
                                    invoice.path("id").asText(null));
                        }
                    }
                }
            } catch (Exception exception) {
                log.warn("Subscription reconciliation failed subscription={}", subscription.id(), exception);
            }
        }
        for (Subscription subscription : repository.amountTransitionCandidates(25)) {
            try {
                provider.updateAmount(subscription.providerSubscriptionId(), subscription.originalAmountCop());
                repository.completeAmountTransition(subscription);
            } catch (Exception exception) {
                repository.failAmountTransition(subscription, exception.getMessage());
                log.error("Coupon amount transition failed subscription={}", subscription.id(), exception);
            }
        }
    }

    private void processWebhook(WebhookEvent event) {
        if ("subscription_preapproval".equals(event.eventType())) {
            JsonNode canonical = provider.subscription(event.resourceId());
            Subscription subscription = resolveSubscription(canonical, event.resourceId());
            reconcileSubscription(subscription, canonical);
            return;
        }
        if ("subscription_authorized_payment".equals(event.eventType())) {
            JsonNode authorized = provider.authorizedPayment(event.resourceId());
            String providerSubscriptionId = firstText(authorized, "preapproval_id", "subscription_id");
            Subscription subscription = repository.subscriptionByProviderId(providerSubscriptionId)
                    .orElseThrow(() -> new IllegalStateException("Unknown provider subscription."));
            String paymentId = authorized.path("payment").path("id").asText("");
            JsonNode payment = paymentId.isBlank() ? authorized : provider.payment(paymentId);
            applyCanonicalPayment(subscription, payment, event.resourceId());
            return;
        }
        if ("payment".equals(event.eventType())) {
            JsonNode payment = provider.payment(event.resourceId());
            String externalReference = payment.path("external_reference").asText("");
            Subscription subscription = repository.subscriptionByExternalReference(externalReference)
                    .or(() -> repository.subscriptionByProviderId(
                            payment.path("metadata").path("preapproval_id").asText("")))
                    .orElseThrow(() -> new IllegalStateException("Payment is not associated with a LegalGate subscription."));
            applyCanonicalPayment(subscription, payment, null);
        }
    }

    private void applyCanonicalPayment(Subscription subscription, JsonNode payment, String authorizedId) {
        String paymentId = payment.path("id").asText(authorizedId);
        String status = payment.path("status").asText("unknown");
        BigDecimal amount = decimal(payment, "transaction_amount");
        Instant paidAt = instant(firstText(payment, "date_approved", "date_created"));
        if (paidAt == null) paidAt = Instant.now();
        Instant nextPayment = null;
        if (subscription.providerSubscriptionId() != null) {
            JsonNode canonicalSubscription = provider.subscription(subscription.providerSubscriptionId());
            nextPayment = instant(firstText(
                    canonicalSubscription.path("auto_recurring"), "next_payment_date"));
            if (nextPayment == null) nextPayment = instant(canonicalSubscription.path("next_payment_date").asText(null));
        }
        repository.applyPayment(subscription, paymentId, authorizedId, status, amount, paidAt,
                nextPayment, payment, properties.gracePeriod());
    }

    private void reconcileSubscription(Subscription subscription, JsonNode canonical) {
        String providerId = canonical.path("id").asText(subscription.providerSubscriptionId());
        String providerStatus = canonical.path("status").asText("unknown");
        String initPoint = firstText(canonical, "init_point", "sandbox_init_point");
        if (subscription.providerSubscriptionId() == null && providerId != null) {
            repository.attachProvider(subscription.id(), subscription.tenantSlug(), providerId, providerStatus, initPoint);
        } else {
            repository.updateProviderStatus(subscription.id(), subscription.tenantSlug(), providerStatus);
        }
        if ("cancelled".equalsIgnoreCase(providerStatus) || "canceled".equalsIgnoreCase(providerStatus)) {
            repository.cancel(subscription.id(), subscription.tenantSlug(), Instant.now());
        }
    }

    private Subscription resolveSubscription(JsonNode canonical, String providerId) {
        String externalReference = canonical.path("external_reference").asText("");
        return repository.subscriptionByExternalReference(externalReference)
                .or(() -> repository.subscriptionByProviderId(providerId))
                .orElseThrow(() -> new IllegalStateException("Unknown LegalGate subscription."));
    }

    private CheckoutResponse recoverOrReturn(Subscription subscription, String key) {
        if (subscription.checkoutUrl() != null || subscription.providerSubscriptionId() != null) {
            return response(subscription);
        }
        Optional<JsonNode> recovered = safeFind(subscription.id().toString());
        if (recovered.isPresent()) {
            attachProviderResponse(subscription, recovered.get());
            return response(repository.subscriptionByIdempotency(subscription.tenantSlug(), key).orElse(subscription));
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "provider_checkout_pending_reconciliation");
    }

    private void attachProviderResponse(Subscription subscription, JsonNode response) {
        if (response == null || response.path("id").asText("").isBlank()) {
            throw new IllegalStateException("Mercado Pago did not return a subscription ID.");
        }
        repository.attachProvider(
                subscription.id(), subscription.tenantSlug(), response.path("id").asText(),
                response.path("status").asText("pending"),
                firstText(response, "init_point", "sandbox_init_point"));
    }

    private Optional<JsonNode> safeFind(String externalReference) {
        try {
            return provider.findByExternalReference(externalReference);
        } catch (RestClientException ignored) {
            return Optional.empty();
        }
    }

    private CheckoutResponse response(Subscription subscription) {
        return new CheckoutResponse(subscription.id(), subscription.status(),
                subscription.checkoutUrl(), subscription.pendingExpiresAt());
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "billing_disabled");
        }
    }

    private static int discountedCycles(Coupon coupon) {
        if ("ONCE".equals(coupon.duration())) return 1;
        if ("REPEATING".equals(coupon.duration())) return coupon.durationCycles();
        return -1;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).decimalValue() : null;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }
}
