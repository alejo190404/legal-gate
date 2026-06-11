package com.legalgate.intake.model;

public record StoredUserCredentials(
        String email,
        String tenantId,
        String displayName,
        String role,
        String hashedPassword
) {
    public RegistrationResponse toSession() {
        return new RegistrationResponse(email, tenantId, displayName, role);
    }
}
