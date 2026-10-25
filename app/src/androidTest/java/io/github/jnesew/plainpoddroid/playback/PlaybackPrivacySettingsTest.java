package io.github.jnesew.plainpoddroid.playback;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.AndroidTestCase;

@SuppressWarnings("deprecation")
public final class PlaybackPrivacySettingsTest extends AndroidTestCase {
    public void testOfflineAndDownloadBeforePlayingPersistIndependently() {
        String prefix = "playback-policy-test-" + System.nanoTime();
        Context isolated = new ContextWrapper(getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
        try {
            PlaybackPrivacySettings settings = new PlaybackPrivacySettings(isolated);
            assertFalse(settings.isOfflineModeEnabled()); assertFalse(settings.isDownloadBeforePlayingEnabled());
            assertTrue(settings.setDownloadBeforePlayingEnabled(true));
            assertFalse(settings.isOfflineModeEnabled());
            assertTrue(new PlaybackPrivacySettings(isolated).isDownloadBeforePlayingEnabled());
            assertTrue(settings.setNeverStreamAudioEnabled(true));
            assertTrue(settings.setDownloadBeforePlayingEnabled(false));
            assertTrue(settings.isOfflineModeEnabled());
            assertFalse(new PlaybackPrivacySettings(isolated).isDownloadBeforePlayingEnabled());
        } finally { isolated.getSharedPreferences("playback_privacy", 0).edit().clear().commit(); }
    }
}
