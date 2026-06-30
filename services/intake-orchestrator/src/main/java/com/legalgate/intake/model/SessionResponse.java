package com.legalgate.intake.model;

public record SessionResponse(
        String userId,
        String sessionId,
        String organizationId,
        String tenantId,
        String displayName,
        String role
) {
}
