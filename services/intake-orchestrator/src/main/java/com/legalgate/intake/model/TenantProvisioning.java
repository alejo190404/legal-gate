package com.legalgate.intake.model;

public record TenantProvisioning(
        String id,
        String slug,
        String displayName,
        String organizationId,
        String status,
        String ownerId
) {
}
