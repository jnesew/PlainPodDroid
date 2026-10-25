package io.github.jnesew.plainpoddroid.core.refresh;

import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WeeklyPreparationScheduleTest {
    private static long time(String value) { return Instant.parse(value).toEpochMilli(); }
    @Test public void weekdaysSkipWeekendsAndAnAlreadyReachedTime() {
        var schedule = new WeeklyPreparationSchedule(360, 31);
        assertEquals(time("2026-10-23T06:00:00Z"), schedule.nextAfter(time("2026-10-23T05:59:00Z"), ZoneOffset.UTC));
        assertEquals(time("2026-10-26T06:00:00Z"), schedule.nextAfter(time("2026-10-23T06:00:00Z"), ZoneOffset.UTC));
        assertEquals(time("2026-10-26T06:00:00Z"), schedule.nextAfter(time("2026-10-25T12:00:00Z"), ZoneOffset.UTC));
    }
    @Test public void daylightSavingGapMovesForwardWithoutLosingTheDay() {
        var schedule = new WeeklyPreparationSchedule(150, 127);
        ZoneId zone = ZoneId.of("Europe/Berlin");
        long due = schedule.nextAfter(time("2026-03-28T23:00:00Z"), zone);
        assertEquals(time("2026-03-29T01:30:00Z"), due);
        assertTrue(schedule.isOccurrence(due, zone));
    }
    @Test public void daylightSavingOverlapDoesNotCreateASecondRun() {
        var schedule = new WeeklyPreparationSchedule(150, 127);
        ZoneId zone = ZoneId.of("Europe/Berlin");
        assertEquals(time("2026-10-25T00:30:00Z"), schedule.nextAfter(time("2026-10-24T22:00:00Z"), zone));
        assertEquals(time("2026-10-26T01:30:00Z"), schedule.nextAfter(time("2026-10-25T00:30:00Z"), zone));
        assertFalse(schedule.isOccurrence(time("2026-10-25T01:30:00Z"), zone));
    }
    @Test public void timezoneChangesAndClockRollbackRecomputeWallClockTime() {
        var schedule = new WeeklyPreparationSchedule(360, 127);
        long now = time("2026-10-20T02:00:00Z");
        assertEquals(time("2026-10-20T03:00:00Z"), schedule.nextAfter(now, ZoneId.of("Europe/Helsinki")));
        assertEquals(time("2026-10-20T06:00:00Z"), schedule.nextAfter(now, ZoneId.of("Atlantic/Reykjavik")));
        assertFalse(schedule.isOccurrence(time("2026-10-20T03:00:00Z"), ZoneId.of("Atlantic/Reykjavik")));
    }
    @Test public void lateRunsAreBoundedAndFutureOrAlteredOccurrencesCannotStart() {
        var schedule = new WeeklyPreparationSchedule(360, 31);
        long due = time("2026-10-20T06:00:00Z");
        assertFalse(schedule.mayStart(due, due - 1, ZoneOffset.UTC));
        assertTrue(schedule.mayStart(due, due + WeeklyPreparationSchedule.MAX_LATENESS_MILLIS, ZoneOffset.UTC));
        assertFalse(schedule.mayStart(due, due + WeeklyPreparationSchedule.MAX_LATENESS_MILLIS + 1, ZoneOffset.UTC));
        assertFalse(schedule.mayStart(due + 1, due + 2, ZoneOffset.UTC));
        assertTrue(schedule.keepPending(due, due - 1000, ZoneOffset.UTC));
        assertFalse(schedule.keepPending(due, due + WeeklyPreparationSchedule.MAX_LATENESS_MILLIS + 1, ZoneOffset.UTC));
    }
    @Test public void malformedSchedulesFailClosed() {
        for (int minute : new int[]{-1, 1440, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new WeeklyPreparationSchedule(minute, 31));
        for (int days : new int[]{-1, 0, 128, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new WeeklyPreparationSchedule(360, days));
    }
    @Test public void aClockRollbackCannotLeaveAJobSeveralOccurrencesInTheFuture() {
        var schedule = new WeeklyPreparationSchedule(360, 127);
        long now = time("2026-10-20T05:00:00Z");
        assertTrue(schedule.keepPending(time("2026-10-20T06:00:00Z"), now, ZoneOffset.UTC));
        assertFalse(schedule.keepPending(time("2026-10-21T06:00:00Z"), now, ZoneOffset.UTC));
        assertTrue(schedule.keepPending(time("2026-10-20T06:00:00Z"), time("2026-10-20T07:00:00Z"), ZoneOffset.UTC));
    }
}
