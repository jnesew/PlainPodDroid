package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.feed.FeedDocument;
import io.github.jnesew.plainpoddroid.core.feed.FeedParser;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadTarget;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class EpisodeCatalogMapperTest {
    @Test
    public void selectsFirstEligibleCanonicalAudioAndMapsBoundedMetadata() throws Exception {
        String item = """
                <item>
                  <guid>episode-guid</guid>
                  <title>Episode One</title>
                  <description>Notes</description>
                  <link>https://publisher.example/episode</link>
                  <pubDate>Fri, 29 Aug 2026 10:00:00 GMT</pubDate>
                  <itunes:duration>01:02:03</itunes:duration>
                  <category>Security</category>
                  <enclosure url="http://cdn.example/one.mp3" type="audio/mpeg" />
                  <enclosure url="https://cdn.example/one.mp4" type="video/mp4" />
                  <enclosure url="HTTPS://CDN.EXAMPLE:443/one.MP3#tracking"
                      type="Audio/MPEG; charset=binary" length="42" />
                </item>
                """;

        CatalogSnapshot snapshot = map(rss(item));

        assertEquals("Example", snapshot.getTitle());
        assertEquals("Publisher", snapshot.getAuthor());
        assertEquals("https://publisher.example/", snapshot.getWebsiteUrl().toString());
        assertEquals("https://images.example/cover.png", snapshot.getArtworkUrl().toString());
        assertEquals(1, snapshot.getEpisodes().size());
        assertEquals(0, snapshot.getRejectedEpisodeCount());
        CatalogEpisode episode = snapshot.getEpisodes().get(0);
        assertTrue(episode.getMediaId().matches("episode-[0-9a-f]{64}"));
        assertEquals("https://cdn.example/one.MP3", episode.getAudioUrl().toString());
        assertEquals("audio/mpeg", episode.getMimeType());
        assertEquals(42L, episode.getDeclaredLength());
        assertEquals("Publisher", episode.getAuthor());
        assertEquals(3_723_000L, episode.getDurationMillis());
        assertEquals("Security", episode.getCategories().get(0));
        assertEquals(0, episode.getSourceOrder());
    }

    @Test
    public void genericMimeNeedsAudioExtensionAndPlaylistsAndMidiStayExcluded()
            throws Exception {
        String items = item("one", "https://cdn.example/track%2Emp3?token=one",
                "application/octet-stream", 1L, "")
                + item("two", "https://cdn.example/track.bin",
                        "application/octet-stream", 1L, "")
                + item("three", "https://cdn.example/list.m3u8",
                        "audio/mpeg", 1L, "")
                + item("four", "https://cdn.example/track.mp3",
                        "audio/x-mpegurl", 1L, "")
                + item("five", "https://cdn.example/track.mp3",
                        "audio/midi", 1L, "");

        CatalogSnapshot snapshot = map(rss(items));

        assertEquals(1, snapshot.getEpisodes().size());
        assertEquals(4, snapshot.getRejectedEpisodeCount());
        assertEquals(
                "https://cdn.example/track%2Emp3?token=one",
                snapshot.getEpisodes().get(0).getAudioUrl().toString());
    }

    @Test
    public void deduplicatesGuidAndFallbackUrlIdentitiesDeterministically() throws Exception {
        String items = item("same-guid", "https://cdn.example/first.mp3", "audio/mpeg", 1L, "")
                + item("same-guid", "https://cdn.example/second.mp3", "audio/mpeg", 2L, "")
                + item("", "https://cdn.example/no-guid.mp3#one", "audio/mpeg", 3L, "")
                + item("", "https://cdn.example:443/no-guid.mp3#two", "audio/mpeg", 4L, "");

        CatalogSnapshot snapshot = map(rss(items));

        assertEquals(2, snapshot.getEpisodes().size());
        assertEquals(0, snapshot.getRejectedEpisodeCount());
        assertEquals(2, snapshot.getDuplicateEpisodeCount());
        assertEquals(
                "https://cdn.example/first.mp3",
                snapshot.getEpisodes().get(0).getAudioUrl().toString());

        String changed = item(
                "same-guid",
                "https://other.example/replacement.mp3",
                "audio/ogg",
                9L,
                "");
        assertEquals(
                snapshot.getEpisodes().get(0).getMediaId(),
                map(rss(changed)).getEpisodes().get(0).getMediaId());
    }

    @Test
    public void mediaIdIsSubscriptionScopedAndDoesNotExposeTokens() throws Exception {
        FeedDocument document = parse(rss(item(
                "private-guid-token",
                "https://cdn.example/audio.mp3?media_secret=hidden",
                "audio/mpeg",
                1L,
                "")));
        EpisodeCatalogMapper mapper = new EpisodeCatalogMapper();
        CatalogSnapshot first = mapper.map(
                CanonicalHttpsUrl.parse("https://feeds.example/feed?token=one"),
                CanonicalHttpsUrl.parse("https://feeds.example/feed?token=one"),
                document);
        CatalogSnapshot second = mapper.map(
                CanonicalHttpsUrl.parse("https://feeds.example/feed?token=two"),
                CanonicalHttpsUrl.parse("https://feeds.example/feed?token=two"),
                document);

        String firstId = first.getEpisodes().get(0).getMediaId();
        String secondId = second.getEpisodes().get(0).getMediaId();
        assertFalse(firstId.equals(secondId));
        assertFalse(firstId.contains("token"));
        assertFalse(firstId.contains("hidden"));
        assertFalse(firstId.contains("guid"));
    }

    @Test
    public void durationLimitRejectsOnlySyntacticallyValidOverLimitValues() throws Exception {
        String items = item("equal", "https://cdn.example/equal.mp3", "audio/mpeg", 1L,
                "<itunes:duration>48:00:00</itunes:duration>")
                + item("over", "https://cdn.example/over.mp3", "audio/mpeg", 1L,
                        "<itunes:duration>48:00:01</itunes:duration>")
                + item("invalid", "https://cdn.example/invalid.mp3", "audio/mpeg", 1L,
                        "<itunes:duration>not-a-duration</itunes:duration>")
                + item("minutes", "https://cdn.example/minutes.mp3", "audio/mpeg", 1L,
                        "<itunes:duration>120:00</itunes:duration>");

        CatalogSnapshot snapshot = map(rss(items));

        assertEquals(3, snapshot.getEpisodes().size());
        assertEquals(1, snapshot.getRejectedEpisodeCount());
        assertEquals(SecurityLimits.MAX_MEDIA_DURATION_MILLIS,
                snapshot.getEpisodes().get(0).getDurationMillis());
        assertEquals(CatalogEpisode.UNKNOWN_DURATION_MILLIS,
                snapshot.getEpisodes().get(1).getDurationMillis());
        assertEquals(7_200_000L, snapshot.getEpisodes().get(2).getDurationMillis());
    }

    @Test
    public void zeroLengthRssEnclosureBecomesDownloadTargetWithoutChangingCatalogHint()
            throws Exception {
        CatalogEpisode episode = map(rss(item("unknown-size", "https://cdn.example/audio.mp3",
                "audio/mpeg", 0L, ""))).getEpisodes().get(0);
        assertEquals(0L, episode.getDeclaredLength());
        EpisodeDownloadTarget target = new EpisodeDownloadTarget(episode.getMediaId(),
                episode.getAudioUrl(), episode.getMimeType(), episode.getDeclaredLength(), false);
        // Existing rows retain zero so download completion can compare the original metadata.
        assertEquals(0L, target.getDeclaredLength());
        assertThrows(IllegalArgumentException.class, () -> new EpisodeDownloadTarget(
                episode.getMediaId(), episode.getAudioUrl(), episode.getMimeType(), -2L, false));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeDownloadTarget(
                episode.getMediaId(), episode.getAudioUrl(), episode.getMimeType(),
                SecurityLimits.MAX_DOWNLOAD_BYTES + 1L, false));
    }

    @Test
    public void declaredDownloadCeilingIsInclusiveAndOversizeIsRejected() throws Exception {
        String items = item(
                "equal",
                "https://cdn.example/equal.flac",
                "audio/flac",
                SecurityLimits.MAX_DOWNLOAD_BYTES,
                "")
                + item(
                        "over",
                        "https://cdn.example/over.flac",
                        "audio/flac",
                        SecurityLimits.MAX_DOWNLOAD_BYTES + 1L,
                        "");

        CatalogSnapshot snapshot = map(rss(items));

        assertEquals(1, snapshot.getEpisodes().size());
        assertEquals(SecurityLimits.MAX_DOWNLOAD_BYTES,
                snapshot.getEpisodes().get(0).getDeclaredLength());
        assertEquals(1, snapshot.getRejectedEpisodeCount());
    }

    @Test
    public void optionalPublisherLinksMustPassTheSameHttpsUrlPolicy() throws Exception {
        String xml = """
                <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                     xmlns:podcast="https://podcastindex.org/namespace/1.0">
                  <channel>
                    <title>Unsafe links</title>
                    <link>http://publisher.example/</link>
                    <itunes:image href="https://127.0.0.1/cover.png" />
                    <item>
                      <guid>one</guid>
                      <title>One</title>
                      <link>https://user:secret@publisher.example/episode</link>
                      <enclosure url="https://cdn.example/one.mp3" type="audio/mpeg" />
                    </item>
                  </channel>
                </rss>
                """;

        CatalogSnapshot snapshot = map(xml);

        assertNull(snapshot.getWebsiteUrl());
        assertNull(snapshot.getArtworkUrl());
        assertNull(snapshot.getEpisodes().get(0).getWebsiteUrl());
        assertEquals(
                CatalogEpisode.UNKNOWN_DECLARED_LENGTH,
                snapshot.getEpisodes().get(0).getDeclaredLength());
    }

    @Test
    public void mapsOnlyExactJsonChaptersMimeAtCanonicalHttpsUrl() throws Exception {
        String items = chapterItem(
                "one", "HTTPS://CHAPTERS.EXAMPLE:443/one.json#fragment",
                " Application/JSON+Chapters ")
                + chapterItem("two", "http://chapters.example/two.json",
                        "application/json+chapters")
                + chapterItem("three", "https://chapters.example/three.json",
                        "application/json+chapters; charset=utf-8");

        CatalogSnapshot snapshot = map(rss(items));

        assertEquals("https://chapters.example/one.json",
                snapshot.getEpisodes().get(0).getChapterUrl().toString());
        assertNull(snapshot.getEpisodes().get(1).getChapterUrl());
        assertNull(snapshot.getEpisodes().get(2).getChapterUrl());
    }

    @Test
    public void snapshotAndNestedListsAreImmutable() throws Exception {
        CatalogSnapshot snapshot = map(rss(item(
                "one",
                "https://cdn.example/one.mp3",
                "audio/mpeg",
                1L,
                "<category>One</category>")));

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.getEpisodes().add(snapshot.getEpisodes().get(0)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.getCategories().add("Other"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.getEpisodes().get(0).getCategories().add("Other"));
    }

    private static CatalogSnapshot map(String xml) throws Exception {
        return new EpisodeCatalogMapper().map(
                CanonicalHttpsUrl.parse("https://feeds.example/feed?token=subscription"),
                CanonicalHttpsUrl.parse("https://cdn-feeds.example/final"),
                parse(xml));
    }

    private static String chapterItem(String guid, String chapterUrl, String chapterType) {
        return "<item><guid>" + guid + "</guid><title>" + guid + "</title>"
                + "<enclosure url=\"https://cdn.example/" + guid
                + ".mp3\" type=\"audio/mpeg\"/>"
                + "<podcast:chapters url=\"" + chapterUrl + "\" type=\""
                + chapterType + "\"/></item>";
    }

    private static FeedDocument parse(String xml) throws Exception {
        return new FeedParser().parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String rss(String items) {
        return """
                <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                     xmlns:podcast="https://podcastindex.org/namespace/1.0">
                  <channel>
                    <title>Example</title>
                    <author>Publisher</author>
                    <description>Show notes</description>
                    <link>https://publisher.example/</link>
                    <itunes:image href="https://images.example/cover.png" />
                    <category>Technology</category>
                """ + items + "</channel></rss>";
    }

    private static String item(
            String guid,
            String url,
            String mimeType,
            long length,
            String extras) {
        String guidElement = guid.isEmpty() ? "" : "<guid>" + guid + "</guid>";
        return "<item>"
                + guidElement
                + "<title>" + (guid.isEmpty() ? "No GUID" : guid) + "</title>"
                + extras
                + "<enclosure url=\"" + xmlAttribute(url) + "\" type=\""
                + xmlAttribute(mimeType) + "\" length=\"" + length + "\" />"
                + "</item>";
    }

    private static String xmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;");
    }
}
