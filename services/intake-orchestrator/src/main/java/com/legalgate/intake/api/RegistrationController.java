package com.legalgate.intake.api;

import com.legalgate.intake.model.RegisterRequest;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.service.RegistrationService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegistrationResponse response = registrationService.registerFirmOwner(request);
        URI location = URI.create("/api/admin/tenants/" + response.tenantId() + "/consultations");
        return ResponseEntity.created(location).body(response);
    }
}
