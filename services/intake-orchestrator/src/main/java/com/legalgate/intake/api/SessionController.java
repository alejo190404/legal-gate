package com.legalgate.intake.api;

import com.legalgate.intake.model.SessionResponse;
import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.service.TenantContextResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final TenantContextResolver resolver;

    public SessionController(TenantContextResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/api/session")
    public SessionResponse session(
            @RequestHeader("X-LegalGate-User-Id") String userId,
            @RequestHeader("X-LegalGate-Session-Id") String sessionId,
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @RequestHeader("X-LegalGate-Role") String role
    ) {
        TenantProvisioning tenant = resolver.requireActiveTenant(organizationId);
        return new SessionResponse(
                userId, sessionId, organizationId, tenant.slug(), tenant.displayName(), role);
    }
}
