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
    private final WorkosClient workosClient;
    private final IntakeProperties properties;

    public OrganizationOnboardingService(
            IntakeRepository repository,
            WorkosClient workosClient,
            IntakeProperties properties
    ) {
        this.repository = repository;
        this.workosClient = workosClient;
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
        if (local == null && workosClient.hasOrganizationMembership(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user_already_has_organization");
        }
        if (local == null) {
            String slug = uniqueSlug(firmName);
            local = repository.startTenantProvisioning(
                    userId, firmName.trim(), slug, properties.canonicalIntakeEmail(slug));
        }
        if ("ACTIVE".equals(local.status())) {
            return OrganizationOnboardingResponse.from(local);
        }

        try {
            String organizationId = workosClient.organizationByExternalId(local.id()).orElse(null);
            if (organizationId == null) {
                organizationId = workosClient.createOrganization(local.displayName(), local.id());
            }
            List<String> membershipOrganizations = workosClient.organizationMembershipIds(userId);
            if (membershipOrganizations.isEmpty()) {
                workosClient.createFirmAdminMembership(userId, organizationId);
            } else if (!membershipOrganizations.contains(organizationId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "user_already_has_organization");
            }
            TenantProvisioning active = repository.activateTenantProvisioning(
                    local.id(), local.slug(), organizationId);
            return OrganizationOnboardingResponse.from(active);
        } catch (ResponseStatusException exception) {
            repository.failTenantProvisioning(local.id(), local.slug(), exception.getReason());
            throw exception;
        } catch (RuntimeException exception) {
            repository.failTenantProvisioning(local.id(), local.slug(), exception.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "workos_provisioning_failed", exception);
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
