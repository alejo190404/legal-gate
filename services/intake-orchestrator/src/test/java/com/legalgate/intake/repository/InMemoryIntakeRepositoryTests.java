package com.legalgate.intake.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    @Test
    void htmlBodySurvivesQueueAndClaim() {
        InMemoryIntakeRepository repository = new InMemoryIntakeRepository();
        repository.queueNotifications("tenant-a", List.of(notification("First")));

        assertThat(repository.claimPendingNotifications(1))
                .singleElement()
                .extracting(NotificationOutboxItem::htmlBody)
                .isEqualTo("<p>Body</p>");
    }

    @Test
    void consultationSearchIgnoresAccentsBothWays() {
        InMemoryIntakeRepository repository = new InMemoryIntakeRepository();
        String accented = consultation(repository, "José", "Arrendamiento de bodega en Fontibón", "2026-10-01T10:00:00Z");
        String plain = consultation(repository, "Ana", "Bodega en Fontibon", "2026-10-01T10:01:00Z");

        assertThat(repository.consultationsForTenant("tenant-a", null, "FONTIBON", null, 50).items())
                .extracting(ConsultationResponse::id).containsExactly(plain, accented);
        assertThat(repository.consultationsForTenant("tenant-a", null, "fontibón", null, 50).items())
                .extracting(ConsultationResponse::id).containsExactly(plain, accented);
        assertThat(repository.consultationsForTenant("tenant-a", null, "jose", null, 50).items())
                .extracting(ConsultationResponse::id).containsExactly(accented);
        assertThat(repository.consultationsForTenant("tenant-a", null, "100%", null, 50).items()).isEmpty();
    }

    private String consultation(InMemoryIntakeRepository repository, String clientName, String summary, String createdAt) {
        return repository.saveConsultation("tenant-a", new ConsultationResponse(UUID.randomUUID().toString(), "tenant-a",
                clientName, "ana@example.com", summary, null, "RECEIVED", "NORMAL", null, null, null, null, null,
                null, Instant.parse(createdAt))).id();
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
                "<p>Body</p>",
                "BEGIN:VCALENDAR\nEND:VCALENDAR"
        );
    }
}
