package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.OrganizationOnboardingResponse;
import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.repository.IntakeRepository;
import java.text.Normalizer;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrganizationOnboardingService {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private final IntakeRepository repository;
    private final ClerkClient clerkClient;
    private final IntakeProperties properties;

    public OrganizationOnboardingService(
            IntakeRepository repository,
            ClerkClient clerkClient,
            IntakeProperties properties
    ) {
        this.repository = repository;
        this.clerkClient = clerkClient;
        this.properties = properties;
    }

    public OrganizationOnboardingResponse onboard(String userId, String claimedOrganizationId, String firmName) {
        if (claimedOrganizationId != null && !claimedOrganizationId.isBlank()) {
            TenantProvisioning existing = repository.tenantForOrganization(claimedOrganizationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "user_already_has_organization"));
            if (!userId.equals(existing.ownerId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "user_already_has_organization");
            }
            return OrganizationOnboardingResponse.from(existing);
        }

        TenantProvisioning local = repository.tenantForProvisioningOwner(userId).orElse(null);
        if (local != null && "ACTIVE".equals(local.status())) {
            return OrganizationOnboardingResponse.from(local);
        }

        // One round trip serves both rules: a user with no local tenant must not already belong to
        // an organization, and a retry after a half-finished attempt must adopt the organization
        // that attempt already created instead of making a second one for the same firm.
        List<String> memberships = clerkClient.organizationMembershipIds(userId);
        if (memberships.size() > 1 || (local == null && !memberships.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user_already_has_organization");
        }

        if (local == null) {
            String slug = uniqueSlug(firmName);
            local = repository.startTenantProvisioning(
                    userId, firmName.trim(), slug, properties.canonicalIntakeEmail(slug));
        }

        try {
            String organizationId = memberships.isEmpty()
                    ? clerkClient.createOrganization(local.displayName(), userId)
                    : memberships.get(0);
            TenantProvisioning active = repository.activateTenantProvisioning(
                    local.id(), local.slug(), organizationId);
            return OrganizationOnboardingResponse.from(active);
        } catch (ResponseStatusException exception) {
            repository.failTenantProvisioning(local.id(), local.slug(), exception.getReason());
            throw exception;
        } catch (RuntimeException exception) {
            repository.failTenantProvisioning(local.id(), local.slug(), exception.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "auth_provisioning_failed", exception);
        }
    }

    private String uniqueSlug(String name) {
        String normalized = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        String base = NON_SLUG.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_firm_name");
        }
        return (base.length() > 48 ? base.substring(0, 48).replaceAll("-+$", "") : base)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
