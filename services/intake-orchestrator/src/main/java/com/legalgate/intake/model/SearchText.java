package com.legalgate.intake.model;

import java.text.Normalizer;
import java.util.Locale;

/** Text as search compares it: lowercase, accents dropped. In-memory twin of Postgres {@code unaccent(lower(...))}. */
public final class SearchText {

    private SearchText() {
    }

    public static String of(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
