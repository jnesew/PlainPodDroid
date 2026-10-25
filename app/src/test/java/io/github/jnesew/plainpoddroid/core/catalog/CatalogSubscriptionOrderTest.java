package io.github.jnesew.plainpoddroid.core.catalog;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class CatalogSubscriptionOrderTest {
    @Test public void refreshAndPlaybackDoNotDetermineLibraryOrder() {
        var older = podcast(1, "Zebra", 900, 100);
        var newer = podcast(2, "Beta", 1, 200);
        var tied = podcast(3, "Alpha", 2, 200);
        var undated = podcast(4, "Empty", 1000, 0);
        var input = List.of(older, newer, undated, tied);
        assertEquals(List.of(tied, newer, older, undated), CatalogSubscriptionOrder.LATEST_EPISODE.sorted(input));
        assertEquals(List.of(tied, newer, undated, older), CatalogSubscriptionOrder.NAME.sorted(input));
        assertEquals(List.of(older, newer, undated, tied), input);
    }
    private static CatalogSubscriptionSummary podcast(long id, String title, long refreshed, long published) {
        return new CatalogSubscriptionSummary(id, title, "", 1, 0, 1, refreshed, true,
                0, 0L, null, false, null, 0, published, CatalogBrowseQuery.Order.NEWEST_FIRST);
    }
}
