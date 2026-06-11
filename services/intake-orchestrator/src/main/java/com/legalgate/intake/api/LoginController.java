package com.legalgate.intake.api;

import com.legalgate.intake.model.LoginRequest;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.service.LoginService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<RegistrationResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(loginService.loginFirmOwner(request));
    }
}
