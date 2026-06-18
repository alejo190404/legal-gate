package com.legalgate.intake.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.service.IntakeService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tenants/{tenantId}/settings")
public class TenantSettingsController {

    private final IntakeService intakeService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public TenantSettingsController(IntakeService intakeService, ObjectMapper objectMapper, Validator validator) {
        this.intakeService = intakeService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @GetMapping
    public TenantSettingsResponse settings(@PathVariable String tenantId) {
        return intakeService.settingsForTenant(tenantId);
    }

    @PutMapping
    public TenantSettingsResponse saveSettings(
            @PathVariable String tenantId,
            @Valid @RequestBody JsonNode payload
    ) {
        if (payload.has("intakeEmail")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "system_managed_intake_email");
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
