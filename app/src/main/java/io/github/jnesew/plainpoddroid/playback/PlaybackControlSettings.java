package io.github.jnesew.plainpoddroid.playback;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.jnesew.plainpoddroid.core.playback.PlaybackSkipInterval;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting;

import java.util.Objects;

/** App-private, allowlisted playback-control preferences shared by UI and service. */
public final class PlaybackControlSettings {
    private static final String PREFERENCES_NAME = "playback_controls";
    private static final String KEY_SKIP_BACK_SECONDS = "skip_back_seconds";
    private static final String KEY_SKIP_FORWARD_SECONDS = "skip_forward_seconds";
    private static final String KEY_SPEED_PERCENT = "speed_percent";
    private static final String KEY_PODCAST_SETTINGS_REVISION = "podcast_settings_revision";

    private final SharedPreferences preferences;

    public PlaybackControlSettings(Context context) {
        Objects.requireNonNull(context, "context");
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
    }

    public PlaybackSkipInterval getSkipBackInterval() {
        return getInterval(KEY_SKIP_BACK_SECONDS, PlaybackSkipInterval.DEFAULT_BACK);
    }

    public PlaybackSkipInterval getSkipForwardInterval() {
        return getInterval(KEY_SKIP_FORWARD_SECONDS, PlaybackSkipInterval.DEFAULT_FORWARD);
    }

    public PlaybackSpeedSetting getPlaybackSpeed() {
        final int percent;
        try {
            percent = preferences.getInt(
                    KEY_SPEED_PERCENT,
                    PlaybackSpeedSetting.DEFAULT.getPercent());
        } catch (ClassCastException exception) {
            return PlaybackSpeedSetting.DEFAULT;
        }
        try {
            return PlaybackSpeedSetting.fromPercent(percent);
        } catch (IllegalArgumentException exception) {
            return PlaybackSpeedSetting.DEFAULT;
        }
    }

    public boolean setSkipBackInterval(PlaybackSkipInterval interval) {
        return setInterval(KEY_SKIP_BACK_SECONDS, interval);
    }

    public boolean setSkipForwardInterval(PlaybackSkipInterval interval) {
        return setInterval(KEY_SKIP_FORWARD_SECONDS, interval);
    }

    public boolean setPlaybackSpeed(PlaybackSpeedSetting speed) {
        Objects.requireNonNull(speed, "speed");
        return preferences.edit()
                .putInt(KEY_SPEED_PERCENT, speed.getPercent())
                .commit();
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(
                Objects.requireNonNull(listener, "listener"));
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(
                Objects.requireNonNull(listener, "listener"));
    }

    public static boolean isSkipPreference(String key) {
        return KEY_SKIP_BACK_SECONDS.equals(key) || KEY_SKIP_FORWARD_SECONDS.equals(key);
    }

    public static boolean isSpeedPreference(String key) {
        return KEY_SPEED_PERCENT.equals(key)||KEY_PODCAST_SETTINGS_REVISION.equals(key);
    }

    public boolean notifyPodcastSettingsChanged() {
        long previous;
        try{previous=preferences.getLong(KEY_PODCAST_SETTINGS_REVISION,0L);}catch(ClassCastException e){previous=0L;}
        return preferences.edit().putLong(KEY_PODCAST_SETTINGS_REVISION,previous==Long.MAX_VALUE?0L:previous+1L).commit();
    }

    private PlaybackSkipInterval getInterval(
            String key,
            PlaybackSkipInterval fallback) {
        final int seconds;
        try {
            seconds = preferences.getInt(key, fallback.getSeconds());
        } catch (ClassCastException exception) {
            return fallback;
        }
        try {
            return PlaybackSkipInterval.fromSeconds(seconds);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private boolean setInterval(String key, PlaybackSkipInterval interval) {
        Objects.requireNonNull(interval, "interval");
        return preferences.edit()
                .putInt(key, interval.getSeconds())
                .commit();
    }
}
