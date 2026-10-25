package io.github.jnesew.plainpoddroid.core.catalog;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parses feed publication dates without guessing a timezone for ambiguous input. */
public final class CatalogPublishTime {
    private CatalogPublishTime() {
        throw new AssertionError("No instances");
    }

    /** Zero means missing, malformed, or outside the representable positive epoch range. */
    public static long epochMillis(String published) {
        if (published == null || published.isBlank() || published.length() > 2048) return 0L;
        String value = published.trim();
        try {
            return positive(Instant.parse(value));
        } catch (DateTimeParseException | ArithmeticException ignored) {
            // Atom also permits explicit offsets instead of a Z suffix.
        }
        try {
            return positive(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException | ArithmeticException ignored) {
            // RSS uses RFC 1123 dates.
        }
        try {
            return positive(ZonedDateTime.parse(value,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return 0L;
        }
    }

    private static long positive(Instant instant) {
        long millis = instant.toEpochMilli();
        return millis > 0L ? millis : 0L;
    }
}
