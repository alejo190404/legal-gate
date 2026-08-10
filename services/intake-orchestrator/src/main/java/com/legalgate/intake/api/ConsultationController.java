package com.legalgate.intake.api;

import com.legalgate.intake.model.ConsultationListResponse;
import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.CreateConsultationRequest;
import com.legalgate.intake.model.DiagnosticsView;
import com.legalgate.intake.service.DiagnosticsService;
import com.legalgate.intake.service.IntakeService;
import com.legalgate.intake.service.TenantContextResolver;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ConsultationController {

    private final IntakeService intakeService;
    private final DiagnosticsService diagnosticsService;
    private final TenantContextResolver tenantContextResolver;

    public ConsultationController(
            IntakeService intakeService,
            DiagnosticsService diagnosticsService,
            TenantContextResolver tenantContextResolver
    ) {
        this.intakeService = intakeService;
        this.diagnosticsService = diagnosticsService;
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

    /** The transcript, verdict, reason and Prompt snapshot behind one Consultation's Diagnostics. */
    @GetMapping("/api/consultations/{consultationId}/diagnostics")
    public DiagnosticsView diagnostics(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @PathVariable String consultationId
    ) {
        String tenantId = tenantContextResolver.requireActiveTenant(organizationId).slug();
        return diagnosticsService.viewFor(tenantId, consultationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "diagnostics_session_not_found"));
    }

    /** Ends Diagnostics early and schedules the matter: the firm's override on a wrong verdict. */
    @PostMapping("/api/consultations/{consultationId}/accept")
    public ConsultationResponse acceptNow(
            @RequestHeader("X-LegalGate-Organization-Id") String organizationId,
            @PathVariable String consultationId
    ) {
        String tenantId = tenantContextResolver.requireActiveTenant(organizationId).slug();
        return diagnosticsService.acceptNow(tenantId, consultationId);
    }
}
