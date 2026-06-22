package com.legalgate.intake.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class InboundEmailControllerTests {

    private final InboundEmailController controller = new InboundEmailController();

    @Test
    void acceptsValidInboundEmailEvents() {
        InboundEmailReceived event = new InboundEmailReceived(
                "event-1",
                "firma-demo",
                "intake@firma.test",
                List.of("intake@firma.test"),
                "client@example.com",
                "Client <client@example.com>",
                "Consulta",
                "<message@example.com>",
                "Necesito orientacion.",
                "<p>Necesito orientacion.</p>",
                Instant.now()
        );

        ResponseEntity<Map<String, Object>> response = controller.receive(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "received")
                .containsEntry("eventId", "event-1")
                .containsEntry("tenantId", "firma-demo");
    }

    @Test
    void rejectsMalformedInboundEmailEventsWithBadRequest() {
        InboundEmailReceived event = new InboundEmailReceived(
                "event-1",
                "",
                "intake@firma.test",
                List.of("intake@firma.test"),
                "client@example.com",
                null,
                "Consulta",
                null,
                "Necesito orientacion.",
                null,
                Instant.now()
        );

        assertThatThrownBy(() -> controller.receive(event))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException responseStatus = (ResponseStatusException) exception;
                    assertThat(responseStatus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(responseStatus.getReason()).isEqualTo("missing_tenant_id");
                });
    }
}