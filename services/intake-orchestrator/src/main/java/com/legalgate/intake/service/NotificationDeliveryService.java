package com.legalgate.intake.service;

import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class NotificationDeliveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int BATCH_SIZE = 20;

    private final IntakeRepository intakeRepository;
    private final CloudMailinOutboundEmailClient outboundEmailClient;

    NotificationDeliveryService(IntakeRepository intakeRepository, CloudMailinOutboundEmailClient outboundEmailClient) {
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
                String providerMessageId = outboundEmailClient.send(notification);
                intakeRepository.markNotificationSent(notification.id(), providerMessageId);
            } catch (Exception ex) {
                LOGGER.warn("Failed to send LegalGate notification id={} type={} recipientRole={}",
                        notification.id(), notification.type(), notification.recipientRole(), ex);
                intakeRepository.markNotificationFailed(notification.id(), ex.getMessage());
            }
        }
    }
}
