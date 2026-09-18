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
        String fromEmail = fromEmail(notification);
        // A potential client wrote to a firm and hears back from that firm. Lawyers and firm staff
        // are the LegalGate customer, so their mail keeps LegalGate branding unchanged.
        if (!"CLIENT".equals(notification.recipientRole())) {
            return intakeProperties.notificationsFromName() + " <" + fromEmail + ">";
        }
        return quoted(firmNameResolver.firmNameFor(notification.tenantId())) + " <" + fromEmail + ">";
    }

    /**
     * A From address earns its sender reputation by staying the same, and a unique high-entropy
     * local part per message is the shape of snowshoe spam, so the Reply Token tag is stripped
     * here and travels in Reply-To instead.
     */
    String fromEmail(NotificationOutboxItem notification) {
        return isBlank(notification.fromEmail())
                ? intakeProperties.notificationsFromEmail()
                : untagged(notification.fromEmail().trim());
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

    /**
     * The headers that belong to the conversation with the potential client: where a reply goes,
     * and the Consultation Thread anchored flat at the client's first email. See ADR 0004.
     */
    Map<String, String> clientHeaders(NotificationOutboxItem notification, String threadAnchor) {
        // Staff mail is not part of the client's conversation, and carries no Reply Token.
        if (!"CLIENT".equals(notification.recipientRole())) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        // Reply routing is not optional the way threading is, so it does not wait on an anchor.
        String tagged = isBlank(notification.fromEmail()) ? null : notification.fromEmail().trim();
        if (tagged != null && !tagged.equals(fromEmail(notification))) {
            headers.put("Reply-To", tagged);
        }
        if (!isBlank(threadAnchor)) {
            String anchor = angleBracketed(threadAnchor);
            if (!"<>".equals(anchor)) {
                headers.put("In-Reply-To", anchor);
                headers.put("References", anchor);
            }
        }
        return headers;
    }

    /** Strips the Reply Token tag. It is appended last, so a tenant address with its own `+` keeps it. */
    private static String untagged(String address) {
        int at = address.lastIndexOf('@');
        int plus = address.lastIndexOf('+');
        return plus < 0 || plus > at ? address : address.substring(0, plus) + address.substring(at);
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
