package com.legalgate.mail.service;

import com.legalgate.mail.model.CloudMailinMessage;
import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.InboundEmailIngestionResult;
import com.legalgate.mail.model.NormalizedInboundEmail;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CloudMailinIngestionService {

    private final RecipientAddressExtractor recipientAddressExtractor;
    private final InboundEmailIngestionService inboundEmailIngestionService;
    private final AutoResponderDetector autoResponderDetector;

    public CloudMailinIngestionService(
            RecipientAddressExtractor recipientAddressExtractor,
            InboundEmailIngestionService inboundEmailIngestionService,
            AutoResponderDetector autoResponderDetector
    ) {
        this.recipientAddressExtractor = recipientAddressExtractor;
        this.inboundEmailIngestionService = inboundEmailIngestionService;
        this.autoResponderDetector = autoResponderDetector;
    }

    public InboundEmailIngestionResult ingest(CloudMailinMessage message) {
        List<String> recipients = recipientAddressExtractor.recipientsFor(message);
        return inboundEmailIngestionService.ingest(new NormalizedInboundEmail(
                recipients,
                message.envelope().from(),
                autoResponderDetector.headerValue(message.headers(), "from"),
                autoResponderDetector.headerValue(message.headers(), "subject"),
                autoResponderDetector.headerValue(message.headers(), "message_id"),
                message.replyPlain() == null || message.replyPlain().isBlank() ? message.plain() : message.replyPlain(),
                message.html(),
                autoResponderDetector.isAutoResponder(message.headers())
        ));
    }
}
