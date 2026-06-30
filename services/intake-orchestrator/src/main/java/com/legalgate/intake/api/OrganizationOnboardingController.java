package com.legalgate.intake.api;

import com.legalgate.intake.model.OrganizationOnboardingRequest;
import com.legalgate.intake.model.OrganizationOnboardingResponse;
import com.legalgate.intake.service.OrganizationOnboardingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrganizationOnboardingController {
    private final OrganizationOnboardingService onboardingService;

    public OrganizationOnboardingController(OrganizationOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping("/api/onboarding/organization")
    public OrganizationOnboardingResponse onboard(
            @RequestHeader("X-LegalGate-User-Id") String userId,
            @RequestHeader(value = "X-LegalGate-Organization-Id", required = false) String organizationId,
            @Valid @RequestBody OrganizationOnboardingRequest request
    ) {
        return onboardingService.onboard(userId, organizationId, request.firmName());
    }
}
