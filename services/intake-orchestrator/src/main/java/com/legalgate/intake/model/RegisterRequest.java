package com.legalgate.intake.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12) String password,
        @NotBlank @Size(max = 120) String firmName
) {
}
