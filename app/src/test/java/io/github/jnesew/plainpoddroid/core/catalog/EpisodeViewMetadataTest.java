package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EpisodeViewMetadataTest {
    private static final String ID = "episode-" + "a".repeat(64);
    private static final PodcastContext PODCAST = new PodcastContext(1, "Podcast", null, false);

    @Test public void parentAndPublicationMetadataAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new PodcastContext(0, "", null, true));
        assertThrows(IllegalArgumentException.class, () -> new PodcastContext(1,
                "x".repeat(SecurityLimits.MAX_FEED_TITLE_CHARS + 1), null, true));
        assertThrows(IllegalArgumentException.class, () -> summary(true,
                "x".repeat(SecurityLimits.MAX_IDENTIFIER_CHARS + 1), PODCAST));
    }

    @Test public void episodeMutationsPreserveTheSelectedPodcastContext() {
        EpisodeDetails original = new EpisodeDetails(ID, "Episode", "", "", null, "Date",
                120_000, 60_000, true, false, false, true, false, false, PODCAST);
        EpisodeDetails changed = original.withFavorite(true).withPlayed(false);
        assertSame(PODCAST, changed.getPodcast());
        assertFalse(changed.getPodcast().subscribed());
        assertTrue(changed.isFavorite());
        assertEquals(0, changed.getPlaybackPositionMillis());
        assertEquals("Date", changed.getPublished());
    }

    @Test public void unknownHistoricalCompletionIsAllowedButInvalidMetadataIsRejected() {
        CatalogEpisodeSummary downloaded = summary(true, "", PODCAST);
        new DownloadedEpisode(downloaded, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisode(downloaded, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisode(downloaded, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisode(downloaded, 1,
                SecurityLimits.MAX_DOWNLOAD_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisode(summary(false, "", PODCAST), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisode(summary(true, "", null), 1, 1));
    }

    @Test public void downloadedPageIsAnImmutableBoundedSnapshot() {
        DownloadedEpisode item = new DownloadedEpisode(summary(true, "", PODCAST), 1, 5);
        List<DownloadedEpisode> items = new ArrayList<>(); items.add(item);
        DownloadedEpisodePage page = new DownloadedEpisodePage(0, items, false);
        items.clear();
        assertEquals(1, page.episodes().size());
        assertThrows(UnsupportedOperationException.class, () -> page.episodes().clear());
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisodePage(-1, List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisodePage(
                SecurityLimits.MAX_GLOBAL_EPISODES, List.of(item), false));
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisodePage(0,
                java.util.Collections.nCopies(SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE + 1, item), false));
        assertThrows(IllegalArgumentException.class, () -> new DownloadedEpisodePage(0,
                java.util.Arrays.asList((DownloadedEpisode) null), false));
    }

    private static CatalogEpisodeSummary summary(boolean downloaded, String published,
            PodcastContext podcast) {
        return new CatalogEpisodeSummary(ID, "Episode", "", 120_000, 0,
                false, false, false, downloaded, false, published, podcast);
    }
}
