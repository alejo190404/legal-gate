package com.legalgate.intake.model;

import java.time.Instant;

public record EventResponse(
        String id,
        String lawyerId,
        String lawyerDisplayName,
        String lawyerEmail,
        String routeName,
        String urgencyName,
        Integer slaDays,
        Instant slaDeadline,
        Integer priorityScore,
        Instant scheduledStart,
        Instant scheduledEnd,
        String status,
        String source
) {
}
