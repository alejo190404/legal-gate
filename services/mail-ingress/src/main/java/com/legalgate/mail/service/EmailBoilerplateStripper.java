package com.legalgate.mail.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes Email Boilerplate — the confidentiality notices, legal disclaimers and antivirus stamps
 * a corporate mail gateway appends to everything its users send. See ADR-0001.
 *
 * <p>The trade-off is deliberately one-sided: boilerplate left in place costs the classifier some
 * noise, while a wrong removal silently destroys a potential client's own words and there is no
 * raw body kept anywhere to notice it against. So detection is a fixed phrase list rather than a
 * heuristic, and every ambiguous shape falls back to the untouched original.
 */
@Component
public class EmailBoilerplateStripper {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailBoilerplateStripper.class);

    /**
     * Fixed strings gateways open their trailer with, folded to lower case and stripped of
     * accents. Every entry must be a phrase no human plausibly starts a line of a consultation
     * with — a false positive here is a lost matter.
     *
     * <p>Deliberately absent: the {@code "-- "} signature delimiter, because a signature carries
     * the name, phone and identity document the firm often asked for; and a bare
     * {@code "disclaimer"}, because a client asking about a contract's disclaimer is a real
     * consultation.
     */
    private static final List<String> MARKERS = List.of(
            "aviso legal",
            "aviso de confidencialidad",
            "este mensaje es confidencial",
            "este correo electronico es confidencial",
            "el presente correo electronico",
            "la informacion contenida en este",
            "confidentiality notice",
            "this message is confidential",
            "this e-mail has been scanned",
            "this email has been scanned");

    public String strip(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        int cut = firstMarkerOffset(plain);
        // Nothing matched, or the trailer is the very first thing in the message — the latter is a
        // forwarded disclaimer-laden thread with the answer below it, not a trailer to cut.
        if (cut <= 0) {
            return plain;
        }
        String kept = plain.substring(0, cut).trim();
        if (kept.isBlank()) {
            return plain;
        }
        LOGGER.info("Removed {} characters of email boilerplate: {}",
                plain.length() - cut, plain.substring(cut).trim());
        return kept;
    }

    /**
     * Offset of the first line that opens with a marker, or -1. Trailers run to the end, so the
     * caller keeps everything above. Offset 0 and -1 are the same answer to it: leave the body be.
     */
    private int firstMarkerOffset(String plain) {
        int lineStart = 0;
        while (lineStart <= plain.length()) {
            int lineEnd = plain.indexOf('\n', lineStart);
            String line = plain.substring(lineStart, lineEnd < 0 ? plain.length() : lineEnd);
            String folded = fold(line.strip());
            if (MARKERS.stream().anyMatch(folded::startsWith)) {
                return lineStart;
            }
            if (lineEnd < 0) {
                return -1;
            }
            lineStart = lineEnd + 1;
        }
        return -1;
    }

    /** Gateways disagree on case and on tildes — one observed in the wild drops them on purpose. */
    private static String fold(String line) {
        return Normalizer.normalize(line, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
