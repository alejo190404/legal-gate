package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.repository.IntakeRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The firm's name as a potential client sees it. Comes from the tenant's organization display name,
 * falling back to the global sender name so no mail is ever sent from a blank sender.
 */
@Component
public class FirmNameResolver {

    private final IntakeRepository intakeRepository;
    private final IntakeProperties intakeProperties;

    public FirmNameResolver(IntakeRepository intakeRepository, IntakeProperties intakeProperties) {
        this.intakeRepository = intakeRepository;
        this.intakeProperties = intakeProperties;
    }

    public String firmNameFor(String tenantSlug) {
        return firmDisplayName(tenantSlug).orElseGet(intakeProperties::notificationsFromName);
    }

    /**
     * The firm's own name only, with no fallback. A sender address must always carry some display
     * name, but a signature inside a potential client's mail must not: falling back there would
     * sign the firm's correspondence "LegalGate Agenda", which ADR 0004 forbids.
     */
    public Optional<String> firmDisplayName(String tenantSlug) {
        return intakeRepository.tenantDisplayName(tenantSlug)
                .map(String::trim)
                .filter(name -> !name.isEmpty());
    }
}
