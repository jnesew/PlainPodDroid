package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public final class PlaybackProgressUpdateTest {
    private static final String MEDIA_ID = "episode-" + "a".repeat(64);

    @Test
    public void updateCarriesOnlyValidatedCatalogState() {
        PlaybackProgressUpdate update = new PlaybackProgressUpdate(
                MEDIA_ID,
                123L,
                false,
                456L);

        assertEquals(MEDIA_ID, update.getMediaId());
        assertEquals(123L, update.getPositionMillis());
        assertFalse(update.shouldMarkPlayed());
        assertEquals(456L, update.getUpdatedAtMillis());
    }

    @Test
    public void invalidIdsPositionsAndTimesAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new PlaybackProgressUpdate(null, 0L, false, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackProgressUpdate("https://example.test/audio", 0L, false, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackProgressUpdate(MEDIA_ID, -1L, false, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackProgressUpdate(
                        MEDIA_ID,
                        SecurityLimits.MAX_MEDIA_DURATION_MILLIS + 1L,
                        false,
                        0L));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackProgressUpdate(MEDIA_ID, 0L, false, -1L));
    }
}
