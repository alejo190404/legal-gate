package com.legalgate.intake.api;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.service.IntakeService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConsultationController {

    private final IntakeService intakeService;

    public ConsultationController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping("/api/tenants/{tenantId}/consultations")
    public ResponseEntity<ConsultationResponse> createConsultation(
            @PathVariable String tenantId,
            @Valid @RequestBody CreateConsultationRequest request
    ) {
        ConsultationResponse consultation = intakeService.createConsultation(tenantId, request);
        URI location = URI.create("/api/tenants/" + tenantId + "/consultations/" + consultation.id());
        return ResponseEntity.created(location).body(consultation);
    }

    @GetMapping("/api/admin/tenants/{tenantId}/consultations")
    public ConsultationListResponse consultationsForTenant(@PathVariable String tenantId) {
        return intakeService.consultationsForTenant(tenantId);
    }
}
