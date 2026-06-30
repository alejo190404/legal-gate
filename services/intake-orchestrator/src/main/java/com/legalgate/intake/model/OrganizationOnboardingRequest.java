package com.legalgate.intake.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrganizationOnboardingRequest(
        @NotBlank @Size(max = 200) String firmName
) {
}
