package com.legalgate.intake.api;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.service.IntakeService;
import com.legalgate.intake.service.TenantContextResolver;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConsultationController {

    private final IntakeService intakeService;
    private final TenantContextResolver tenantContextResolver;

    public ConsultationController(IntakeService intakeService, TenantContextResolver tenantContextResolver) {
        this.intakeService = intakeService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/api/consultations")
    public ResponseEntity<ConsultationResponse> createConsultation(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @Valid @RequestBody CreateConsultationRequest request
    ) {
        String tenantId = tenantContextResolver.requireActiveTenant(organizationId).slug();
        ConsultationResponse consultation = intakeService.createConsultation(tenantId, request);
        URI location = URI.create("/api/consultations/" + consultation.id());
        return ResponseEntity.created(location).body(consultation);
    }

    @GetMapping("/api/consultations")
    public ConsultationListResponse consultationsForTenant(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId
    ) {
        String tenantId = tenantContextResolver.requireActiveTenant(organizationId).slug();
        return intakeService.consultationsForTenant(tenantId);
    }
}
