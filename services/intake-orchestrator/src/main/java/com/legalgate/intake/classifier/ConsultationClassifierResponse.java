package com.legalgate.intake.classifier;

public record ConsultationClassifierResponse(
        Integer routeIndex,
        String consultationType,
        String urgency,
        String concept,
        String summary,
        String clientName,
        String explanation,
        Double confidence
) {
}
