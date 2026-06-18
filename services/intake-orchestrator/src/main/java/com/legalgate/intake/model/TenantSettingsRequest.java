package com.legalgate.intake.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import java.util.List;

public record TenantSettingsRequest(
        List<String> urgentKeywords,
        List<String> consultationWindows,
        @Email String destinationEmail,
        @Email String intakeEmail,
        List<@Valid TenantRoutingRule> routingRules
) {
}
