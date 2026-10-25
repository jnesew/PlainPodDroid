package io.github.jnesew.plainpoddroid;

enum DisplayTheme {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    private final String storedValue;

    DisplayTheme(String storedValue) {
        this.storedValue = storedValue;
    }

    String storedValue() {
        return storedValue;
    }

    static DisplayTheme fromStoredValue(String value) {
        for (DisplayTheme theme : values()) {
            if (theme.storedValue.equals(value)) return theme;
        }
        return SYSTEM;
    }
}
