package com.legalgate.mail.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Reads the RFC 3834 / RFC 2076 headers that mark a message as machine-sent.
 *
 * <p>Provider-agnostic on purpose: every adapter has the headers, and an Auto-Responder that slips
 * through starts a reply loop between LegalGate and another robot. It lived in
 * {@code CloudMailinIngestionService} until Resend needed it too.
 */
@Component
public class AutoResponderDetector {

    public boolean isAutoResponder(Map<String, Object> headers) {
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

    /** CloudMailin underscores header names, other providers keep the hyphens; match either. */
    public String headerValue(Map<String, Object> headers, String key) {
        if (headers == null) {
            return null;
        }
        Object value = headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && normalizeHeaderName(entry.getKey()).equals(key))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
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
