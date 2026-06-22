package com.legalgate.intake.model;

import java.util.List;

public record TenantSettingsResponse(
        String tenantId,
        List<String> urgentKeywords,
        List<String> consultationWindows,
        List<String> urgencyLevels,
        String destinationEmail,
        String intakeEmail,
        List<TenantRoutingRule> routingRules
) {
}
