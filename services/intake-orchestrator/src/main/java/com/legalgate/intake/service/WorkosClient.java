package com.legalgate.intake.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalgate.intake.config.IntakeProperties;
import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WorkosClient {
    private final RestClient restClient;

    public WorkosClient(RestClient.Builder builder, IntakeProperties properties) {
        this.restClient = builder
                .baseUrl(properties.workosApiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.workosApiKey())
                .build();
    }

    public boolean hasOrganizationMembership(String userId) {
        return !organizationMembershipIds(userId).isEmpty();
    }

    public List<String> organizationMembershipIds(String userId) {
        URI uri = UriComponentsBuilder.fromPath("/user_management/organization_memberships")
                .queryParam("user_id", userId)
                .queryParam("limit", 10)
                .build().toUri();
        JsonNode response = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (response == null || !response.path("data").isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(response.path("data").spliterator(), false)
                .map(item -> item.path("organization_id").asText(""))
                .filter(value -> !value.isBlank())
                .toList();
    }

    public Optional<String> organizationByExternalId(String externalId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/organizations/external_id/{externalId}", externalId)
                    .retrieve().body(JsonNode.class);
            return response == null ? Optional.empty() : Optional.ofNullable(response.path("id").textValue());
        } catch (HttpClientErrorException.NotFound ignored) {
            return Optional.empty();
        }
    }

    public String createOrganization(String name, String externalId) {
        JsonNode response = restClient.post()
                .uri("/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "external_id", externalId))
                .retrieve().body(JsonNode.class);
        if (response == null || response.path("id").textValue() == null) {
            throw new IllegalStateException("WorkOS did not return an organization ID.");
        }
        return response.path("id").textValue();
    }

    public void createFirmAdminMembership(String userId, String organizationId) {
        restClient.post()
                .uri("/user_management/organization_memberships")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "user_id", userId,
                        "organization_id", organizationId,
                        "role_slug", "firm_admin"))
                .retrieve().toBodilessEntity();
    }
}
