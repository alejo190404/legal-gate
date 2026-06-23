package com.legalgate.intake.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TenantRoutingRule(
        @NotBlank String name,
        String description,
        List<@NotBlank String> urgentKeywords,
        List<@NotBlank String> consultationWindows,
        List<@NotBlank String> urgencyLevels,
        @NotBlank @Email String destinationEmail
) {
}

