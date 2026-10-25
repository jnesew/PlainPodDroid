package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CatalogBrowseModelTest {
    @Test
    public void subscriptionSummaryEnforcesCountsAndTextBounds() {
        CatalogSubscriptionSummary summary = new CatalogSubscriptionSummary(
                1L, "Title", "Author", 4, 2, 10L);

        assertEquals(1L, summary.getSubscriptionId());
        assertEquals(4, summary.getEpisodeCount());
        assertEquals(2, summary.getDownloadedCount());
        CatalogSubscriptionSummary counted = new CatalogSubscriptionSummary(
                1L, "Title", "Author", 4, 2, 3, 10L, true);
        assertEquals(3, counted.getUnplayedCount());
        CatalogSubscriptionSummary failed = new CatalogSubscriptionSummary(
                1L, "Title", "Author", 4, 2, 3, 10L, true, 1, 20L);
        assertEquals(1, failed.getRefreshFailureCount());
        assertEquals(20L, failed.getLastRefreshAttemptAtMillis());
        assertThrows(IllegalArgumentException.class, () -> new CatalogSubscriptionSummary(
                1L, "Title", "Author", 4, 2, 3, 10L, true, 1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new CatalogSubscriptionSummary(
                1L, "Title", "Author", 4, 2, 5, 10L, true));
        assertThrows(IllegalArgumentException.class, () ->
                new CatalogSubscriptionSummary(0L, "", "", 0, 0, 0L));
        CatalogSubscriptionSummary archived = new CatalogSubscriptionSummary(
                1L, "", "", 1, 2, 0L, false);
        assertEquals(2, archived.getDownloadedCount());
        assertTrue(!archived.isSubscribed());
        assertThrows(IllegalArgumentException.class, () ->
                new CatalogSubscriptionSummary(1L, "", "", 1,
                        SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION + 1, 0L));
        assertThrows(IllegalArgumentException.class, () ->
                new CatalogSubscriptionSummary(
                        1L,
                        "x".repeat(SecurityLimits.MAX_FEED_TITLE_CHARS + 1),
                        "",
                        0,
                        0,
                        0L));
    }

    @Test
    public void artworkReferenceIsOnlyAValidatedHttpsValue() throws Exception {
        io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl url =
                io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl.parse(
                        "https://publisher.example/cover.png?private=token");
        CatalogSubscriptionSummary summary = new CatalogSubscriptionSummary(
                1L, "Title", "Author", 0, 0, 0, 10L, true, 0, 0L, url);
        assertEquals(url, summary.getArtworkUrl());
        CatalogSubscriptionSummary enabled = new CatalogSubscriptionSummary(
                1L, "Title", "Author", 0, 0, 0, 10L, true, 0, 0L, url, true);
        assertTrue(enabled.isAutoDownload());
        assertTrue(!summary.isAutoDownload());
    }

    @Test
    public void episodeSummaryRequiresOpaqueIdAndBoundedPlaybackValues() {
        CatalogEpisodeSummary summary = episode(1);

        assertTrue(summary.getMediaId().matches("episode-[0-9a-f]{64}"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogEpisodeSummary(
                "https://secret.example/audio.mp3",
                "Title",
                "Author",
                1L,
                0L,
                false,
                false,
                false,
                false,
                false));
        assertThrows(IllegalArgumentException.class, () -> new CatalogEpisodeSummary(
                mediaId(2),
                "Title",
                "Author",
                SecurityLimits.MAX_MEDIA_DURATION_MILLIS + 1L,
                0L,
                false,
                false,
                false,
                false,
                false));
    }

    @Test
    public void searchIsBoundedAndTreatsWildcardCharactersAsText() {
        CatalogBrowseQuery query = new CatalogBrowseQuery(
                CatalogBrowseQuery.Filter.DOWNLOADED, "  100%_  ");
        assertEquals("100%_", query.getTitleSearch());
        assertEquals(CatalogBrowseQuery.Filter.DOWNLOADED, query.getFilter());
        assertThrows(IllegalArgumentException.class, () -> new CatalogBrowseQuery(
                CatalogBrowseQuery.Filter.ALL,
                "x".repeat(CatalogBrowseQuery.MAX_SEARCH_CHARS + 1)));
        assertThrows(IllegalArgumentException.class, () -> new CatalogBrowseQuery(
                CatalogBrowseQuery.Filter.ALL, "bad\nquery"));
    }

    @Test
    public void pageIsImmutableAndEnforcesPageAndTraversalBounds() {
        List<CatalogEpisodeSummary> source = new ArrayList<>();
        source.add(episode(1));
        CatalogEpisodePage page = new CatalogEpisodePage(2L, 0, source, true);
        source.clear();

        assertEquals(1, page.getEpisodes().size());
        assertEquals(1, page.getNextOffset());
        assertThrows(UnsupportedOperationException.class, () ->
                page.getEpisodes().add(episode(2)));
        assertThrows(IllegalArgumentException.class, () -> new CatalogEpisodePage(
                2L,
                SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION,
                List.of(),
                true));
        List<CatalogEpisodeSummary> oversized = new ArrayList<>();
        for (int index = 0; index <= SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE; index++) {
            oversized.add(episode(index));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new CatalogEpisodePage(2L, 0, oversized, false));
    }

    static CatalogEpisodeSummary episode(int value) {
        return new CatalogEpisodeSummary(
                mediaId(value),
                "Episode " + value,
                "Author",
                60_000L,
                0L,
                false,
                false,
                false,
                false,
                false);
    }

    private static String mediaId(int value) {
        return "episode-" + String.format("%064x", value);
    }
}
