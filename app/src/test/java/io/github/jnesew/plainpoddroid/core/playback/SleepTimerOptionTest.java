package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SleepTimerOptionTest {
    @Test
    public void fixedDurationsAreBoundedAndRoundTrip() {
        for (SleepTimerOption option : SleepTimerOption.values()) {
            assertEquals(option, SleepTimerOption.fromCommandValue(option.getCommandValue()));
            if (option.isEndOfEpisode()) {
                continue;
            }
            assertTrue(option.getCommandValue() >= SecurityLimits.MIN_SLEEP_TIMER_MINUTES);
            assertTrue(option.getCommandValue() <= SecurityLimits.MAX_SLEEP_TIMER_MINUTES);
            assertEquals(option.getCommandValue() * 60L * 1_000L,
                    option.getDurationMillis());
        }
    }

    @Test
    public void endOfEpisodeIsExplicitlyNonDuration() {
        assertTrue(SleepTimerOption.END_OF_EPISODE.isEndOfEpisode());
        assertEquals(SleepTimerOption.END_OF_EPISODE_COMMAND_VALUE,
                SleepTimerOption.END_OF_EPISODE.getCommandValue());
        assertThrows(IllegalStateException.class,
                SleepTimerOption.END_OF_EPISODE::getDurationMillis);
        assertFalse(SleepTimerOption.MINUTES_5.isEndOfEpisode());
    }

    @Test
    public void arbitraryCommandValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SleepTimerOption.fromCommandValue(0));
        assertThrows(IllegalArgumentException.class,
                () -> SleepTimerOption.fromCommandValue(20));
        assertThrows(IllegalArgumentException.class,
                () -> SleepTimerOption.fromCommandValue(61));
    }
}
