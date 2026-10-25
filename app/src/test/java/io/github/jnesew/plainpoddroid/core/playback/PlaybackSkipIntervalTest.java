package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PlaybackSkipIntervalTest {
    @Test
    public void defaultsMatchDocumentedTenAndThirtySeconds() {
        assertEquals(10, PlaybackSkipInterval.DEFAULT_BACK.getSeconds());
        assertEquals(30, PlaybackSkipInterval.DEFAULT_FORWARD.getSeconds());
    }

    @Test
    public void everyExposedIntervalIsBoundedAndConvertsExactly() {
        for (PlaybackSkipInterval interval : PlaybackSkipInterval.values()) {
            assertTrue(interval.getSeconds() >= SecurityLimits.MIN_PLAYBACK_SKIP_SECONDS);
            assertTrue(interval.getSeconds() <= SecurityLimits.MAX_PLAYBACK_SKIP_SECONDS);
            assertEquals(interval.getSeconds() * 1_000L, interval.getMillis());
            assertEquals(interval, PlaybackSkipInterval.fromSeconds(interval.getSeconds()));
        }
    }

    @Test
    public void arbitraryOrOutOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackSkipInterval.fromSeconds(0));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackSkipInterval.fromSeconds(20));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackSkipInterval.fromSeconds(61));
    }
}
