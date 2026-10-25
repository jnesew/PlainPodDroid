package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class PlaybackDisplayMetadataTest {
    @Test
    public void substitutesAnInertUntitledLabel() {
        assertEquals("Untitled episode", PlaybackDisplayMetadata.notificationTitle(""));
        assertEquals("Untitled episode", PlaybackDisplayMetadata.notificationTitle(null));
    }

    @Test
    public void boundsNotificationTextWithoutSplittingSurrogates() {
        String title = "a".repeat(SecurityLimits.MAX_NOTIFICATION_TITLE_CHARS - 1)
                + "\ud83d\ude80tail";
        String bounded = PlaybackDisplayMetadata.notificationTitle(title);

        assertEquals(SecurityLimits.MAX_NOTIFICATION_TITLE_CHARS - 1, bounded.length());
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)));
        assertEquals(
                SecurityLimits.MAX_NOTIFICATION_SUBTITLE_CHARS,
                PlaybackDisplayMetadata.notificationSubtitle(
                        "b".repeat(SecurityLimits.MAX_NOTIFICATION_SUBTITLE_CHARS + 20))
                        .length());
    }
}
