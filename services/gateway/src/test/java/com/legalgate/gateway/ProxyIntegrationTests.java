package com.legalgate.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProxyIntegrationTests {

    private static HttpServer backend;
    private static ExecutorService backendExecutor;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress(0), 0);
        backend.createContext("/api/status", ProxyIntegrationTests::respondWithCase);
        backend.createContext("/api/tenants/firma-demo/consultations", ProxyIntegrationTests::respondWithCase);
        backendExecutor = Executors.newSingleThreadExecutor();
        backend.setExecutor(backendExecutor);
        backend.start();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
        if (backendExecutor != null) {
            backendExecutor.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("legalgate.gateway.forwarded-token", () -> "internal-service-token");
        registry.add("legalgate.gateway.backend.base-url", () -> "http://localhost:" + backend.getAddress().getPort());
    }

    @Test
    void prototypeRequestsAreProxiedToConfiguredBackendWithoutSharedSecret() throws Exception {
        mockMvc.perform(get("/api/backend/api/status?include=summary"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Proxied-By", "legal-gate-gateway"))
                .andExpect(jsonPath("$.id").value("42"))
                .andExpect(jsonPath("$.path").value("/api/status"))
                .andExpect(jsonPath("$.query").value("include=summary"))
                .andExpect(jsonPath("$.gatewayToken").value("internal-service-token"));
    }

    @Test
    void unsupportedOrUnavailableBackendResponsesUseGatewayFallbackShape() throws Exception {
        mockMvc.perform(post("/api/backend/api/tenants/firma-demo/consultations")
                        .contentType("application/json")
                        .content("{\"name\":\"client\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("service_unavailable"))
                .andExpect(jsonPath("$.service").value("backend"));
    }

    private static void respondWithCase(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String token = exchange.getRequestHeaders().getFirst("X-LegalGate-Service-Token");
        String body = "{\"id\":\"42\",\"path\":\"" + exchange.getRequestURI().getPath()
                + "\",\"query\":\"" + exchange.getRequestURI().getRawQuery()
                + "\",\"gatewayToken\":\"" + token + "\"}";
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
