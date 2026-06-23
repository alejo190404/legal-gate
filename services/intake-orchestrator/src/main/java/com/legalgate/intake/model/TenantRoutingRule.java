package com.legalgate.intake.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TenantRoutingRule(
        @NotBlank String name,
        String description,
        List<@NotBlank String> urgentKeywords,
        List<@NotBlank String> consultationWindows,
        List<@NotBlank String> urgencyLevels,
        String lawyerId,
        List<@Valid UrgencyDefinition> urgencyDefinitions,
        @NotBlank @Email String destinationEmail
) {
}
