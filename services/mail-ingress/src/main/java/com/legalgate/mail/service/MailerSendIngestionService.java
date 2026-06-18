package com.legalgate.mail.service;

import com.legalgate.mail.model.InboundEmailReceived;
import com.legalgate.mail.model.MailerSendWebhook;
import com.legalgate.mail.model.NormalizedInboundEmail;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MailerSendIngestionService {

    private final InboundEmailIngestionService inboundEmailIngestionService;

    public MailerSendIngestionService(InboundEmailIngestionService inboundEmailIngestionService) {
        this.inboundEmailIngestionService = inboundEmailIngestionService;
    }

    public InboundEmailReceived ingest(MailerSendWebhook webhook) {
        MailerSendWebhook.Data data = webhook.data();
        return inboundEmailIngestionService.ingest(new NormalizedInboundEmail(
                recipientsFor(data),
                data == null || data.from() == null ? null : data.from().email(),
                data == null ? null : formatFrom(data.from()),
                data == null ? null : data.subject(),
                data == null ? null : data.messageId(),
                plainFor(data),
                htmlFor(data)
        ));
    }

    private List<String> recipientsFor(MailerSendWebhook.Data data) {
        Set<String> recipients = new LinkedHashSet<>();
        if (data != null) {
            add(recipients, data.recipient());
            addAll(recipients, data.recipients());
            addAll(recipients, data.to());
        }
        return List.copyOf(recipients);
    }

    private void addAll(Set<String> recipients, List<MailerSendWebhook.Recipient> values) {
        if (values != null) {
            values.forEach(value -> add(recipients, value));
        }
    }

    private void add(Set<String> recipients, MailerSendWebhook.Recipient value) {
        if (value != null && value.email() != null && !value.email().isBlank()) {
            recipients.add(value.email().trim().toLowerCase(Locale.ROOT));
        }
    }

    private String formatFrom(MailerSendWebhook.From from) {
        if (from == null || from.email() == null || from.email().isBlank()) {
            return null;
        }
        if (from.name() == null || from.name().isBlank()) {
            return from.email();
        }
        return from.name().trim() + " <" + from.email().trim() + ">";
    }

    private String plainFor(MailerSendWebhook.Data data) {
        if (data == null) {
            return null;
        }
        if (data.plain() != null && !data.plain().isBlank()) {
            return data.plain();
        }
        return data.text() == null ? null : data.text().plain();
    }

    private String htmlFor(MailerSendWebhook.Data data) {
        if (data == null) {
            return null;
        }
        if (data.html() != null && !data.html().isBlank()) {
            return data.html();
        }
        return data.text() == null ? null : data.text().html();
    }
}
