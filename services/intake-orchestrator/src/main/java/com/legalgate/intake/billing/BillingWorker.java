package com.legalgate.intake.billing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class BillingWorker {
    private final BillingService billing;

    BillingWorker(BillingService billing) {
        this.billing = billing;
    }

    @Scheduled(
            fixedDelayString = "${LEGALGATE_BILLING_WEBHOOK_PROCESSING_DELAY_MS:5000}",
            initialDelayString = "${LEGALGATE_BILLING_WORKER_INITIAL_DELAY_MS:10000}")
    void processWebhooks() {
        billing.processWebhookBatch();
    }

    @Scheduled(
            fixedDelayString = "${LEGALGATE_BILLING_RECONCILIATION_DELAY_MS:300000}",
            initialDelayString = "${LEGALGATE_BILLING_WORKER_INITIAL_DELAY_MS:10000}")
    void reconcile() {
        billing.reconcile();
    }
}
