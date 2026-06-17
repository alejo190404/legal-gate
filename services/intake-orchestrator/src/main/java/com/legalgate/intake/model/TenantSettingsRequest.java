package com.legalgate.intake.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TenantSettingsRequest(
        List<@NotBlank String> urgentKeywords,
        List<@NotBlank String> consultationWindows,
        @NotBlank @Email String destinationEmail,
        @Email String intakeEmail
) {
}
