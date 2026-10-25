package io.github.jnesew.plainpoddroid.core.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackSleepTimerSourceContractTest {
    @Test
    public void serviceOwnsTransientMonotonicTimerAndReliableEpisodeBoundary()
            throws Exception {
        String source = readSource("playback", "PlaybackService.java");

        assertTrue(source.contains("SleepTimerState sleepTimerState"));
        assertTrue(source.contains("SystemClock.elapsedRealtime()"));
        assertTrue(source.contains("mainHandler.postDelayed(sleepTimerExpiry,"));
        assertTrue(source.contains("player.setPauseAtEndOfMediaItems("));
        assertTrue(source.contains(
                "Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM"));
        assertTrue(source.contains("player.pause();"));
        assertTrue(source.contains("cancelSleepTimerInternal(true);"));
        assertTrue(source.contains("mediaSession.getConnectedControllers()"));
        assertTrue(source.contains("if (controller.getUid() == Process.myUid())"));
        assertTrue(source.contains("mediaSession.sendCustomCommand("));
        assertFalse(source.contains("mediaSession.broadcastCustomCommand("));
        assertFalse(source.contains("AlarmManager"));
        assertFalse(source.contains("SharedPreferences.Editor"));
    }

    @Test
    public void onlyApplicationUidReceivesAndExecutesTimerCommands() throws Exception {
        String source = readSource("playback", "PlaybackSessionCallback.java");

        assertTrue(source.contains("if (controller.getUid() == applicationUid)"));
        assertTrue(source.contains(".add(PlaybackSleepTimerCommands.armCommand())"));
        assertTrue(source.contains(".add(PlaybackSleepTimerCommands.cancelCommand())"));
        assertTrue(source.contains(".add(PlaybackSleepTimerCommands.getStatusCommand())"));
        assertTrue(source.contains("if (controller.getUid() != applicationUid)"));
        assertTrue(source.contains("SessionError.ERROR_PERMISSION_DENIED"));
        assertTrue(source.contains("SessionError.ERROR_BAD_VALUE"));
    }

    @Test
    public void activityUsesAllowlistedOptionsAndExplicitActions() throws Exception {
        String source = readSource("ui", "PlaybackPanel.java");

        assertTrue(source.contains("SleepTimerOption.values()"));
        assertTrue(source.contains("playbackSleepTimerSetButton.setOnClickListener("));
        assertTrue(source.contains("playbackSleepTimerCancelButton.setOnClickListener("));
        assertTrue(source.contains("PlaybackSleepTimerCommands.armArguments(option)"));
        assertTrue(source.contains("controller.sendCustomCommand(command, args)"));
        assertTrue(source.contains("requestSleepTimerStatus();"));
        assertFalse(source.contains("AlarmManager"));
    }

    @Test
    public void commandCodecUsesNamespacedBoundedIntegerValues() throws Exception {
        String source = readSource("playback", "PlaybackSleepTimerCommands.java");

        assertTrue(source.contains(
                "io.github.jnesew.plainpoddroid.playback.sleep_timer."));
        assertTrue(source.contains("SleepTimerOption.fromCommandValue("));
        assertTrue(source.contains("extras.containsKey(EXTRA_OPTION)"));
        assertTrue(source.contains("remainingMillis > option.getDurationMillis()"));
        assertFalse(source.contains("Serializable"));
        assertFalse(source.contains("Parcelable"));
    }

    private static Path sourceRoot() throws IOException {
        Path rootRelative = Path.of("app", "src", "main", "java", "io", "github",
                "jnesew", "plainpoddroid");
        if (Files.isDirectory(rootRelative)) {
            return rootRelative;
        }
        Path appRelative = Path.of("src", "main", "java", "io", "github",
                "jnesew", "plainpoddroid");
        if (Files.isDirectory(appRelative)) {
            return appRelative;
        }
        throw new IOException("Production source root was unavailable to the test.");
    }

    private static String readSource(String child, String fileName) throws IOException {
        Path path = child == null
                ? sourceRoot().resolve(fileName)
                : sourceRoot().resolve(child).resolve(fileName);
        return SourceFileReader.readString(path, StandardCharsets.UTF_8);
    }
}
