package io.github.jnesew.plainpoddroid.core.catalog;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CatalogSubscriptionSearchTest {
    @Test
    public void titleSearchIsLocalLiteralAndKeepsSelectedPodcastAvailable() {
        CatalogSubscriptionSummary first = new CatalogSubscriptionSummary(
                1L, "A 100%_ Show", "", 0, 0, 1L);
        CatalogSubscriptionSummary second = new CatalogSubscriptionSummary(
                2L, "Another", "", 0, 0, 1L);
        List<CatalogSubscriptionSummary> all = List.of(first, second);
        assertEquals(List.of(first), CatalogSubscriptionSearch.filter(
                all, " 100%_ ", -1L));
        assertEquals(List.of(first, second), CatalogSubscriptionSearch.filter(
                all, "100%_", 2L));
        assertEquals(List.of(), CatalogSubscriptionSearch.filter(
                all, "no match", -1L));
        assertEquals(List.of(first), CatalogSubscriptionSearch.filter(
                all, "a 100%_", -1L));
        assertThrows(UnsupportedOperationException.class,
                () -> CatalogSubscriptionSearch.filter(all, "", -1L).clear());
    }

    @Test
    public void rejectsUnboundedOrControlSearch() {
        assertThrows(IllegalArgumentException.class, () ->
                CatalogSubscriptionSearch.validate("x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () ->
                CatalogSubscriptionSearch.validate("a\nb"));
    }
}
