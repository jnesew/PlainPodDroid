package io.github.jnesew.plainpoddroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

final class AutomaticDownloadSettings {
    private static final String PREFERENCES = "automatic_downloads";
    private static final String KEY_UNMETERED_ONLY = "unmetered_only";
    private final SharedPreferences preferences;

    AutomaticDownloadSettings(Context context) {
        preferences = Objects.requireNonNull(context, "context")
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    boolean isUnmeteredOnly() {
        try {
            return preferences.getBoolean(KEY_UNMETERED_ONLY, false);
        } catch (ClassCastException exception) {
            return true;
        }
    }

    boolean setUnmeteredOnly(boolean value) {
        return preferences.edit().putBoolean(KEY_UNMETERED_ONLY, value).commit();
    }
}
