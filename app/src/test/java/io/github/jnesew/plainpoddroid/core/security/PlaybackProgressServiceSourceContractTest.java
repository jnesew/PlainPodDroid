package io.github.jnesew.plainpoddroid.core.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackProgressServiceSourceContractTest {
    @Test
    public void serviceUsesPrivateSerialWorkerAndUrlFreeCoordinator() throws Exception {
        String source = SourceFileReader.readString(serviceSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains("Executors.newSingleThreadExecutor("));
        assertTrue(source.contains("application.getPlaybackProgressStore()"));
        assertTrue(source.contains("item.mediaId,"));
        assertTrue(source.contains("currentPlayer.getCurrentPosition()"));
        assertTrue(source.contains("currentPlayer.getDuration()"));
        assertFalse(source.contains("item.localConfiguration.uri.toString(),\n"
                + "                        currentPlayer.getCurrentPosition()"));
        assertFalse(source.contains("getWritableDatabase()"));
        assertFalse(source.contains("getReadableDatabase()"));
    }

    @Test
    public void lifecycleAndEpisodeChangesForceLatestProgress() throws Exception {
        String source = SourceFileReader.readString(serviceSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains("public void onIsPlayingChanged(boolean isPlaying)"));
        assertTrue(source.contains("sampleCurrentProgress(true);"));
        assertTrue(source.contains("public void onPlaybackStateChanged(int playbackState)"));
        assertTrue(source.contains("playbackState == Player.STATE_IDLE"));
        assertTrue(source.contains("playbackState == Player.STATE_ENDED"));
        assertTrue(source.contains("public void onMediaItemTransition("));
        assertTrue(source.contains("public void onPositionDiscontinuity("));
        assertTrue(source.contains("forceDiscontinuedEpisode("));
        assertTrue(source.contains("progressCoordinator.close("));
        assertTrue(source.contains("progressExecutor.shutdown();"));
    }

    @Test
    public void progressDiagnosticsContainNoEpisodeMetadata() throws Exception {
        String source = SourceFileReader.readString(serviceSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains("Playback progress could not be saved"));
        assertFalse(source.contains("Playback progress could not be saved\", exception"));
        assertFalse(source.contains("progress update for \" +"));
        assertFalse(source.contains("progress update: \" + item.mediaId"));
    }

    @Test
    public void serviceAppliesBoundedSkipSettingsAtStartupAndLive() throws Exception {
        String source = SourceFileReader.readString(serviceSourcePath(), StandardCharsets.UTF_8);

        assertTrue(source.contains("new PlaybackControlSettings(this)"));
        assertTrue(source.contains(".setSeekBackIncrementMs("));
        assertTrue(source.contains(".setSeekForwardIncrementMs("));
        assertTrue(source.contains("PlaybackControlSettings.isSkipPreference(key)"));
        assertTrue(source.contains("PlaybackControlSettings.isSpeedPreference(key)"));
        assertTrue(source.contains("player.setPlaybackSpeed("));
        assertTrue(source.contains("controlSettings.registerListener(this)"));
        assertTrue(source.contains("controlSettings.unregisterListener(this)"));
    }

    private static Path serviceSourcePath() throws IOException {
        Path rootRelative = Path.of(
                "app",
                "src",
                "main",
                "java",
                "io",
                "github",
                "jnesew",
                "plainpoddroid",
                "playback",
                "PlaybackService.java");
        if (Files.isRegularFile(rootRelative)) {
            return rootRelative;
        }
        Path appRelative = Path.of(
                "src",
                "main",
                "java",
                "io",
                "github",
                "jnesew",
                "plainpoddroid",
                "playback",
                "PlaybackService.java");
        if (Files.isRegularFile(appRelative)) {
            return appRelative;
        }
        throw new IOException("PlaybackService source was unavailable to the test.");
    }
}
