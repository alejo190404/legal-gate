package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * The plaintext part carries every sentence its card does (ADR-0009). A card test calls
 * {@link #assertPlaintextCarriesTheCard} on the renderer's output; a purely visual piece with words
 * in it (say, a date block laid out as "12 / marzo / 2026") is excused by passing its visible text.
 */
public final class CardParity {

    /** What a reader never sees in the card: head, comments (MSO blocks too), the hidden preheader. */
    private static final Pattern HIDDEN = Pattern.compile(
            "(?is)<head\\b.*?</head>|<!--.*?-->|<(div|span)\\b[^>]*display:\\s*none[^>]*>.*?</\\1>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(div|p|td|th|tr|table|br|li|ul|ol|h[1-6])\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern WORDLESS = Pattern.compile("[^\\p{L}\\p{N}]*");

    private CardParity() {
    }

    public static void assertPlaintextCarriesTheCard(RenderedEmail mail, String... visualOnly) {
        assertThat(mail.html()).as("the card").isNotBlank();
        String text = collapse(mail.text());
        List<String> excused = Arrays.stream(visualOnly).map(CardParity::collapse).toList();
        List<String> missing = visibleSentences(mail.html()).stream()
                .filter(sentence -> !text.contains(sentence) && !excused.contains(sentence))
                .toList();
        assertThat(missing).as("sentences in the card but not in its plaintext part:\n%s", mail.text()).isEmpty();
    }

    /** Each block's text, entities decoded and whitespace collapsed, split into sentences; tiles like ✓ drop out. */
    static List<String> visibleSentences(String html) {
        String blocks = TAG.matcher(BLOCK.matcher(HIDDEN.matcher(html).replaceAll(" ")).replaceAll("\u0000")).replaceAll("");
        return Arrays.stream(HtmlUtils.htmlUnescape(blocks).split("\u0000"))
                .map(CardParity::collapse)
                .flatMap(SENTENCE_END::splitAsStream)
                .filter(sentence -> !WORDLESS.matcher(sentence).matches())
                .toList();
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
