package com.legalgate.intake.model;

import java.util.List;

public record TenantSettingsResponse(
        String tenantId,
        List<String> urgentKeywords,
        List<String> consultationWindows,
        List<String> urgencyLevels,
        String destinationEmail,
        String intakeEmail,
        List<TenantRoutingRule> routingRules,
        List<LawyerProfile> lawyers
) {
    public TenantSettingsResponse(
            String tenantId,
            List<String> urgentKeywords,
            List<String> consultationWindows,
            List<String> urgencyLevels,
            String destinationEmail,
            String intakeEmail,
            List<TenantRoutingRule> routingRules
    ) {
        this(tenantId, urgentKeywords, consultationWindows, urgencyLevels, destinationEmail, intakeEmail, routingRules, List.of());
    }
}
