package com.legalgate.intake.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.NotificationOutboxItem;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class NotificationDeliveryServiceTests {

    @Test
    void disabledOutboundEmailLeavesPendingNotificationsUnclaimed() {
        IntakeRepository intakeRepository = mock(IntakeRepository.class);
        OutboundEmailClient outboundEmailClient = mock(OutboundEmailClient.class);
        when(outboundEmailClient.isEnabled()).thenReturn(false);

        new NotificationDeliveryService(intakeRepository, outboundEmailClient).deliverPendingNotifications();

        verify(intakeRepository, never()).claimPendingNotifications(anyInt());
    }

    @Test
    void mailIsSentUnderTheConsultationsOriginalInboundMessageId() {
        IntakeRepository intakeRepository = mock(IntakeRepository.class);
        OutboundEmailClient outboundEmailClient = enabledClient(intakeRepository);
        when(intakeRepository.consultationById("firma-demo", "consultation-1"))
                .thenReturn(Optional.of(consultation("<CAF=original@mail.gmail.com>")));

        new NotificationDeliveryService(intakeRepository, outboundEmailClient).deliverPendingNotifications();

        verify(outboundEmailClient).send(any(NotificationOutboxItem.class), eq("<CAF=original@mail.gmail.com>"));
    }

    @Test
    void aConsultationWithoutASourceMessageIdStillSends() {
        IntakeRepository intakeRepository = mock(IntakeRepository.class);
        OutboundEmailClient outboundEmailClient = enabledClient(intakeRepository);
        when(intakeRepository.consultationById("firma-demo", "consultation-1"))
                .thenReturn(Optional.of(consultation(null)));

        new NotificationDeliveryService(intakeRepository, outboundEmailClient).deliverPendingNotifications();

        verify(outboundEmailClient).send(any(NotificationOutboxItem.class), eq(null));
    }

    @Test
    void aRateLimitedBatchIsDeferredWithoutSpendingAnAttempt() {
        IntakeRepository intakeRepository = mock(IntakeRepository.class);
        OutboundEmailClient outboundEmailClient = mock(OutboundEmailClient.class);
        when(outboundEmailClient.isEnabled()).thenReturn(true);
        when(intakeRepository.claimPendingNotifications(anyInt())).thenReturn(List.of(notification(), notification()));
        when(outboundEmailClient.send(any(NotificationOutboxItem.class), any()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null));

        new NotificationDeliveryService(intakeRepository, outboundEmailClient).deliverPendingNotifications();

        // The row stays SENDING and is re-claimed in five minutes; marking it failed would walk
        // real client mail toward DEAD on a busy day. The rest of the batch is not attempted.
        verify(intakeRepository, never()).markNotificationFailed(any(), any());
        verify(intakeRepository, never()).markNotificationSent(any(), any());
        verify(outboundEmailClient, times(1)).send(any(NotificationOutboxItem.class), any());
    }

    private OutboundEmailClient enabledClient(IntakeRepository intakeRepository) {
        OutboundEmailClient outboundEmailClient = mock(OutboundEmailClient.class);
        when(outboundEmailClient.isEnabled()).thenReturn(true);
        when(intakeRepository.claimPendingNotifications(anyInt())).thenReturn(List.of(notification()));
        return outboundEmailClient;
    }

    private NotificationOutboxItem notification() {
        return new NotificationOutboxItem(
                "notification-1", "firma-demo", "consultation-1", null, "DIAGNOSTICS_QUESTION", "CLIENT",
                "cliente@example.com", null, "Asunto", "Cuerpo", null, null,
                "SENDING", 0, null, null, null, null, null);
    }

    private ConsultationResponse consultation(String sourceMessageId) {
        return new ConsultationResponse(
                "consultation-1", "firma-demo", "Cliente", "cliente@example.com", "Resumen", null, "PENDING",
                null, null, null, null, null, null, sourceMessageId, null);
    }
}
