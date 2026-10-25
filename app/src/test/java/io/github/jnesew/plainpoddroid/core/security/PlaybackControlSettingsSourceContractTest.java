package io.github.jnesew.plainpoddroid.core.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackControlSettingsSourceContractTest {
    @Test
    public void settingsPersistOnlyAllowlistedSecondValues() throws Exception {
        String source = SourceFileReader.readString(settingsSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains("PlaybackSkipInterval.fromSeconds(seconds)"));
        assertTrue(source.contains(".putInt(key, interval.getSeconds())"));
        assertTrue(source.contains(".commit();"));
        assertFalse(source.contains("putString("));
        assertFalse(source.contains("http"));
        assertFalse(source.contains("Uri"));
    }

    @Test
    public void malformedValuesFallBackWithoutExpandingTheRange() throws Exception {
        String source = SourceFileReader.readString(settingsSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains("catch (ClassCastException exception)"));
        assertTrue(source.contains("catch (IllegalArgumentException exception)"));
        assertTrue(source.contains("return fallback;"));
        assertTrue(source.contains("PlaybackSpeedSetting.fromPercent(percent)"));
        assertTrue(source.contains("return PlaybackSpeedSetting.DEFAULT;"));
    }

    @Test
    public void playbackSpeedIsStoredAsAnAllowlistedIntegerPercentage() throws Exception {
        String source = SourceFileReader.readString(settingsSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains(".putInt(KEY_SPEED_PERCENT, speed.getPercent())"));
        assertTrue(source.contains("PlaybackSpeedSetting.fromPercent(percent)"));
        assertFalse(source.contains("putFloat("));
    }

    private static Path settingsSourcePath() throws IOException {
        Path rootRelative = Path.of(
                "app", "src", "main", "java", "io", "github", "jnesew",
                "plainpoddroid", "playback", "PlaybackControlSettings.java");
        if (Files.isRegularFile(rootRelative)) {
            return rootRelative;
        }
        Path appRelative = Path.of(
                "src", "main", "java", "io", "github", "jnesew",
                "plainpoddroid", "playback", "PlaybackControlSettings.java");
        if (Files.isRegularFile(appRelative)) {
            return appRelative;
        }
        throw new IOException("Playback control settings source was unavailable to the test.");
    }
}
