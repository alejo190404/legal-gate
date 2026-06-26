package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.model.NotificationOutboxItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryIntakeRepositoryTests {

    @Test
    void failedNotificationsStopBeingClaimedAfterRetryCap() {
        InMemoryIntakeRepository repository = new InMemoryIntakeRepository();
        repository.queueNotifications("tenant-a", List.of(notification("First")));

        NotificationOutboxItem claimed = repository.claimPendingNotifications(1).get(0);
        for (int attempt = 0; attempt < 5; attempt++) {
            repository.markNotificationFailed(claimed.id(), "provider rejected message");
        }

        assertThat(repository.claimPendingNotifications(1)).isEmpty();
    }

    @Test
    void sentNotificationDoesNotDedupeLaterDeliveryForSameEventAndRecipient() {
        InMemoryIntakeRepository repository = new InMemoryIntakeRepository();
        repository.queueNotifications("tenant-a", List.of(notification("First")));

        NotificationOutboxItem first = repository.claimPendingNotifications(1).get(0);
        repository.markNotificationSent(first.id(), "provider-1");

        repository.queueNotifications("tenant-a", List.of(notification("Second")));

        assertThat(repository.claimPendingNotifications(1))
                .singleElement()
                .extracting(NotificationOutboxItem::subject)
                .isEqualTo("Second");
    }

    private NotificationOutboxItem notification(String subject) {
        return new NotificationOutboxItem(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "CONSULTATION_RESCHEDULED",
                "CLIENT",
                "client@example.com",
                subject,
                "Body",
                "BEGIN:VCALENDAR\nEND:VCALENDAR"
        );
    }
}
