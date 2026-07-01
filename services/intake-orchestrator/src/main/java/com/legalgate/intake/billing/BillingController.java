package com.legalgate.intake.billing;

import com.legalgate.intake.billing.BillingModels.CheckoutRequest;
import com.legalgate.intake.billing.BillingModels.CheckoutResponse;
import com.legalgate.intake.billing.BillingModels.Plan;
import com.legalgate.intake.billing.BillingModels.Quote;
import com.legalgate.intake.billing.BillingModels.Status;
import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.service.TenantContextResolver;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/billing")
class BillingController {
    private final BillingService billing;
    private final TenantContextResolver tenants;

    BillingController(BillingService billing, TenantContextResolver tenants) {
        this.billing = billing;
        this.tenants = tenants;
    }

    @GetMapping("/plans")
    List<Plan> plans(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @RequestHeader("X-LegalGate-Role") String role
    ) {
        requireAdmin(role);
        tenants.requireProvisioningActiveTenant(organizationId);
        return billing.plans();
    }

    @PostMapping("/quote")
    Quote quote(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @RequestHeader("X-LegalGate-Role") String role,
            @Valid @RequestBody CheckoutRequest request
    ) {
        requireAdmin(role);
        tenants.requireProvisioningActiveTenant(organizationId);
        return billing.quote(request.planCode(), request.couponCode());
    }

    @PostMapping("/checkout")
    CheckoutResponse checkout(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @RequestHeader("X-LegalGate-User-Id") String userId,
            @RequestHeader("X-LegalGate-Role") String role,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotency_key_required");
        }
        requireAdmin(role);
        TenantProvisioning tenant = tenants.requireProvisioningActiveTenant(organizationId);
        return billing.checkout(
                tenant.slug(), userId, request.planCode(), request.couponCode(), idempotencyKey);
    }

    @GetMapping("/subscription")
    Status subscription(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @RequestHeader("X-LegalGate-Role") String role
    ) {
        requireAdmin(role);
        TenantProvisioning tenant = tenants.requireProvisioningActiveTenant(organizationId);
        return billing.status(tenant.slug());
    }

    @PostMapping("/subscription/cancel")
    Status cancel(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @RequestHeader("X-LegalGate-Role") String role
    ) {
        requireAdmin(role);
        TenantProvisioning tenant = tenants.requireProvisioningActiveTenant(organizationId);
        return billing.cancel(tenant.slug());
    }

    private static void requireAdmin(String role) {
        if (!"firm_admin".equalsIgnoreCase(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "firm_admin_required");
        }
    }
}
