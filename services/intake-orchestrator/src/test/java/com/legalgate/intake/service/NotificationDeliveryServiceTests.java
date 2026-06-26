package com.legalgate.intake.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.legalgate.intake.repository.IntakeRepository;
import org.junit.jupiter.api.Test;

class NotificationDeliveryServiceTests {

    @Test
    void disabledOutboundEmailLeavesPendingNotificationsUnclaimed() {
        IntakeRepository intakeRepository = mock(IntakeRepository.class);
        CloudMailinOutboundEmailClient outboundEmailClient = mock(CloudMailinOutboundEmailClient.class);
        when(outboundEmailClient.isEnabled()).thenReturn(false);

        new NotificationDeliveryService(intakeRepository, outboundEmailClient).deliverPendingNotifications();

        verify(intakeRepository, never()).claimPendingNotifications(anyInt());
    }
}
