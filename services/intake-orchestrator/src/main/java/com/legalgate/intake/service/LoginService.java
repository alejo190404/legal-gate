package com.legalgate.intake.service;

import com.legalgate.intake.model.LoginRequest;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
import com.legalgate.intake.repository.IntakeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoginService {

    private final IntakeRepository intakeRepository;
    private final PasswordHasher passwordHasher;

    public LoginService(IntakeRepository intakeRepository, PasswordHasher passwordHasher) {
        this.intakeRepository = intakeRepository;
        this.passwordHasher = passwordHasher;
    }

    public RegistrationResponse loginFirmOwner(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        StoredUserCredentials user = intakeRepository.findActiveUserByEmail(email)
                .orElseThrow(this::invalidCredentials);

        if (!passwordHasher.matches(request.password(), user.hashedPassword())) {
            throw invalidCredentials();
        }

        intakeRepository.recordLogin(user.email());
        return user.toSession();
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
    }
}
