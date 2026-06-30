package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.legalgate.intake.config.IntakeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WorkosClientTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void collectsOrganizationMembershipsAcrossAllPages() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        List<String> queries = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/user_management/organization_memberships", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            int page = requestCount.getAndIncrement();
            String body = page == 0
                    ? """
                      {"data":[{"organization_id":"org_first"}],
                       "list_metadata":{"after":"om_cursor"}}
                      """
                    : """
                      {"data":[{"organization_id":"org_second"}],
                       "list_metadata":{"after":null}}
                      """;
            respond(exchange, body);
        });
        server.start();

        WorkosClient client = new WorkosClient(RestClient.builder(), properties());

        assertThat(client.organizationMembershipIds("user_123"))
                .containsExactly("org_first", "org_second");
        assertThat(queries).hasSize(2);
        assertThat(queries.get(0)).contains("user_id=user_123", "limit=100");
        assertThat(queries.get(1)).contains("after=om_cursor");
    }

    private IntakeProperties properties() {
        return new IntakeProperties(
                "memory",
                false,
                "intake.legal-gate.co",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                false,
                "test-service-token",
                "sk_test",
                "http://localhost:" + server.getAddress().getPort()
        );
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
