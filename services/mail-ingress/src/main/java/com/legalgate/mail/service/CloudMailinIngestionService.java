package com.legalgate.mail.service;

import com.legalgate.mail.model.CloudMailinMessage;
import com.legalgate.mail.model.InboundEmailReceived;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CloudMailinIngestionService {

    private final RecipientAddressExtractor recipientAddressExtractor;
    private final TenantLookupService tenantLookupService;
    private final InboundEmailPublisher inboundEmailPublisher;

    public CloudMailinIngestionService(
            RecipientAddressExtractor recipientAddressExtractor,
            TenantLookupService tenantLookupService,
            InboundEmailPublisher inboundEmailPublisher
    ) {
        this.recipientAddressExtractor = recipientAddressExtractor;
        this.tenantLookupService = tenantLookupService;
        this.inboundEmailPublisher = inboundEmailPublisher;
    }

    public InboundEmailReceived ingest(CloudMailinMessage message) {
        List<String> recipients = recipientAddressExtractor.recipientsFor(message);
        if (recipients.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "missing_recipient");
        }

        String tenantId = recipients.stream()
                .map(tenantLookupService::tenantForIntakeEmail)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_intake_recipient"));

        InboundEmailReceived event = new InboundEmailReceived(
                UUID.randomUUID().toString(),
                tenantId,
                recipients.get(0),
                recipients,
                message.envelope().from(),
                headerValue(message.headers(), "from"),
                headerValue(message.headers(), "subject"),
                headerValue(message.headers(), "message_id"),
                message.replyPlain() == null || message.replyPlain().isBlank() ? message.plain() : message.replyPlain(),
                message.html(),
                Instant.now()
        );

        try {
            inboundEmailPublisher.publish(event);
        } catch (AmqpException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "rabbitmq_publish_failed", ex);
        }

        return event;
    }

    private String headerValue(Map<String, Object> headers, String key) {
        if (headers == null || !headers.containsKey(key) || headers.get(key) == null) {
            return null;
        }
        Object value = headers.get(key);
        if (value instanceof List<?> values) {
            return values.isEmpty() || values.get(0) == null ? null : values.get(0).toString();
        }
        return value.toString();
    }
}
