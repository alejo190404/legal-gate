package com.legalgate.intake.model;

import java.util.List;

public record TenantSettingsResponse(
        String tenantId,
        List<String> urgentKeywords,
        List<String> consultationWindows,
        String destinationEmail
) {
}
