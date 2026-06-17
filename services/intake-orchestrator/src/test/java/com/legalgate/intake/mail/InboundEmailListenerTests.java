package com.legalgate.intake.mail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InboundEmailListenerTests {

    private final InboundEmailListener listener = new InboundEmailListener();

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

        assertThatCode(() -> listener.receive(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMalformedInboundEmailEventsSoRabbitCanDeadLetterThem() {
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

        assertThatThrownBy(() -> listener.receive(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }
}
