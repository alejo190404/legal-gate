package com.legalgate.mail.service;

import com.legalgate.mail.model.InboundEmailIngestionResult;
import com.legalgate.mail.model.NormalizedInboundEmail;
import com.legalgate.mail.model.ResendReceivedEmail;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ResendIngestionService {

    private final ResendReceivedEmailClient resendReceivedEmailClient;
    private final RecipientAddressExtractor recipientAddressExtractor;
    private final InboundEmailIngestionService inboundEmailIngestionService;
    private final AutoResponderDetector autoResponderDetector;

    public ResendIngestionService(
            ResendReceivedEmailClient resendReceivedEmailClient,
            RecipientAddressExtractor recipientAddressExtractor,
            InboundEmailIngestionService inboundEmailIngestionService,
            AutoResponderDetector autoResponderDetector
    ) {
        this.resendReceivedEmailClient = resendReceivedEmailClient;
        this.recipientAddressExtractor = recipientAddressExtractor;
        this.inboundEmailIngestionService = inboundEmailIngestionService;
        this.autoResponderDetector = autoResponderDetector;
    }

    public InboundEmailIngestionResult ingest(String messageStoreId) {
        ResendReceivedEmail email = resendReceivedEmailClient.fetch(messageStoreId);
        return inboundEmailIngestionService.ingest(new NormalizedInboundEmail(
                recipients(email),
                email.from(),
                email.from(),
                email.subject(),
                email.messageId(),
                email.text(),
                email.html(),
                autoResponderDetector.isAutoResponder(email.headers())
        ));
    }

    /**
     * {@code received_for} is the address the message was actually delivered to, which is where a
     * plus-addressed Reply Token survives when {@code to} has been rewritten. It goes first so the
     * tenant lookup sees it before any alias.
     */
    private List<String> recipients(ResendReceivedEmail email) {
        List<String> addresses = new ArrayList<>();
        if (email.receivedFor() != null) {
            addresses.add(email.receivedFor());
        }
        if (email.to() != null) {
            addresses.addAll(email.to());
        }
        return recipientAddressExtractor.normalizeAll(addresses);
    }
}
