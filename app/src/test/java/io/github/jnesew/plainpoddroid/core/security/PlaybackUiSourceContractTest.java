package io.github.jnesew.plainpoddroid.core.security;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Scoped policy guards supplement PlaybackActions/PlaybackConnection behavior tests. */
public final class PlaybackUiSourceContractTest {
    @Test public void playbackAdapterCannotInjectRawMediaUrls() throws Exception {
        String source = source("PlaybackPanel.java");
        assertTrue(source.contains(".setMediaId(opaqueMediaId)"));
        assertFalse(source.contains(".setUri("));
        assertFalse(source.contains("MediaItem.fromUri"));
    }

    @Test public void navigationRowsHaveNoPlaybackOrDownloadDependency() throws Exception {
        String source = source("EpisodeRowViews.java");
        assertFalse(source.contains("MediaController"));
        assertFalse(source.contains("PlaybackActions"));
        assertFalse(source.contains("EpisodeDownloadController"));
        assertFalse(source.contains(".play("));
        assertFalse(source.contains(".download("));
        for (String binding : new String[] {"NavigationShell.java", "PodcastLibraryView.java", "EpisodeListView.java", "PullRefreshScrollView.java", "ChapterPanel.java", "ChapterSeekBar.java"}) {
            String redesigned = source(binding);
            assertFalse(redesigned.contains("MediaController"));
            assertFalse(redesigned.contains("PlaybackActions"));
            assertFalse(redesigned.contains("EpisodeDownloadController"));
            assertFalse(redesigned.contains(".setUri("));
        }
    }

    @Test public void playbackSpeedUsesBoundedSettingsRatherThanControllerInjection()
            throws Exception {
        String source = source("PlaybackPanel.java");
        assertTrue(source.contains("PlaybackSpeedSetting.values()"));
        assertTrue(source.contains("playbackControlSettings.setPlaybackSpeed("));
        assertFalse(source.contains("controller().setPlaybackSpeed("));
        assertFalse(source.contains("controller().setPlaybackParameters("));
    }

    private static String source(String file) throws Exception {
        Path root = Files.isDirectory(Path.of("app/src/main")) ? Path.of("app") : Path.of("");
        return SourceFileReader.readString(root.resolve(
                "src/main/java/io/github/jnesew/plainpoddroid/ui").resolve(file));
    }
}
