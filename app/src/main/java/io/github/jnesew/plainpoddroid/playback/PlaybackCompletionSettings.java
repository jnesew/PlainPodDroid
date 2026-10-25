package io.github.jnesew.plainpoddroid.playback;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.jnesew.plainpoddroid.core.playback.CompletionMode;

import java.util.Objects;

public final class PlaybackCompletionSettings {
    private static final String PREFERENCES = "playback_completion";
    private static final String KEY_MODE = "completion_mode";
    private final SharedPreferences preferences;

    public PlaybackCompletionSettings(Context context) {
        preferences = Objects.requireNonNull(context, "context")
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public CompletionMode getMode() {
        try {
            return CompletionMode.fromStoredValue(
                    preferences.getString(KEY_MODE, CompletionMode.NEAR_END.storedValue()));
        } catch (ClassCastException exception) {
            return CompletionMode.NEAR_END;
        }
    }

    public boolean setMode(CompletionMode mode) {
        return preferences.edit().putString(KEY_MODE,
                Objects.requireNonNull(mode, "mode").storedValue()).commit();
    }
}
