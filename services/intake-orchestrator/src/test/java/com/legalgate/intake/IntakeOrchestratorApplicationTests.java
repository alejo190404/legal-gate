package com.legalgate.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.legalgate.intake.model.ConsultationResponse;
import com.legalgate.intake.model.TenantProvisioning;
import com.legalgate.intake.repository.IntakeRepository;
import com.legalgate.intake.service.ClerkClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "legalgate.intake.persistence=memory"
})
@AutoConfigureMockMvc
class IntakeOrchestratorApplicationTests {
    private static final String SERVICE_TOKEN = "test-service-token";
    @Autowired MockMvc mockMvc;
    @Autowired IntakeRepository repository;
    @MockBean ClerkClient clerkClient;

    @BeforeEach
    void clerkDefaults() {
        when(clerkClient.hasOrganizationMembership(anyString())).thenReturn(false);
        when(clerkClient.organizationMembershipIds(anyString())).thenReturn(List.of());
        when(clerkClient.createOrganization(anyString(), anyString())).thenReturn("org_created");
    }

    @Test
    void directBusinessCallsRequireTheInternalServiceToken() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_service_token"));
    }

    @Test
    void oldPasswordAndTenantSlugRoutesAreGone() throws Exception {
        mockMvc.perform(post("/api/auth/login").header("X-LegalGate-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tenants/old/settings")
                        .header("X-LegalGate-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void organizationHeaderResolvesOnlyItsMappedTenant() throws Exception {
        TenantProvisioning pending = repository.startTenantProvisioning(
                "user_existing", "Firma Existing", "firma-existing", "firma-existing@intake.legal-gate.co");
        repository.activateTenantProvisioning(pending.id(), pending.slug(), "org_existing");

        mockMvc.perform(get("/api/session")
                        .header("X-LegalGate-Service-Token", SERVICE_TOKEN)
                        .header("X-LegalGate-User-Id", "user_existing")
                        .header("X-LegalGate-Session-Id", "session_existing")
                        .header("X-LegalGate-Organization-Id", "org_existing")
                        .header("X-LegalGate-Role", "firm_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("firma-existing"))
                .andExpect(jsonPath("$.organizationId").value("org_existing"));

        mockMvc.perform(get("/api/session")
                        .header("X-LegalGate-Service-Token", SERVICE_TOKEN)
                        .header("X-LegalGate-User-Id", "user_existing")
                        .header("X-LegalGate-Session-Id", "session_existing")
                        .header("X-LegalGate-Organization-Id", "org_other")
                        .header("X-LegalGate-Role", "firm_admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onboardingIsIdempotentAndActivatesTheOrganizationMapping() throws Exception {
        String body = "{\"firmName\":\"Firma Nueva\"}";
        mockMvc.perform(post("/api/onboarding/organization")
                        .header("X-LegalGate-Service-Token", SERVICE_TOKEN)
                        .header("X-LegalGate-User-Id", "user_new")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value("org_created"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/onboarding/organization")
                        .header("X-LegalGate-Service-Token", SERVICE_TOKEN)
                        .header("X-LegalGate-User-Id", "user_new")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value("org_created"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void onboardingReportsBadGatewayWhenClerkCannotListMemberships() throws Exception {
        when(clerkClient.organizationMembershipIds("user_unreachable"))
                .thenThrow(new ResourceAccessException("Read timed out"));

        mockMvc.perform(post("/api/onboarding/organization")
                        .header("X-LegalGate-Service-Token", SERVICE_TOKEN)
                        .header("X-LegalGate-User-Id", "user_unreachable")
                        .contentType("application/json")
                        .content("{\"firmName\":\"Firma Lenta\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(result -> assertThat(((ResponseStatusException) result.getResolvedException()).getReason())
                        .isEqualTo("auth_provisioning_failed"));
    }

    @Test
    void theConsultationListCarriesEachConsultationsStage() throws Exception {
        TenantProvisioning pending = repository.startTenantProvisioning(
                "user_stage", "Firma Stage", "firma-stage", "firma-stage@intake.legal-gate.co");
        repository.activateTenantProvisioning(pending.id(), pending.slug(), "org_stage");
        repository.saveConsultation("firma-stage", new ConsultationResponse("c-stage", "firma-stage", "Ana",
                "ana@example.com", "Contrato", null, "DIAGNOSTICS_PENDING", "NORMAL", null, null, null, null,
                null, null, Instant.parse("2026-10-01T10:00:00Z")), List.of());

        mockMvc.perform(get("/api/consultations")
                        .header("X-LegalGate-Service-Token", SERVICE_TOKEN)
                        .header("X-LegalGate-User-Id", "user_stage")
                        .header("X-LegalGate-Session-Id", "session_stage")
                        .header("X-LegalGate-Organization-Id", "org_stage")
                        .header("X-LegalGate-Role", "firm_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consultations[0].stage").value("diagnostics"));
    }
}
