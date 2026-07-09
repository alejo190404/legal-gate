package com.legalgate.intake.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.service.IntakeService;
import com.legalgate.intake.service.TenantContextResolver;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tenant/settings")
public class TenantSettingsController {

    private final IntakeService intakeService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final TenantContextResolver tenantContextResolver;

    public TenantSettingsController(
            IntakeService intakeService,
            ObjectMapper objectMapper,
            Validator validator,
            TenantContextResolver tenantContextResolver
    ) {
        this.intakeService = intakeService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.tenantContextResolver = tenantContextResolver;
    }

    // Settings stay readable/writable before payment so pre-checkout onboarding can
    // configure lawyers and routing rules; product usage (consultations, mail intake)
    // remains entitlement-gated.
    @GetMapping
    public TenantSettingsResponse settings(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId
    ) {
        String tenantId = tenantContextResolver.requireProvisioningActiveTenant(organizationId).slug();
        return intakeService.settingsForTenant(tenantId);
    }

    @PutMapping
    public TenantSettingsResponse saveSettings(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @Valid @RequestBody JsonNode payload
    ) {
        String tenantId = tenantContextResolver.requireProvisioningActiveTenant(organizationId).slug();
        if (payload.has("intakeEmail")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "system_managed_intake_email");
        }
        if (payload.has("urgencyLevels")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenant_wide_urgency_levels_not_supported");
        }
        if (payload.has("urgentKeywords") || payload.has("consultationWindows") || payload.has("destinationEmail")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "legacy_flat_routing_settings_not_supported");
        }
        TenantSettingsRequest request = objectMapper.convertValue(payload, TenantSettingsRequest.class);
        Set<ConstraintViolation<TenantSettingsRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String fields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.joining(","));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "validation_failed:" + fields);
        }
        return intakeService.saveSettings(tenantId, request);
    }
}

