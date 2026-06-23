package com.legalgate.intake.model;

import jakarta.validation.Valid;
import java.util.List;

public record TenantSettingsRequest(
        List<@Valid TenantRoutingRule> routingRules,
        List<@Valid LawyerProfile> lawyers
) {
}
