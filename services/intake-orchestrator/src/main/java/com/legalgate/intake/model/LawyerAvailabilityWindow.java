package com.legalgate.intake.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LawyerAvailabilityWindow(
        @NotNull Integer weekday,
        @NotBlank String startTime,
        @NotBlank String endTime,
        String timezone
) {
}
