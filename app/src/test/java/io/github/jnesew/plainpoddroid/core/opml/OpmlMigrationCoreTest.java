package io.github.jnesew.plainpoddroid.core.opml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class OpmlMigrationCoreTest {
    @Test
    public void previewDistinguishesExistingAndFileDuplicatesFromInvalidUrls() throws Exception {
        byte[] xml = ("<opml><body>"
                + "<outline xmlUrl='https://existing.example/feed'/>"
                + "<outline xmlUrl='https://new.example/feed'/>"
                + "<outline xmlUrl='https://new.example/feed'/>"
                + "<outline xmlUrl='http://invalid.example/feed'/>"
                + "</body></opml>").getBytes(StandardCharsets.UTF_8);
        OpmlDocument document = new OpmlParser().parse(new ByteArrayInputStream(xml));
        Set<CanonicalHttpsUrl> existing = Set.of(
                CanonicalHttpsUrl.parse("https://existing.example/feed"));
        OpmlImportPlan plan = OpmlImportPlan.calculate(document, existing);
        assertEquals(1, plan.getAccepted().size());
        assertEquals(2, plan.getDuplicateCount());
        assertEquals(1, plan.getRejectedCount());
        assertFalse(plan.exceedsSubscriptionLimit());
    }

    @Test
    public void capacityIsAllOrNothingIncludingConcurrentExistingState() throws Exception {
        List<OpmlDocument.Feed> entries = List.of(feed("https://new.example/a", "A"),
                feed("https://new.example/b", "B"));
        OpmlDocument document = new OpmlDocument(entries, 0, 0);
        Set<CanonicalHttpsUrl> existing = new HashSet<>();
        for (int index = 0; index < SecurityLimits.MAX_SUBSCRIPTIONS - 1; index++) {
            existing.add(CanonicalHttpsUrl.parse("https://existing.example/" + index));
        }
        OpmlImportPlan plan = OpmlImportPlan.calculate(document, existing);
        assertTrue(plan.exceedsSubscriptionLimit());
        assertEquals(2, plan.getAccepted().size());
    }

    @Test
    public void writerEscapesValuesAndRoundTripsWithinInputLimit() throws Exception {
        List<OpmlDocument.Feed> feeds = List.of(feed(
                "https://example.com/rss?token=private&part=2", "A & <B> \"podcast\""));
        byte[] output = new OpmlWriter().write(feeds);
        String text = new String(output, StandardCharsets.UTF_8);
        assertTrue(text.contains("&amp;part=2"));
        assertTrue(text.contains("A &amp; &lt;B&gt; &quot;"));
        OpmlDocument parsed = new OpmlParser().parse(new ByteArrayInputStream(output));
        assertEquals(feeds.get(0).getUrl(), parsed.getFeeds().get(0).getUrl());
        assertEquals(feeds.get(0).getLabel(), parsed.getFeeds().get(0).getLabel());
    }

    @Test
    public void writerRejectsOversizeBeforeDestinationIsTouched() throws Exception {
        List<OpmlDocument.Feed> feeds = new ArrayList<>();
        for (int index = 0; index < SecurityLimits.MAX_SUBSCRIPTIONS; index++) {
            feeds.add(feed("https://example.com/" + index + "/" + "a".repeat(3000), "A"));
        }
        assertThrows(IOException.class, () -> new OpmlWriter().write(feeds));
    }

    private static OpmlDocument.Feed feed(String url, String title) throws Exception {
        return new OpmlDocument.Feed(CanonicalHttpsUrl.parse(url), title);
    }
}
