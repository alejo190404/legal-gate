package com.legalgate.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProxyIntegrationTests {
    private static HttpServer backend;
    private static ExecutorService executor;
    @Autowired MockMvc mockMvc;

    static synchronized void startBackend() {
        if (backend != null) {
            return;
        }
        try {
            backend = HttpServer.create(new InetSocketAddress(0), 0);
            backend.createContext("/api/session", ProxyIntegrationTests::echoHeaders);
            backend.createContext("/api/webhooks/mercadopago", ProxyIntegrationTests::echoWebhook);
            executor = Executors.newSingleThreadExecutor();
            backend.setExecutor(executor);
            backend.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start the proxy test backend.", exception);
        }
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) backend.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        startBackend();
        registry.add("legalgate.gateway.backend.base-url",
                () -> "http://localhost:" + backend.getAddress().getPort());
    }

    @Test
    void proxyRebuildsTrustedHeadersAndStripsSpoofedValues() throws Exception {
        mockMvc.perform(get("/api/session")
                        .header("X-LegalGate-User-Id", "attacker")
                        .header("X-LegalGate-Organization-Id", "org_attacker")
                        .header("Cookie", "session=attacker")
                        .header("Forwarded", "host=attacker.example")
                        .header("X-Forwarded-For", "203.0.113.10")
                        .with(jwt().jwt(token -> token
                                .subject("user_real")
                                .claim("sid", "session_real")
                                .claim("org_id", "org_real")
                                .claim("role", "firm_admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_FIRM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("user_real"))
                .andExpect(jsonPath("$.organization").value("org_real"))
                .andExpect(jsonPath("$.session").value("session_real"))
                .andExpect(jsonPath("$.role").value("firm_admin"))
                .andExpect(jsonPath("$.serviceToken").value("test-service-token"))
                .andExpect(jsonPath("$.authorization").isEmpty())
                .andExpect(jsonPath("$.cookie").isEmpty())
                .andExpect(jsonPath("$.forwarded").isEmpty())
                .andExpect(jsonPath("$.forwardedFor").isEmpty());
    }

    @Test
    void anonymousWebhookForwardsOnlyTheSignedEnvelope() throws Exception {
        mockMvc.perform(post("/api/webhooks/mercadopago")
                        .queryParam("data.id", "payment-1")
                        .header("x-signature", "ts=1,v1=abc")
                        .header("x-request-id", "request-1")
                        .header("Authorization", "Bearer attacker")
                        .header("Cookie", "session=attacker")
                        .header("X-LegalGate-User-Id", "attacker")
                        .contentType("application/json")
                        .content("{\"data\":{\"id\":\"payment-1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signature").value("ts=1,v1=abc"))
                .andExpect(jsonPath("$.requestId").value("request-1"))
                .andExpect(jsonPath("$.serviceToken").value("test-service-token"))
                .andExpect(jsonPath("$.authorization").isEmpty())
                .andExpect(jsonPath("$.cookie").isEmpty())
                .andExpect(jsonPath("$.legalGateUser").isEmpty())
                .andExpect(jsonPath("$.query").value("data.id=payment-1"));
    }

    private static void echoHeaders(HttpExchange exchange) throws IOException {
        String body = """
                {"user":"%s","organization":"%s","session":"%s","role":"%s","serviceToken":"%s","authorization":"%s","cookie":"%s","forwarded":"%s","forwardedFor":"%s"}
                """.formatted(
                header(exchange, "X-LegalGate-User-Id"),
                header(exchange, "X-LegalGate-Organization-Id"),
                header(exchange, "X-LegalGate-Session-Id"),
                header(exchange, "X-LegalGate-Role"),
                header(exchange, "X-LegalGate-Service-Token"),
                header(exchange, "Authorization"),
                header(exchange, "Cookie"),
                header(exchange, "Forwarded"),
                header(exchange, "X-Forwarded-For"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void echoWebhook(HttpExchange exchange) throws IOException {
        String body = """
                {"signature":"%s","requestId":"%s","serviceToken":"%s","authorization":"%s","cookie":"%s","legalGateUser":"%s","query":"%s"}
                """.formatted(
                header(exchange, "x-signature"),
                header(exchange, "x-request-id"),
                header(exchange, "X-LegalGate-Service-Token"),
                header(exchange, "Authorization"),
                header(exchange, "Cookie"),
                header(exchange, "X-LegalGate-User-Id"),
                exchange.getRequestURI().getRawQuery());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }
}
