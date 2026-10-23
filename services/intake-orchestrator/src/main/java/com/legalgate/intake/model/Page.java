package com.legalgate.intake.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * One page of a keyset-paged list. {@code nextCursor} is opaque to the caller and null on the
 * last page; passing it back returns the rows after the last one here.
 */
public record Page<T>(List<T> items, String nextCursor) {

    // NUL can't occur in Postgres text, so it can't occur in any sort key we read back from it.
    private static final String SEPARATOR = "\0";

    /** The page from up to {@code limit + 1} rows: the extra row only says another page exists. */
    public static <T> Page<T> of(List<T> rows, int limit, Function<T, List<String>> sortKey) {
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<T> items = rows.subList(0, limit);
        return new Page<>(List.copyOf(items), cursor(sortKey.apply(items.get(limit - 1))));
    }

    /** Encodes the last row's sort key, e.g. its name and id. */
    public static String cursor(List<String> sortKey) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join(SEPARATOR, sortKey).getBytes(StandardCharsets.UTF_8));
    }

    /** The sort key {@link #cursor} encoded, or 400 invalid_cursor when it has not {@code parts} parts. */
    public static List<String> sortKey(String cursor, int parts) {
        try {
            List<String> key = List.of(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split(SEPARATOR, -1));
            if (key.size() == parts) {
                return key;
            }
        } catch (IllegalArgumentException malformed) {
            // falls through to the 400
        }
        throw invalidCursor();
    }

    /** A sort key part read back as an instant, or 400 invalid_cursor. */
    public static Instant instant(String part) {
        try {
            return Instant.parse(part);
        } catch (DateTimeParseException malformed) {
            throw invalidCursor();
        }
    }

    /** A sort key part read back as a UUID, or 400 invalid_cursor. */
    public static UUID uuid(String part) {
        try {
            return UUID.fromString(part);
        } catch (IllegalArgumentException malformed) {
            throw invalidCursor();
        }
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_cursor");
    }
}
