package com.legalgate.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "legalgate.gateway.backend.base-url=")
@AutoConfigureMockMvc
class WorkosJwtValidationTests {
    private static final RSAKey signingKey;
    private static final HttpServer jwksServer;
    @Autowired MockMvc mockMvc;

    static {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("workos-test-key").generate();
            jwksServer = HttpServer.create(new InetSocketAddress(0), 0);
            jwksServer.createContext("/jwks", exchange -> {
                byte[] body = ("{\"keys\":[" + signingKey.toPublicJWK().toJSONString() + "]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            jwksServer.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @AfterAll
    static void stopJwks() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("legalgate.gateway.workos.jwks-url",
                () -> "http://localhost:" + jwksServer.getAddress().getPort() + "/jwks");
    }

    @Test
    void acceptsAValidWorkosToken() throws Exception {
        mockMvc.perform(get("/api/session").header("Authorization", "Bearer " + token(
                        signingKey, "https://api.workos.com", "client_test", Instant.now().plusSeconds(300))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsWrongIssuerClientExpiredAndBadSignature() throws Exception {
        assertUnauthorized(token(signingKey, "https://wrong.example", "client_test", Instant.now().plusSeconds(300)));
        assertUnauthorized(token(signingKey, "https://api.workos.com", "client_wrong", Instant.now().plusSeconds(300)));
        assertUnauthorized(token(signingKey, "https://api.workos.com", "client_test", Instant.now().minusSeconds(1)));
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("workos-test-key").generate();
        assertUnauthorized(token(attacker, "https://api.workos.com", "client_test", Instant.now().plusSeconds(300)));
    }

    private void assertUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private static String token(RSAKey key, String issuer, String clientId, Instant expiresAt) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user_1")
                .claim("client_id", clientId)
                .claim("sid", "session_1")
                .claim("org_id", "org_1")
                .claim("role", "firm_admin")
                .claim("roles", List.of("firm_admin"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
