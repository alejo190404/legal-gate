package com.legalgate.intake.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record LawyerProfile(
        String id,
        @NotBlank String displayName,
        @NotBlank @Email String email,
        Boolean active,
        @NotNull Integer defaultEventDurationMinutes,
        List<@Valid LawyerAvailabilityWindow> availabilityWindows
) {
}
