package com.legalgate.intake.service;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
class NotificationDeliveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int BATCH_SIZE = 20;

    private final IntakeRepository intakeRepository;
    private final OutboundEmailClient outboundEmailClient;

    NotificationDeliveryService(IntakeRepository intakeRepository, OutboundEmailClient outboundEmailClient) {
        this.intakeRepository = intakeRepository;
        this.outboundEmailClient = outboundEmailClient;
    }

    @Scheduled(fixedDelayString = "${LEGALGATE_NOTIFICATION_DISPATCH_DELAY_MS:30000}", initialDelayString = "${LEGALGATE_NOTIFICATION_DISPATCH_INITIAL_DELAY_MS:10000}")
    void deliverPendingNotifications() {
        if (!outboundEmailClient.isEnabled()) {
            LOGGER.debug("LegalGate outbound notification delivery is disabled; pending notifications remain queued.");
            return;
        }
        List<NotificationOutboxItem> notifications = intakeRepository.claimPendingNotifications(BATCH_SIZE);
        for (NotificationOutboxItem notification : notifications) {
            try {
                String providerMessageId = outboundEmailClient.send(notification, threadAnchor(notification));
                intakeRepository.markNotificationSent(notification.id(), providerMessageId);
            } catch (HttpClientErrorException.TooManyRequests ex) {
                // The row stays SENDING and claimPendingNotifications re-claims it once
                // next_attempt_at passes, so the send is deferred without spending an attempt.
                // Marking it failed would march real client mail toward DEAD on a busy day.
                // ponytail: fixed five-minute retry, read `ratelimit-reset` if throughput matters.
                LOGGER.warn("Provider rate limit reached; deferring notification id={} and the rest of this batch.",
                        notification.id());
                break;
            } catch (Exception ex) {
                LOGGER.warn("Failed to send LegalGate notification id={} type={} recipientRole={}",
                        notification.id(), notification.type(), notification.recipientRole(), ex);
                intakeRepository.markNotificationFailed(notification.id(), ex.getMessage());
            }
        }
    }

    /**
     * The Consultation Thread anchor (ADR 0004), resolved here so the outbox carries no message ids.
     * Threading is an enhancement, never a delivery risk: a failed lookup sends the mail unthreaded.
     */
    private String threadAnchor(NotificationOutboxItem notification) {
        if (notification.consultationId() == null || notification.tenantId() == null) {
            return null;
        }
        try {
            return intakeRepository.consultationById(notification.tenantId(), notification.consultationId())
                    .map(ConsultationResponse::sourceMessageId)
                    .orElse(null);
        } catch (Exception ex) {
            LOGGER.debug("Could not resolve the thread anchor for notification id={}; sending unthreaded.",
                    notification.id(), ex);
            return null;
        }
    }
}
