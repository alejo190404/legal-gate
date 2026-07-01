package com.legalgate.intake.billing;

import com.legalgate.intake.billing.BillingModels.Status;
import com.legalgate.intake.billing.BillingModels.Subscription;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BillingAccessService {
    private final BillingProperties properties;
    private final BillingRepository repository;

    public BillingAccessService(BillingProperties properties, BillingRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public Status status(String tenantSlug) {
        if (!properties.enabled()) {
            return new Status(false, false, true, "DISABLED", null, List.of(), null,
                    "Billing is disabled for this environment.");
        }
        Subscription subscription = repository.currentSubscription(tenantSlug).orElse(null);
        Instant now = Instant.now();
        Subscription entitledSubscription = repository.entitledSubscription(tenantSlug, now).orElse(null);
        boolean paidEntitled = entitled(entitledSubscription, now);
        boolean entitled = !properties.enforcementEnabled() || paidEntitled;
        Instant accessEndsAt = accessEndsAt(entitledSubscription);
        String status = subscription == null ? "SUBSCRIPTION_REQUIRED" : subscription.status();
        List<BillingModels.Payment> payments = subscription == null
                ? List.of() : repository.payments(tenantSlug, subscription.id());
        return new Status(true, properties.enforcementEnabled(), entitled, status, subscription,
                payments, accessEndsAt, message(subscription, paidEntitled));
    }

    public boolean isEntitled(String tenantSlug) {
        return status(tenantSlug).entitled();
    }

    public void requireEntitled(String tenantSlug) {
        if (!isEntitled(tenantSlug)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "subscription_required");
        }
    }

    private boolean entitled(Subscription subscription, Instant now) {
        if (subscription == null) return false;
        if ("ACTIVE".equals(subscription.status())) {
            return subscription.paidThrough() != null && subscription.paidThrough().isAfter(now);
        }
        if ("PAST_DUE".equals(subscription.status())) {
            return subscription.graceDeadline() != null && subscription.graceDeadline().isAfter(now);
        }
        if ("CANCELED".equals(subscription.status())) {
            return subscription.paidThrough() != null && subscription.paidThrough().isAfter(now);
        }
        return false;
    }

    private Instant accessEndsAt(Subscription subscription) {
        if (subscription == null) return null;
        if ("PAST_DUE".equals(subscription.status())) return subscription.graceDeadline();
        return subscription.paidThrough();
    }

    private String message(Subscription subscription, boolean paidEntitled) {
        if (!properties.enforcementEnabled() && !paidEntitled) {
            return "Billing enforcement is staged off; access remains enabled.";
        }
        if (subscription == null) return "Choose a plan to activate LegalGate.";
        return switch (subscription.status()) {
            case "PENDING" -> "Complete checkout in Mercado Pago.";
            case "PAST_DUE" -> paidEntitled
                    ? "Payment is overdue; access remains available during the grace period."
                    : "The payment grace period has expired.";
            case "CANCELED" -> paidEntitled
                    ? "Renewal is canceled; access continues through the paid period."
                    : "The canceled subscription has ended.";
            default -> paidEntitled ? "Subscription active." : "A subscription is required.";
        };
    }
}
