package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.repository.IntakeRepository;
import org.springframework.stereotype.Component;

/**
 * The firm's name as a potential client sees it. Comes from the tenant's organization display name,
 * falling back to the global sender name so no mail is ever sent from a blank sender.
 */
@Component
class FirmNameResolver {

    private final IntakeRepository intakeRepository;
    private final IntakeProperties intakeProperties;

    FirmNameResolver(IntakeRepository intakeRepository, IntakeProperties intakeProperties) {
        this.intakeRepository = intakeRepository;
        this.intakeProperties = intakeProperties;
    }

    String firmNameFor(String tenantSlug) {
        return intakeRepository.tenantDisplayName(tenantSlug)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .orElseGet(intakeProperties::notificationsFromName);
    }
}
