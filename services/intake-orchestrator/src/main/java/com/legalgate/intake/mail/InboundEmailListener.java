package com.legalgate.intake.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "legalgate.intake.mail.enabled", havingValue = "true")
class InboundEmailListener {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailListener.class);

    @RabbitListener(queues = "${legalgate.intake.mail.incoming-queue}")
    void receive(InboundEmailReceived event) {
        if (event == null || event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("Inbound email event is missing tenantId");
        }
        log.info(
                "Received inbound email event id={} tenant={} from={} subject={}",
                event.eventId(),
                event.tenantId(),
                event.envelopeFrom(),
                event.subject()
        );
    }
}
