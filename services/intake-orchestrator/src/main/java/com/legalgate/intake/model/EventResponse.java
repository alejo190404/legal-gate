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
        String meetingUrl,
        Boolean scheduledWithinSla,
        String status,
        String source
) {
    public EventResponse(
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
        this(id, lawyerId, lawyerDisplayName, lawyerEmail, routeName, urgencyName, slaDays, slaDeadline, priorityScore,
                scheduledStart, scheduledEnd, null, scheduledEnd == null || slaDeadline == null ? null : !scheduledEnd.isAfter(slaDeadline),
                status, source);
    }
}
