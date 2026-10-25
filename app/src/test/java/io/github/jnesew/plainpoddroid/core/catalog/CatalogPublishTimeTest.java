package io.github.jnesew.plainpoddroid.core.catalog;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;

public final class CatalogPublishTimeTest {
    @Test
    public void parsesAtomAndRssTimesWithExplicitZones() {
        long expected = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli();
        assertEquals(expected, CatalogPublishTime.epochMillis("2026-09-29T10:00:00Z"));
        assertEquals(expected, CatalogPublishTime.epochMillis("2026-09-29T12:00:00+02:00"));
        assertEquals(expected, CatalogPublishTime.epochMillis(
                "Tue, 29 Sep 2026 10:00:00 GMT"));
    }

    @Test
    public void malformedOrAmbiguousDatesRemainUndated() {
        assertEquals(0L, CatalogPublishTime.epochMillis(null));
        assertEquals(0L, CatalogPublishTime.epochMillis(""));
        assertEquals(0L, CatalogPublishTime.epochMillis("2026-09-29"));
        assertEquals(0L, CatalogPublishTime.epochMillis("2026-09-29T10:00:00"));
        assertEquals(0L, CatalogPublishTime.epochMillis("garbage"));
        assertEquals(0L, CatalogPublishTime.epochMillis("x".repeat(2049)));
        assertEquals(0L, CatalogPublishTime.epochMillis("1800-01-01T00:00:00Z"));
    }
}
