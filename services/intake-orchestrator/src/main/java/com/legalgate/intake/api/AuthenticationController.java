package com.legalgate.intake.api;

import com.legalgate.intake.model.LoginRequest;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/api/auth/login")
    public RegistrationResponse login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.login(request);
    }
}
