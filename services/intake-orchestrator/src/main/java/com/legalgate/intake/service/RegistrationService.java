package com.legalgate.intake.service;

import com.legalgate.intake.model.RegisterRequest;
import com.legalgate.intake.model.RegistrationResponse;
import com.legalgate.intake.repository.IntakeRepository;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {

    private static final Pattern NON_ASCII_MARK = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG_CHAR = Pattern.compile("[^a-z0-9]+");
    private static final String FIRM_ADMIN_ROLE = "FIRM_ADMIN";

    private final IntakeRepository intakeRepository;
    private final PasswordHasher passwordHasher;

    public RegistrationService(IntakeRepository intakeRepository, PasswordHasher passwordHasher) {
        this.intakeRepository = intakeRepository;
        this.passwordHasher = passwordHasher;
    }

    public RegistrationResponse registerFirmOwner(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String firmName = request.firmName().trim();
        String firmSlug = slugify(firmName);
        String passwordHash = passwordHasher.hash(request.password());

        // Users are firm owner/admin accounts. They are intentionally separate from Lawyer,
        // which the ERD models as a business entity that can later be associated to a user.
        return intakeRepository.registerFirmOwner(firmSlug, firmName, email, passwordHash, FIRM_ADMIN_ROLE);
    }

    private String slugify(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        String withoutMarks = NON_ASCII_MARK.matcher(normalized).replaceAll("");
        String slug = NON_SLUG_CHAR.matcher(withoutMarks.toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("Firm name must contain at least one letter or number.");
        }
        return slug;
    }
}
