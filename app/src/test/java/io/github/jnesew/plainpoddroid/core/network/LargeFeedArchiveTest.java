package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.catalog.EpisodeCatalogMapper;
import io.github.jnesew.plainpoddroid.core.feed.FeedParseException;
import io.github.jnesew.plainpoddroid.core.feed.FeedParser;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LargeFeedArchiveTest {
    @Test public void importsLongArchiveWithLargeNotesAsIdentityAndGzip() throws Exception {
        Random random = new Random(7);
        StringBuilder xml = new StringBuilder("<rss><channel><title>Long-running archive</title>");
        for (int episode = 0; episode < 2500; episode++) {
            xml.append("<item><guid>").append(episode).append("</guid><title>Episode</title><description>");
            for (int ch = 0; ch < 5000; ch++) xml.append((char) ('a' + random.nextInt(26)));
            xml.append("</description><enclosure type=\"audio/mpeg\" url=\"https://media.example/")
                    .append(episode).append(".mp3\"/></item>");
        }
        byte[] identity = xml.append("</channel></rss>").toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(identity.length > 8 * 1024 * 1024);
        for (boolean gzip : new boolean[]{false, true}) {
            byte[] wire;
            if (gzip) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (GZIPOutputStream stream = new GZIPOutputStream(out)) { stream.write(identity); }
                wire = out.toByteArray();
            } else wire = identity;
            boolean[] closed = {false};
            FeedTransport transport = (url, headers) -> new FeedTransport.Exchange() {
                @Override public int getStatusCode() { return 200; }
                @Override public String getHeader(String name) {
                    return switch (name) {
                        case "Content-Type" -> "application/xml";
                        case "Content-Encoding" -> gzip ? "gzip" : null;
                        case "Content-Length" -> Integer.toString(wire.length);
                        default -> null;
                    };
                }
                @Override public InputStream getBody() { return new ByteArrayInputStream(wire); }
                @Override public void close() { closed[0] = true; }
            };
            var acquisition = new BoundedFeedAcquirer(transport).acquire("https://feeds.example/archive");
            assertEquals(2500, acquisition.getDocument().getEpisodes().size());
            var catalog = new EpisodeCatalogMapper().map(acquisition);
            assertEquals(2500, catalog.getEpisodes().size());
            assertEquals(0, catalog.getRejectedEpisodeCount());
            assertTrue(closed[0]);
        }
    }

    @Test public void retainsFiniteProductionEpisodeCeilingWithoutReturningPartialArchive() throws Exception {
        String item = "<item><title>Episode</title></item>";
        String prefix = "<rss><channel><title>Archive</title>";
        String suffix = "</channel></rss>";
        assertEquals(SecurityLimits.MAX_EPISODES_PER_FEED, new FeedParser().parse(new ByteArrayInputStream(
                (prefix + item.repeat(SecurityLimits.MAX_EPISODES_PER_FEED) + suffix)
                        .getBytes(StandardCharsets.UTF_8))).getEpisodes().size());
        FeedParseException failure = assertThrows(FeedParseException.class, () -> new FeedParser().parse(
                new ByteArrayInputStream((prefix + item.repeat(SecurityLimits.MAX_EPISODES_PER_FEED + 1) + suffix)
                        .getBytes(StandardCharsets.UTF_8))));
        assertEquals(FeedParseException.Reason.EPISODE_LIMIT, failure.getReason());
    }
}
