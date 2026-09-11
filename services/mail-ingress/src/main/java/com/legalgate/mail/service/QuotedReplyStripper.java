package com.legalgate.mail.service;

import java.text.Normalizer;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes Quoted History — the copy of the earlier exchange a mail client stacks under a reply.
 * See ADR-0002.
 *
 * <p>CloudMailin does this server-side and offers the result as {@code reply_plain}; Resend has no
 * equivalent, so the job moves here and covers every provider. The trade-off is the one ADR-0001
 * already took for Email Boilerplate: quoted history left in place costs the classifier some
 * noise, while a wrong cut silently destroys a potential client's own words and no raw body is
 * kept anywhere to notice it against. So detection is a fixed marker list rather than a
 * heuristic, and every ambiguous shape falls back to the untouched original.
 */
@Component
public class QuotedReplyStripper {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuotedReplyStripper.class);

    public String strip(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        int cut = firstMarkerOffset(plain);
        // Nothing matched, or the quote opens the message — the latter is a forwarded thread
        // whose content is below the attribution, not a trailer hanging off the client's reply.
        if (cut <= 0) {
            return plain;
        }
        String kept = plain.substring(0, cut).trim();
        if (kept.isBlank()) {
            return plain;
        }
        // The removed text is the earlier exchange between the firm and the potential client, so
        // only its size is recorded — the same rule the boilerplate stripper follows.
        LOGGER.info("Removed {} characters of quoted history", plain.length() - cut);
        return kept;
    }

    /**
     * Offset of the line that opens the quoted history, or -1. Quoted history runs to the end of
     * the body, so the caller keeps everything above it. Offset 0 and -1 are the same answer to
     * it: leave the body be.
     */
    private int firstMarkerOffset(String plain) {
        String[] lines = plain.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            if (opensQuotedHistory(lines, i)) {
                return offset;
            }
            offset += lines[i].length() + 1;
        }
        return -1;
    }

    private static boolean opensQuotedHistory(String[] lines, int index) {
        String line = fold(lines[index].strip());
        return isAttribution(line)
                || line.startsWith("-----mensaje original-----")
                || line.startsWith("-----original message-----")
                || line.startsWith(">")
                || opensHeaderBlock(lines, index, line);
    }

    /**
     * Gmail and Apple Mail introduce the quote with a one-line attribution wrapping a date and the
     * sender. The date is not fixed text, so the line is matched by what brackets it.
     *
     * <p>ponytail: a single line only. Gmail wraps this line when the sender's display name and
     * address are long enough, and a wrapped attribution is not detected — the header-block and
     * angle-quote markers below usually catch that mail anyway. Match across a joined pair of
     * lines if one is ever observed slipping through.
     */
    private static boolean isAttribution(String line) {
        return (line.startsWith("el ") && line.endsWith("escribio:"))
                || (line.startsWith("on ") && line.endsWith("wrote:"));
    }

    /**
     * Outlook quotes with a header block rather than an attribution line. "De:" and "From:" are
     * also how people write, so the block counts only when one of its sibling headers follows it
     * — "De: mi arrendador recibí una carta" is a client describing their matter.
     */
    private static boolean opensHeaderBlock(String[] lines, int index, String line) {
        if (!line.startsWith("de:") && !line.startsWith("from:")) {
            return false;
        }
        for (int i = index + 1; i < Math.min(index + 4, lines.length); i++) {
            String sibling = fold(lines[i].strip());
            if (sibling.startsWith("enviado el:") || sibling.startsWith("para:")
                    || sibling.startsWith("sent:") || sibling.startsWith("to:")) {
                return true;
            }
        }
        return false;
    }

    private static String fold(String line) {
        return Normalizer.normalize(line, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
