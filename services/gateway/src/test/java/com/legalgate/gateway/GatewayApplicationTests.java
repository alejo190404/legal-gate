package com.legalgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "legalgate.gateway.api-key=test-gateway-key",
        "legalgate.gateway.backend.base-url=",
        "legalgate.gateway.forwarded-token=test-service-token"
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
    void protectedGatewayRoutesRejectRequestsWithoutApiKey() throws Exception {
        mockMvc.perform(get("/api/backend/cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void protectedGatewayRoutesRejectRequestsWithWrongApiKey() throws Exception {
        mockMvc.perform(get("/api/backend/cases")
                        .header("X-Gateway-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void protectedGatewayRoutesReturnFallbackWhenBackendIsNotConfigured() throws Exception {
        mockMvc.perform(get("/api/backend/cases")
                        .header("X-Gateway-Api-Key", "test-gateway-key"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("service_unavailable"))
                .andExpect(jsonPath("$.service").value("backend"))
                .andExpect(jsonPath("$.message").value("LegalGate backend is not connected yet."));
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
