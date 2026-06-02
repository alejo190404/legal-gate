package com.legalgate.intake.api;

import com.legalgate.intake.model.TenantSettingsRequest;
import com.legalgate.intake.model.TenantSettingsResponse;
import com.legalgate.intake.service.IntakeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants/{tenantId}/settings")
public class TenantSettingsController {

    private final IntakeService intakeService;

    public TenantSettingsController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PutMapping
    public TenantSettingsResponse saveSettings(
            @PathVariable String tenantId,
            @Valid @RequestBody TenantSettingsRequest request
    ) {
        return intakeService.saveSettings(tenantId, request);
    }
}
