package io.github.jnesew.plainpoddroid.core.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public final class PlaybackSpeedSessionSourceContractTest {
    @Test
    public void externalControllersCannotInjectArbitrarySpeedOrPitch() throws Exception {
        String source = SourceFileReader.readString(callbackSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains("if (controller.getUid() != applicationUid)"));
        assertTrue(source.contains(".remove(Player.COMMAND_SET_SPEED_AND_PITCH)"));
    }

    private static Path callbackSourcePath() throws IOException {
        Path rootRelative = Path.of(
                "app", "src", "main", "java", "io", "github", "jnesew",
                "plainpoddroid", "playback", "PlaybackSessionCallback.java");
        if (Files.isRegularFile(rootRelative)) {
            return rootRelative;
        }
        Path appRelative = Path.of(
                "src", "main", "java", "io", "github", "jnesew",
                "plainpoddroid", "playback", "PlaybackSessionCallback.java");
        if (Files.isRegularFile(appRelative)) {
            return appRelative;
        }
        throw new IOException("Playback session callback source was unavailable to the test.");
    }
}
