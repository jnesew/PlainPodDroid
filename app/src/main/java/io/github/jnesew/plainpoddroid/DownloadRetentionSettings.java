package io.github.jnesew.plainpoddroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

public final class DownloadRetentionSettings {
    private static final String PREFERENCES = "download_retention";
    private static final String KEY_AUTO_DELETE_COMPLETED = "auto_delete_completed";
    private final SharedPreferences preferences;
    private volatile boolean enabled;

    DownloadRetentionSettings(Context context) {
        this(Objects.requireNonNull(context, "context")
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE));
    }

    DownloadRetentionSettings(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        try {
            enabled = preferences.getBoolean(KEY_AUTO_DELETE_COMPLETED, false);
        } catch (ClassCastException exception) {
            enabled = false;
        }
    }

    public boolean isAutoDeleteCompleted() {
        return enabled;
    }

    public synchronized boolean setAutoDeleteCompleted(boolean requested) {
        // Every consumer uses the application's shared instance. Disabling takes
        // effect before disk I/O; enabling is exposed only after a successful save.
        if (!requested) enabled = false;
        try {
            if (preferences.edit().putBoolean(KEY_AUTO_DELETE_COMPLETED, requested).commit()) {
                enabled = requested;
                return true;
            }
        } catch (RuntimeException ignored) {
            // A failed store must not enable destructive behavior in this process.
        }
        try {
            // commit() may already have changed the SharedPreferences memory map.
            preferences.edit().putBoolean(KEY_AUTO_DELETE_COMPLETED, enabled).apply();
        } catch (RuntimeException ignored) {
            // The confirmed in-process flag remains authoritative even if rollback fails.
        }
        return false;
    }
}
