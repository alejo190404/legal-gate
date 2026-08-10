package com.legalgate.mail.service;

import com.legalgate.mail.model.CloudMailinMessage;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.InboundEmailIngestionResult;
import com.legalgate.mail.model.NormalizedInboundEmail;
import java.util.List;
import java.util.Locale;
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

    public InboundEmailIngestionResult ingest(CloudMailinMessage message) {
        List<String> recipients = recipientAddressExtractor.recipientsFor(message);
        return inboundEmailIngestionService.ingest(new NormalizedInboundEmail(
                recipients,
                message.envelope().from(),
                headerValue(message.headers(), "from"),
                headerValue(message.headers(), "subject"),
                headerValue(message.headers(), "message_id"),
                message.replyPlain() == null || message.replyPlain().isBlank() ? message.plain() : message.replyPlain(),
                message.html(),
                isAutoResponder(message.headers())
        ));
    }

    // Vacation responders and mailing-list bulk mail must never be answered, or LegalGate
    // ends up in a reply loop with another robot. RFC 3834 / RFC 2076 markers.
    private boolean isAutoResponder(Map<String, Object> headers) {
        String autoSubmitted = headerValue(headers, "auto_submitted");
        if (autoSubmitted != null && !autoSubmitted.trim().equalsIgnoreCase("no")) {
            return true;
        }
        String precedence = headerValue(headers, "precedence");
        if (precedence != null && List.of("bulk", "list", "junk").contains(precedence.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        // Deliberately not List-Unsubscribe: plenty of human-sent mail relayed through an ESP
        // carries it, and a false positive here silently drops a real potential client.
        return headerValue(headers, "x_autoreply") != null
                || headerValue(headers, "x_autorespond") != null;
    }

    // CloudMailin underscores header names, other providers keep the hyphens; match either.
    private String headerValue(Map<String, Object> headers, String key) {
        if (headers == null) {
            return null;
        }
        Object value = headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && normalizeHeaderName(entry.getKey()).equals(key))
                .map(Map.Entry::getValue)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (value instanceof List<?> values) {
            return values.isEmpty() || values.get(0) == null ? null : values.get(0).toString();
        }
        return value == null ? null : value.toString();
    }

    private String normalizeHeaderName(String key) {
        return key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
