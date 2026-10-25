package io.github.jnesew.plainpoddroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DisplayThemeTest {
    @Test
    public void stableStoredValuesAndInvalidPreferenceFallback() {
        assertEquals(DisplayTheme.SYSTEM, DisplayTheme.fromStoredValue(null));
        assertEquals(DisplayTheme.SYSTEM, DisplayTheme.fromStoredValue("unknown"));
        for (DisplayTheme theme : DisplayTheme.values()) {
            assertEquals(theme, DisplayTheme.fromStoredValue(theme.storedValue()));
        }
    }
}
