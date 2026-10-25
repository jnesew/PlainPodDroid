package io.github.jnesew.plainpoddroid.core.playback;

public enum CompletionMode {
    NEAR_END("near_end"),
    AT_RECORDED_END("at_recorded_end"),
    MANUAL_ONLY("manual_only");

    private final String storedValue;

    CompletionMode(String storedValue) {
        this.storedValue = storedValue;
    }

    public String storedValue() {
        return storedValue;
    }

    public static CompletionMode fromStoredValue(String value) {
        for (CompletionMode mode : values()) {
            if (mode.storedValue.equals(value)) return mode;
        }
        return NEAR_END;
    }
}
