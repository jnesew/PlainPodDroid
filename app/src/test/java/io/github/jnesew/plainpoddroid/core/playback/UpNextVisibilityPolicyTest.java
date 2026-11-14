package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UpNextVisibilityPolicyTest {
    @Test public void longEpisodeUsesTwoMinuteBoundaryAndSeekingBackHidesIt() {
        long hour = 3_600_000L;
        assertFalse(UpNextVisibilityPolicy.isNearEnd(0L,hour));
        assertFalse(UpNextVisibilityPolicy.isNearEnd(hour-120_001L,hour));
        assertTrue(UpNextVisibilityPolicy.isNearEnd(hour-120_000L,hour));
        assertTrue(UpNextVisibilityPolicy.isNearEnd(hour,hour));
        assertFalse(UpNextVisibilityPolicy.isNearEnd(1_800_000L,hour));
    }
    @Test public void shortEpisodeUsesFinalFifthInsteadOfShowingThroughout() {
        assertFalse(UpNextVisibilityPolicy.isNearEnd(0L,60_000L));
        assertFalse(UpNextVisibilityPolicy.isNearEnd(47_999L,60_000L));
        assertTrue(UpNextVisibilityPolicy.isNearEnd(48_000L,60_000L));
        assertFalse(UpNextVisibilityPolicy.isNearEnd(239_999L,300_000L));
        assertTrue(UpNextVisibilityPolicy.isNearEnd(240_000L,300_000L));
    }
    @Test public void missingAndOutOfBoundsDurationsStayQuiet() {
        for(long duration : new long[]{-9223372036854775807L,-1L,0L,Long.MAX_VALUE})
            assertFalse(UpNextVisibilityPolicy.isNearEnd(0L,duration));
        assertFalse(UpNextVisibilityPolicy.isNearEnd(-1L,60_000L));
        assertFalse(UpNextVisibilityPolicy.isNearEnd(Long.MAX_VALUE,60_000L));
    }
    @Test public void boundedPlayerOvershootCountsAsTheEndWithoutOverflow() {
        assertTrue(UpNextVisibilityPolicy.isNearEnd(61_000L,60_000L));
        assertTrue(UpNextVisibilityPolicy.isNearEnd(SecurityLimits.MAX_MEDIA_DURATION_MILLIS,
                SecurityLimits.MAX_MEDIA_DURATION_MILLIS));
        assertFalse(UpNextVisibilityPolicy.isNearEnd(Long.MIN_VALUE,60_000L));
    }
}
