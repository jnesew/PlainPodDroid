package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PlaybackSpeedSettingTest {
    @Test
    public void defaultIsNormalSpeed() {
        assertEquals(100, PlaybackSpeedSetting.DEFAULT.getPercent());
        assertEquals(1.0f, PlaybackSpeedSetting.DEFAULT.getMultiplier(), 0.0f);
    }

    @Test
    public void everyExposedSpeedIsBoundedAndRoundTrips() {
        for (PlaybackSpeedSetting speed : PlaybackSpeedSetting.values()) {
            assertTrue(speed.getPercent() >= SecurityLimits.MIN_PLAYBACK_SPEED_PERCENT);
            assertTrue(speed.getPercent() <= SecurityLimits.MAX_PLAYBACK_SPEED_PERCENT);
            assertEquals(speed.getPercent() / 100.0f, speed.getMultiplier(), 0.0f);
            assertEquals(speed, PlaybackSpeedSetting.fromPercent(speed.getPercent()));
        }
    }

    @Test
    public void arbitraryOrOutOfRangeSpeedsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackSpeedSetting.fromPercent(49));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackSpeedSetting.fromPercent(110));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackSpeedSetting.fromPercent(301));
    }
}
