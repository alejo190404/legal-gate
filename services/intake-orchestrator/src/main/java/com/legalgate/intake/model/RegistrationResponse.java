package com.legalgate.intake.model;

public record RegistrationResponse(
        String email,
        String tenantId,
        String displayName,
        String role
) {
}
