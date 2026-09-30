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
class ClerkJwtValidationTests {
    private static final String ISSUER = "https://clerk.test.legal-gate.co";
    private static final String AUTHORIZED_PARTY = "https://console.test.legal-gate.co";
    private static final RSAKey signingKey;
    private static final HttpServer jwksServer;
    @Autowired MockMvc mockMvc;

    static {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("clerk-test-key").generate();
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
        registry.add("legalgate.gateway.auth.jwks-url",
                () -> "http://localhost:" + jwksServer.getAddress().getPort() + "/jwks");
    }

    @Test
    void acceptsAValidSessionToken() throws Exception {
        mockMvc.perform(get("/api/session").header("Authorization", "Bearer " + token(
                        signingKey, ISSUER, AUTHORIZED_PARTY, "org:admin", Instant.now().plusSeconds(300))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsWrongIssuerAuthorizedPartyExpiredAndBadSignature() throws Exception {
        assertUnauthorized(token(signingKey, "https://wrong.example", AUTHORIZED_PARTY, "org:admin",
                Instant.now().plusSeconds(300)));
        assertUnauthorized(token(signingKey, ISSUER, "https://attacker.example", "org:admin",
                Instant.now().plusSeconds(300)));
        assertUnauthorized(token(signingKey, ISSUER, AUTHORIZED_PARTY, "org:admin",
                Instant.now().minusSeconds(1)));
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("clerk-test-key").generate();
        assertUnauthorized(token(attacker, ISSUER, AUTHORIZED_PARTY, "org:admin",
                Instant.now().plusSeconds(300)));
    }

    @Test
    void forbidsAnOrganizationMemberOnBusinessRoutes() throws Exception {
        mockMvc.perform(get("/api/session").header("Authorization", "Bearer " + token(
                        signingKey, ISSUER, AUTHORIZED_PARTY, "org:member", Instant.now().plusSeconds(300))))
                .andExpect(status().isForbidden());
    }

    private void assertUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private static String token(
            RSAKey key,
            String issuer,
            String authorizedParty,
            String organizationRole,
            Instant expiresAt
    ) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user_1")
                .claim("azp", authorizedParty)
                .claim("sid", "session_1")
                .claim("org_id", "org_1")
                .claim("org_role", organizationRole)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
