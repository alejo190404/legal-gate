package com.legalgate.intake.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalgate.intake.config.IntakeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class ClerkClient {
    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;

    public ClerkClient(RestClient.Builder builder, IntakeProperties properties) {
        this.restClient = builder
                .baseUrl(properties.clerkApiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.clerkSecretKey())
                .build();
    }

    public boolean hasOrganizationMembership(String userId) {
        return !organizationMembershipIds(userId).isEmpty();
    }

    public List<String> organizationMembershipIds(String userId) {
        List<String> organizationIds = new ArrayList<>();
        int offset = 0;
        while (true) {
            final int currentOffset = offset;
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/users/{userId}/organization_memberships")
                            .queryParam("limit", PAGE_SIZE)
                            .queryParam("offset", currentOffset)
                            .build(userId))
                    .retrieve().body(JsonNode.class);
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                break;
            }
            StreamSupport.stream(data.spliterator(), false)
                    .map(item -> item.path("organization").path("id").asText(""))
                    .filter(value -> !value.isBlank())
                    .forEach(organizationIds::add);
            if (data.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return List.copyOf(organizationIds);
    }

    public Optional<String> userEmail(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode response = restClient.get()
                    .uri("/users/{userId}", userId)
                    .retrieve().body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }
            String email = primaryEmail(response);
            return email == null || email.isBlank()
                    ? Optional.empty()
                    : Optional.of(email.trim().toLowerCase(Locale.ROOT));
        } catch (RestClientException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Creates the organization with the signed-in user as its creator. Clerk grants the creator
     * the built-in org:admin role, which is what the Gateway maps to ROLE_FIRM_ADMIN, so no
     * separate membership call is needed.
     */
    public String createOrganization(String name, String createdBy) {
        JsonNode response = restClient.post()
                .uri("/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "created_by", createdBy))
                .retrieve().body(JsonNode.class);
        if (response == null || response.path("id").textValue() == null) {
            throw new IllegalStateException("Clerk did not return an organization ID.");
        }
        return response.path("id").textValue();
    }

    private static String primaryEmail(JsonNode user) {
        JsonNode addresses = user.path("email_addresses");
        if (!addresses.isArray()) {
            return null;
        }
        String primaryId = user.path("primary_email_address_id").textValue();
        String fallback = null;
        for (JsonNode address : addresses) {
            String value = address.path("email_address").textValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (primaryId != null && primaryId.equals(address.path("id").textValue())) {
                return value;
            }
            if (fallback == null) {
                fallback = value;
            }
        }
        return fallback;
    }
}
