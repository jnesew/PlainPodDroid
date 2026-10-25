package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackProgressPolicyTest {
    @Test
    public void positionIsClampedToTrustedBoundsAndKnownDuration() {
        assertEquals(0L, PlaybackProgressPolicy.normalizePosition(-1L, 600_000L));
        assertEquals(600_000L,
                PlaybackProgressPolicy.normalizePosition(700_000L, 600_000L));
        assertEquals(SecurityLimits.MAX_MEDIA_DURATION_MILLIS,
                PlaybackProgressPolicy.normalizePosition(Long.MAX_VALUE, -1L));
    }

    @Test
    public void invalidDurationsRemainUnknown() {
        assertEquals(PlaybackProgressPolicy.UNKNOWN_DURATION_MILLIS,
                PlaybackProgressPolicy.normalizeDuration(-1L));
        assertEquals(PlaybackProgressPolicy.UNKNOWN_DURATION_MILLIS,
                PlaybackProgressPolicy.normalizeDuration(0L));
        assertEquals(PlaybackProgressPolicy.UNKNOWN_DURATION_MILLIS,
                PlaybackProgressPolicy.normalizeDuration(
                        SecurityLimits.MAX_MEDIA_DURATION_MILLIS + 1L));
        assertEquals(1L, PlaybackProgressPolicy.normalizeDuration(1L));
    }

    @Test
    public void minimumPlaybackTimeAppliesBeforeEitherCompletionThreshold() {
        assertFalse(PlaybackProgressPolicy.isCompleted(299_999L, 300_000L));
        assertFalse(PlaybackProgressPolicy.isCompleted(299_999L, 310_000L));
    }

    @Test
    public void exactPercentageAndRemainingTimeBoundariesComplete() {
        assertTrue(PlaybackProgressPolicy.isCompleted(380_000L, 400_000L));
        assertTrue(PlaybackProgressPolicy.isCompleted(480_000L, 600_000L));
    }

    @Test
    public void valuesJustOutsideBothThresholdsRemainIncomplete() {
        assertFalse(PlaybackProgressPolicy.isCompleted(479_999L, 600_000L));
        assertFalse(PlaybackProgressPolicy.isCompleted(3_799_999L, 4_000_000L));
    }

    @Test
    public void unknownOrUnboundedDurationNeverCompletes() {
        assertFalse(PlaybackProgressPolicy.isCompleted(600_000L, -1L));
        assertFalse(PlaybackProgressPolicy.isCompleted(
                SecurityLimits.MAX_MEDIA_DURATION_MILLIS,
                SecurityLimits.MAX_MEDIA_DURATION_MILLIS + 1L));
    }

    @Test
    public void explicitCompletionModesHaveDistinctBoundaries() {
        assertFalse(PlaybackProgressPolicy.isCompleted(380_000L, 400_000L,
                CompletionMode.AT_RECORDED_END));
        assertTrue(PlaybackProgressPolicy.isCompleted(400_000L, 400_000L,
                CompletionMode.AT_RECORDED_END));
        assertTrue(PlaybackProgressPolicy.isCompleted(60_000L, 60_000L,
                CompletionMode.AT_RECORDED_END));
        assertFalse(PlaybackProgressPolicy.isCompleted(400_000L, 400_000L,
                CompletionMode.MANUAL_ONLY));
        assertFalse(PlaybackProgressPolicy.isCompleted(400_000L, -1L,
                CompletionMode.AT_RECORDED_END));
        assertEquals(CompletionMode.NEAR_END, CompletionMode.fromStoredValue("invalid"));
    }
}
