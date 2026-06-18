package com.legalgate.mail.service;

import com.legalgate.mail.model.CloudMailinMessage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class RecipientAddressExtractor {

    List<String> recipientsFor(CloudMailinMessage message) {
        Set<String> recipients = new LinkedHashSet<>();
        if (message.envelope() != null) {
            add(recipients, message.envelope().to());
            if (message.envelope().recipients() != null) {
                message.envelope().recipients().forEach(value -> add(recipients, value));
            }
        }
        return List.copyOf(recipients);
    }

    private void add(Set<String> recipients, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            recipients.add(normalized);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        int start = trimmed.indexOf('<');
        int end = trimmed.indexOf('>');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start + 1, end).trim();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
