package com.legalgate.intake.service;

import com.legalgate.intake.config.IntakeProperties;
import com.legalgate.intake.model.NotificationOutboxItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The parts of an outbound message that read the same whichever provider carries it; see ADR 0004. */
@Component
class OutboundMailEnvelope {

    private final IntakeProperties intakeProperties;
    private final FirmNameResolver firmNameResolver;

    OutboundMailEnvelope(IntakeProperties intakeProperties, FirmNameResolver firmNameResolver) {
        this.intakeProperties = intakeProperties;
        this.firmNameResolver = firmNameResolver;
    }

    String fromHeader(NotificationOutboxItem notification) {
        // Diagnostics messages carry a token-bearing From address so the client's reply lands
        // back on the right consultation; everything else sends from the shared agenda address.
        String fromEmail = fromEmail(notification);
        // A potential client wrote to a firm and hears back from that firm. Lawyers and firm staff
        // are the LegalGate customer, so their mail keeps LegalGate branding unchanged.
        if (!"CLIENT".equals(notification.recipientRole())) {
            return intakeProperties.notificationsFromName() + " <" + fromEmail + ">";
        }
        return quoted(firmNameResolver.firmNameFor(notification.tenantId())) + " <" + fromEmail + ">";
    }

    String fromEmail(NotificationOutboxItem notification) {
        return isBlank(notification.fromEmail())
                ? intakeProperties.notificationsFromEmail()
                : notification.fromEmail().trim();
    }

    String senderDomain(NotificationOutboxItem notification) {
        String address = fromEmail(notification);
        int at = address.lastIndexOf('@');
        return at < 0 ? intakeProperties.emailDomain() : address.substring(at + 1);
    }

    /**
     * Inbound Message-IDs are stored as providers hand them over, with or without brackets. The value
     * comes from a potential client's email, so anything that could forge a header is stripped first.
     */
    String angleBracketed(String messageId) {
        String bare = messageId.replaceAll("[\\p{Cntrl}\\s<>]+", "").trim();
        return "<" + bare + ">";
    }

    /** The Consultation Thread is anchored flat at the potential client's first email; see ADR 0004. */
    Map<String, String> threadHeaders(NotificationOutboxItem notification, String threadAnchor) {
        // The thread is the conversation with the potential client; staff mail is not part of it.
        if (!"CLIENT".equals(notification.recipientRole()) || isBlank(threadAnchor)) {
            return Map.of();
        }
        String anchor = angleBracketed(threadAnchor);
        if ("<>".equals(anchor)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("In-Reply-To", anchor);
        headers.put("References", anchor);
        return headers;
    }

    /** Firm names are tenant-supplied, so they are quoted and stripped of anything that could forge a header. */
    private String quoted(String displayName) {
        String sanitized = displayName.replaceAll("\\p{Cntrl}+", " ").trim()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "\"" + sanitized + "\"";
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
