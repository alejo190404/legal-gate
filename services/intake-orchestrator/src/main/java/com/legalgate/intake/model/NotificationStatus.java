package com.legalgate.intake.model;

public record NotificationStatus(
        boolean emailQueued,
        boolean calendarUpdateQueued,
        String destinationEmail,
        String preferredWindow
) {
}
