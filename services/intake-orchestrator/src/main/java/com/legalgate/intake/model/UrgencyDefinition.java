package com.legalgate.intake.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UrgencyDefinition(
        @NotBlank String name,
        @NotNull Integer rank,
        @NotNull Integer slaDays,
        Boolean active
) {
}
