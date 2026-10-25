package io.github.jnesew.plainpoddroid.ui;

import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisodeSummary;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueEntry;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class EpisodeMetadataFormatterTest {
    private static final String ID = "episode-" + "a".repeat(64);
    private final EpisodeMetadataFormatter formatter = new EpisodeMetadataFormatter(
            (resource, args) -> String.format(Locale.ROOT, patterns().get(resource), args));

    @Test public void clockFormatsWholeSecondsAcrossMinuteAndHourBoundaries() {
        assertEquals("0:00", formatter.duration(999));
        assertEquals("0:59", formatter.duration(59_999));
        assertEquals("1:00", formatter.duration(60_000));
        assertEquals("1:01:02", formatter.duration(3_662_000));
    }

    @Test public void queueOmitsAuthorFlagsAndUnknownDuration() {
        EpisodeQueueEntry entry = new EpisodeQueueEntry(ID, "Title", "Author", -1,
                60_000, true, false, true, 0);
        assertEquals("",
                formatter.queue(entry));
    }

    @Test public void queueShowsOnlyCompactDuration() {
        EpisodeQueueEntry entry = new EpisodeQueueEntry(ID, "Title", "", 120_000,
                0, false, true, false, 0);
        assertEquals("2m", formatter.queue(entry));
    }

    @Test public void catalogOmitsFlagsAndUsesCompactDuration() {
        CatalogEpisodeSummary episode = new CatalogEpisodeSummary(ID, "Title", "Author",
                19_317_000, 0, false, true, true, false, false);
        assertEquals("5h 22m", formatter.catalog(episode));
    }

    @Test public void remainingAndUnknownLengthProgressOnlyDescribeUnfinishedEpisodes() {
        assertEquals("48m left", formatter.listeningTime(4_137_000, 1_275_000, false));
        assertEquals("5m listened", formatter.listeningTime(-1, 300_000, false));
        assertEquals("<1m left", formatter.listeningTime(60_000, 59_900, false));
        assertEquals("2m", formatter.listeningTime(120_000, 120_000, false));
        assertEquals("2m", formatter.listeningTime(120_000, 10_000, true));
        assertEquals("", formatter.listeningTime(-1, 0, false));
    }

    @Test public void publicationUsesLocalCalendarAndIncludesOnlyDifferentYears() {
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-10T00:00:00Z"),
                java.time.ZoneId.of("Europe/Helsinki"));
        var dates = new EpisodeMetadataFormatter((r,a)->"",clock,()->Locale.ENGLISH);
        assertEquals("17 Sep", dates.publicationDate("Thu, 17 Sep 2026 00:40:49 +0000"));
        assertEquals("1 Jan", dates.publicationDate("2025-12-31T23:30:00Z"));
        assertEquals("Aug 26, 2025", dates.publicationDate("2025-08-26T12:00:00Z"));
        assertEquals("", dates.publicationDate("not a date"));
        assertEquals("", dates.publicationDate("26 Aug 2026"));
    }

    @Test public void detailsPreservePublicationAndAllStatusOrdering() {
        EpisodeDetails episode = new EpisodeDetails(ID, "Title", "Author", "Notes", null,
                "Published date", -1, 60_000, true, true, true, true, true, false);
        assertEquals("Published Published date · Unknown · Progress 1:00 · Played"
                + " · Favorite · Queued · Downloaded · Stale", formatter.details(episode));
    }

    private static Map<Integer, String> patterns() {
        return Map.ofEntries(
                Map.entry(R.string.ui_time_minutes, "%dm"),
                Map.entry(R.string.ui_time_hours, "%dh"),
                Map.entry(R.string.ui_time_hours_minutes, "%dh %dm"),
                Map.entry(R.string.ui_time_less_minute, "<1m"),
                Map.entry(R.string.ui_time_left, "%s left"),
                Map.entry(R.string.ui_time_listened, "%s listened"),
                Map.entry(R.string.catalog_duration_hours_format, "%d:%02d:%02d"),
                Map.entry(R.string.catalog_duration_minutes_format, "%d:%02d"),
                Map.entry(R.string.catalog_episode_duration_unknown, "Unknown"),
                Map.entry(R.string.catalog_episode_duration_format, "Duration %s"),
                Map.entry(R.string.catalog_episode_progress_format, "Progress %s"),
                Map.entry(R.string.catalog_episode_played, "Played"),
                Map.entry(R.string.catalog_episode_favorite, "Favorite"),
                Map.entry(R.string.catalog_episode_queued, "Queued"),
                Map.entry(R.string.catalog_episode_downloaded, "Downloaded"),
                Map.entry(R.string.catalog_episode_not_downloaded, "Not downloaded"),
                Map.entry(R.string.catalog_episode_stale, "Stale"),
                Map.entry(R.string.episode_details_published_format, "Published %s"));
    }
}
