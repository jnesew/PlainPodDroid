package io.github.jnesew.plainpoddroid;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.jnesew.plainpoddroid.core.refresh.RefreshInterval;

import java.util.Objects;

final class AutomaticRefreshSettings {
    private static final String PREFERENCES = "automatic_refresh";
    private static final String KEY_INTERVAL_HOURS = "interval_hours";
    private static final String KEY_UNMETERED_ONLY = "unmetered_only";

    private final SharedPreferences preferences;

    AutomaticRefreshSettings(Context context) {
        Objects.requireNonNull(context, "context");
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    RefreshInterval getInterval() {
        if (!preferences.contains(KEY_INTERVAL_HOURS)) {
            return RefreshInterval.DEFAULT;
        }
        final int hours;
        try {
            hours = preferences.getInt(KEY_INTERVAL_HOURS, RefreshInterval.DEFAULT.getHours());
        } catch (ClassCastException exception) {
            return RefreshInterval.OFF;
        }
        try {
            return RefreshInterval.fromHours(hours);
        } catch (IllegalArgumentException exception) {
            return RefreshInterval.OFF;
        }
    }

    boolean setInterval(RefreshInterval interval) {
        Objects.requireNonNull(interval, "interval");
        return preferences.edit()
                .putInt(KEY_INTERVAL_HOURS, interval.getHours())
                .commit();
    }

    boolean isUnmeteredOnly() {
        try {
            return preferences.getBoolean(KEY_UNMETERED_ONLY, false);
        } catch (ClassCastException exception) {
            return true;
        }
    }

    boolean setUnmeteredOnly(boolean unmeteredOnly) {
        return preferences.edit()
                .putBoolean(KEY_UNMETERED_ONLY, unmeteredOnly)
                .commit();
    }
}
