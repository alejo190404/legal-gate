package com.legalgate.intake.model;

import java.util.List;

public record ConsultationListResponse(
        String tenantId,
        List<ConsultationResponse> consultations
) {
}
