package com.legalgate.mail.service;

import com.legalgate.mail.model.CloudMailinMessage;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.NormalizedInboundEmail;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CloudMailinIngestionService {

    private final RecipientAddressExtractor recipientAddressExtractor;
    private final InboundEmailIngestionService inboundEmailIngestionService;

    public CloudMailinIngestionService(
            RecipientAddressExtractor recipientAddressExtractor,
            InboundEmailIngestionService inboundEmailIngestionService
    ) {
        this.recipientAddressExtractor = recipientAddressExtractor;
        this.inboundEmailIngestionService = inboundEmailIngestionService;
    }

    public InboundEmailReceived ingest(CloudMailinMessage message) {
        List<String> recipients = recipientAddressExtractor.recipientsFor(message);
        return inboundEmailIngestionService.ingest(new NormalizedInboundEmail(
                recipients,
                message.envelope().from(),
                headerValue(message.headers(), "from"),
                headerValue(message.headers(), "subject"),
                headerValue(message.headers(), "message_id"),
                message.replyPlain() == null || message.replyPlain().isBlank() ? message.plain() : message.replyPlain(),
                message.html()
        ));
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
