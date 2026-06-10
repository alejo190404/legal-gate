package com.legalgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "legalgate.gateway.backend.base-url=",
        "legalgate.gateway.forwarded-token=test-service-token",
        "legalgate.gateway.cors.allowed-origins=http://localhost:4200,https://app.example.test"
})
class GatewayApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusEndpointIsPublicAndDescribesGatewayWhenNoBackendIsConfigured() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("legal-gate-gateway"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.backendConfigured").value(false));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void prototypeGatewayFacadeReturnsFallbackWithoutSharedSecretWhenBackendIsNotConfigured() throws Exception {
        mockMvc.perform(get("/api/backend/api/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("service_unavailable"))
                .andExpect(jsonPath("$.service").value("backend"))
                .andExpect(jsonPath("$.message").value("LegalGate backend is not connected yet."));
    }

    @Test
    void unsupportedPrototypeRoutesAreNotPublic() throws Exception {
        mockMvc.perform(get("/api/backend/internal/admin-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void corsPreflightAllowsConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/backend/api/tenants/firma-demo/consultations")
                        .header("Origin", "https://app.example.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.test"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS"));
    }

    @Test
    void securityHeadersAreAppliedToPublicResponses() throws Exception {
        String frameOptions = mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn()
                .getResponse()
                .getHeader("X-Frame-Options");

        assertThat(frameOptions).isEqualTo("DENY");
    }
}
