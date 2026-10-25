package io.github.jnesew.plainpoddroid.core.security;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Source contracts supplement (not replace) future Android interaction tests. */
public final class ChapterUiSourceContractTest {
    private static Path root() {
        return Files.isDirectory(Path.of("app/src/main")) ? Path.of("app") : Path.of("");
    }
    private static String activity() throws Exception {
        return SourceFileReader.readString(root().resolve("src/main/java/io/github/jnesew/plainpoddroid/MainActivity.java"));
    }
    @Test public void publisherContactIsOnlyInExplicitLoadAction() throws Exception {
        String source=activity();
        assertEquals(1,source.split("chapterLoadController\\.load\\(",-1).length-1);
        assertTrue(source.contains("loadEpisodeChaptersButton.setOnClickListener(ignored -> loadEpisodeChapters())"));
        assertTrue(source.contains("details.isChapterReferenceAvailable()"));
        String app=SourceFileReader.readString(root().resolve("src/main/java/io/github/jnesew/plainpoddroid/PlainPodDroidApplication.java"));
        assertFalse(app.contains("episodeChapterCoordinator.load("));
        assertFalse(app.contains(".acquire("));
        String layout=SourceFileReader.readString(root().resolve("src/main/res/layout/screen_episode.xml"));
        assertTrue(layout.contains("@string/chapter_load_description"));
    }
    @Test public void renderingIsInertBoundedAndNeverRequestsArt() throws Exception {
        String source=SourceFileReader.readString(root().resolve("src/main/java/io/github/jnesew/plainpoddroid/ui/ChapterPanel.java"));
        assertTrue(source.contains("offset+SecurityLimits.MAX_VISIBLE_CHAPTERS"));
        assertTrue(source.contains("row.setContentDescription("));
        assertTrue(source.contains("button.setText(label)"));
        for (String file : new String[] {"MainActivity.java", "ui/ChapterPanel.java", "ui/ChapterSeekBar.java"}) {
            String view=SourceFileReader.readString(root().resolve("src/main/java/io/github/jnesew/plainpoddroid").resolve(file));
            assertFalse(view.contains("chapter.getImageUrl()"));
            assertFalse(view.contains("WebView"));
            assertFalse(view.contains("Html.fromHtml"));
        }
    }
    @Test public void playSeekAndPageAreSeparateValidatedActions() throws Exception {
        String source=activity();
        assertTrue(source.contains("chapterPanel.contains(mediaId, chapter)"));
        assertTrue(source.contains("playbackPanel.seekChapterCurrent(currentEpisodeDetails(), chapter.getStartTimeMillis())"));
        assertTrue(source.contains("playbackPanel.playChapter(currentEpisodeDetails(), chapter.getStartTimeMillis())"));
        assertTrue(source.contains("mediaId.equals(playbackPanel.currentMediaId())"));
        assertTrue(source.contains("CanonicalHttpsUrl.parse(chapter.getWebsiteUrl().toString())"));
        assertTrue(source.contains("chapterLoadController.close()"));
    }
}
