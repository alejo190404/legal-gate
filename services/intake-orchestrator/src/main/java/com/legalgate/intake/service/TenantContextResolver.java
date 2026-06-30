package com.legalgate.intake.service;

import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.repository.IntakeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantContextResolver {
    private final IntakeRepository repository;

    public TenantContextResolver(IntakeRepository repository) {
        this.repository = repository;
    }

    public TenantProvisioning requireActiveTenant(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "organization_required");
        }
        TenantProvisioning tenant = repository.tenantForOrganization(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "organization_not_onboarded"));
        if (!"ACTIVE".equals(tenant.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_not_active");
        }
        return tenant;
    }
}
