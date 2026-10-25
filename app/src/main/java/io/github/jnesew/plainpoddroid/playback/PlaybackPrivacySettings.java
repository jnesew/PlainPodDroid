package io.github.jnesew.plainpoddroid.playback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import io.github.jnesew.plainpoddroid.core.playback.PlaybackPrivacyPolicy;

import java.util.Objects;

public final class PlaybackPrivacySettings implements PlaybackPrivacyPolicy {
    private static final String PREFERENCES_NAME = "playback_privacy";
    private static final String KEY_NEVER_STREAM_AUDIO = "never_stream_audio";
    private static final String KEY_DOWNLOAD_BEFORE_PLAYING = "download_before_playing";

    private final SharedPreferences preferences;

    public PlaybackPrivacySettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
    }

    @Override
    public boolean isNeverStreamAudioEnabled() {
        return isOfflineModeEnabled();
    }

    /** The existing preference now blocks all app network traffic. */
    public boolean isOfflineModeEnabled() {
        try {
            return preferences.getBoolean(KEY_NEVER_STREAM_AUDIO, false);
        } catch (ClassCastException exception) {
            // A malformed app-private value must not silently enable streaming.
            return true;
        }
    }

    @Override public boolean isDownloadBeforePlayingEnabled() {
        try { return preferences.getBoolean(KEY_DOWNLOAD_BEFORE_PLAYING, false); }
        catch (ClassCastException exception) { return true; }
    }

    @SuppressLint("ApplySharedPref")
    public boolean setDownloadBeforePlayingEnabled(boolean enabled) {
        boolean wasEnabled = isDownloadBeforePlayingEnabled();
        boolean committed = preferences.edit().putBoolean(KEY_DOWNLOAD_BEFORE_PLAYING, enabled).commit();
        if (!committed && wasEnabled && !enabled)
            preferences.edit().putBoolean(KEY_DOWNLOAD_BEFORE_PLAYING, true).apply();
        return committed;
    }

    public static boolean isDownloadBeforePlayingPreference(String key) {
        return KEY_DOWNLOAD_BEFORE_PLAYING.equals(key);
    }

    @SuppressLint("ApplySharedPref")
    public boolean setNeverStreamAudioEnabled(boolean enabled) {
        // Enabling a security boundary must be durably committed before the UI confirms it.
        boolean wasEnabled = isNeverStreamAudioEnabled();
        boolean committed = preferences.edit()
                .putBoolean(KEY_NEVER_STREAM_AUDIO, enabled)
                .commit();
        if (!committed && wasEnabled && !enabled) {
            // SharedPreferences changes memory before reporting a disk failure; restore safe state.
            preferences.edit().putBoolean(KEY_NEVER_STREAM_AUDIO, true).apply();
        }
        return committed;
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(
                Objects.requireNonNull(listener, "listener"));
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(
                Objects.requireNonNull(listener, "listener"));
    }

    public static boolean isNeverStreamPreference(String key) {
        return KEY_NEVER_STREAM_AUDIO.equals(key);
    }
}
