package com.legalgate.intake.model;

public record OrganizationOnboardingResponse(
        String organizationId,
        String tenantId,
        String displayName,
        String status
) {
    public static OrganizationOnboardingResponse from(TenantProvisioning tenant) {
        return new OrganizationOnboardingResponse(
                tenant.organizationId(), tenant.slug(), tenant.displayName(), tenant.status());
    }
}
