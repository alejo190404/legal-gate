package com.legalgate.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "legalgate.gateway.backend.base-url=")
@AutoConfigureMockMvc
class GatewayApplicationTests {
    @Autowired MockMvc mockMvc;

    @Test
    void healthAndStatusArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/status")).andExpect(status().isOk());
    }

    @Test
    void businessRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void billingRequiresFirmAdminButMercadoPagoWebhookIsAnonymous() throws Exception {
        mockMvc.perform(get("/api/billing/subscription"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/webhooks/mercadopago")
                        .queryParam("data.id", "123")
                        .header("x-signature", "ts=1,v1=invalid")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void corsAllowsCheckoutIdempotencyHeader() throws Exception {
        mockMvc.perform(options("/api/billing/checkout")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", "idempotency-key"));
    }

    @Test
    void organizationAndFirmAdminAreRequired() throws Exception {
        mockMvc.perform(get("/api/session").with(jwt().jwt(token -> token
                        .subject("user_1").claim("sid", "session_1").claim("role", "firm_admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_FIRM_ADMIN"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/session").with(jwt().jwt(token -> token
                        .subject("user_1").claim("sid", "session_1").claim("org_id", "org_1")
                        .claim("role", "member"))
                        .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void oldTenantAndPasswordRoutesAreNotFoundForAuthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/auth/login").with(jwt()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tenants/old/settings").with(jwt()))
                .andExpect(status().isNotFound());
    }
}
