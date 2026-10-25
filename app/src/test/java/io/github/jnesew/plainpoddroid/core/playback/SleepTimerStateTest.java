package io.github.jnesew.plainpoddroid.core.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SleepTimerStateTest {
    @Test
    public void inactiveStateHasNoOptionOrDeadline() {
        SleepTimerState state = SleepTimerState.inactive();

        assertFalse(state.isActive());
        assertFalse(state.isEndOfEpisode());
        assertEquals(SleepTimerState.NO_DEADLINE_MILLIS,
                state.getDeadlineElapsedRealtimeMillis());
        assertEquals(SleepTimerState.NO_DEADLINE_MILLIS, state.remainingMillis(0L));
        assertFalse(state.isExpired(Long.MAX_VALUE));
        assertThrows(IllegalStateException.class, state::getOption);
        assertSame(state, SleepTimerState.inactive());
    }

    @Test
    public void durationUsesMonotonicDeadlineAndExactBoundary() {
        SleepTimerState state = SleepTimerState.arm(SleepTimerOption.MINUTES_5, 1_000L);

        assertTrue(state.isActive());
        assertFalse(state.isEndOfEpisode());
        assertEquals(SleepTimerOption.MINUTES_5, state.getOption());
        assertEquals(301_000L, state.getDeadlineElapsedRealtimeMillis());
        assertEquals(300_000L, state.remainingMillis(1_000L));
        assertEquals(1L, state.remainingMillis(300_999L));
        assertFalse(state.isExpired(300_999L));
        assertEquals(0L, state.remainingMillis(301_000L));
        assertTrue(state.isExpired(301_000L));
        assertTrue(state.isExpired(400_000L));
    }

    @Test
    public void endOfEpisodeHasNoSyntheticDeadline() {
        SleepTimerState state = SleepTimerState.arm(
                SleepTimerOption.END_OF_EPISODE,
                50_000L);

        assertTrue(state.isActive());
        assertTrue(state.isEndOfEpisode());
        assertEquals(SleepTimerOption.END_OF_EPISODE, state.getOption());
        assertEquals(SleepTimerState.NO_DEADLINE_MILLIS,
                state.getDeadlineElapsedRealtimeMillis());
        assertEquals(SleepTimerState.NO_DEADLINE_MILLIS,
                state.remainingMillis(Long.MAX_VALUE));
        assertFalse(state.isExpired(Long.MAX_VALUE));
    }

    @Test
    public void invalidClockAndOverflowAreRejected() {
        assertThrows(NullPointerException.class,
                () -> SleepTimerState.arm(null, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> SleepTimerState.arm(SleepTimerOption.MINUTES_5, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> SleepTimerState.arm(SleepTimerOption.MINUTES_60, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> SleepTimerState.inactive().remainingMillis(-1L));
    }
}
