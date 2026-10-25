package io.github.jnesew.plainpoddroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Objects;

final class DisplayThemeSettings {
    private static final String PREFERENCES = "display_theme";
    private static final String KEY_THEME = "theme";
    private final SharedPreferences preferences;

    DisplayThemeSettings(Context context) {
        preferences = Objects.requireNonNull(context, "context")
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    DisplayTheme getTheme() {
        try {
            return DisplayTheme.fromStoredValue(
                    preferences.getString(KEY_THEME, DisplayTheme.SYSTEM.storedValue()));
        } catch (ClassCastException exception) {
            return DisplayTheme.SYSTEM;
        }
    }

    boolean setTheme(DisplayTheme theme) {
        return preferences.edit().putString(KEY_THEME,
                Objects.requireNonNull(theme, "theme").storedValue()).commit();
    }

    static Context themedContext(Context base) {
        DisplayTheme selected = new DisplayThemeSettings(base).getTheme();
        if (selected == DisplayTheme.SYSTEM) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (selected == DisplayTheme.DARK
                        ? Configuration.UI_MODE_NIGHT_YES
                        : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(configuration);
    }
}
