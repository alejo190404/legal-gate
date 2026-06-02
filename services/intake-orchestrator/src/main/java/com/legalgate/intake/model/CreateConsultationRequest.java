package com.legalgate.intake.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConsultationRequest(
        @NotBlank String clientName,
        @NotBlank @Email String clientEmail,
        @NotBlank @Size(min = 20) String summary,
        String preferredWindow
) {
}
