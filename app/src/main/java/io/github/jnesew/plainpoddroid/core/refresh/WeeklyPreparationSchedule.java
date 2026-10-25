package io.github.jnesew.plainpoddroid.core.refresh;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** One local-time occurrence per selected day; Monday is bit zero. */
public record WeeklyPreparationSchedule(int minuteOfDay, int days) {
    public static final long MAX_LATENESS_MILLIS = 2L * 60 * 60 * 1000;
    public WeeklyPreparationSchedule {
        if (minuteOfDay < 0 || minuteOfDay >= 24 * 60 || days < 1 || days > 127)
            throw new IllegalArgumentException("Choose a valid time and at least one day.");
    }

    public long nextAfter(long nowMillis, ZoneId zone) {
        if (nowMillis < 1L) throw new IllegalArgumentException("Invalid schedule time.");
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(Objects.requireNonNull(zone)).toLocalDate();
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = today.plusDays(offset);
            if (!includes(date)) continue;
            long candidate = occurrence(date, zone).toInstant().toEpochMilli();
            if (candidate > nowMillis) return candidate;
        }
        throw new IllegalStateException("No next preparation day.");
    }

    public boolean isOccurrence(long millis, ZoneId zone) {
        if (millis < 1L) return false;
        LocalDate date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        return includes(date) && occurrence(date, zone).toInstant().toEpochMilli() == millis;
    }

    public boolean mayStart(long due, long now, ZoneId zone) {
        return due > 0L && now >= due && now - due <= MAX_LATENESS_MILLIS && isOccurrence(due, zone);
    }

    public boolean keepPending(long due, long now, ZoneId zone) {
        return due > 0L && now > 0L && (due > now ? due <= nextAfter(now, zone)
                : now - due <= MAX_LATENESS_MILLIS) && isOccurrence(due, zone);
    }

    private boolean includes(LocalDate date) { return (days & (1 << (date.getDayOfWeek().getValue() - 1))) != 0; }
    private ZonedDateTime occurrence(LocalDate date, ZoneId zone) {
        // Gaps move forward with the clock; overlaps use the first occurrence only.
        return date.atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).atZone(zone)
                .withEarlierOffsetAtOverlap();
    }
}
