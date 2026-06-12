package com.legalgate.intake.service;

import com.legalgate.intake.model.LoginRequest;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.model.StoredUserCredentials;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private final IntakeRepository intakeRepository;
    private final PasswordHasher passwordHasher;

    public AuthenticationService(IntakeRepository intakeRepository, PasswordHasher passwordHasher) {
        this.intakeRepository = intakeRepository;
        this.passwordHasher = passwordHasher;
    }

    public RegistrationResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        StoredUserCredentials user = intakeRepository.findActiveUserByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordHasher.matches(request.password(), user.hashedPassword())) {
            throw new InvalidCredentialsException();
        }
        intakeRepository.recordSuccessfulLogin(user.email());
        return user.toSession();
    }
}
