package io.github.jnesew.plainpoddroid.core.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Saved library order, independent of refresh completion and playback state. */
public enum CatalogSubscriptionOrder {
    LATEST_EPISODE, NAME;

    public List<CatalogSubscriptionSummary> sorted(List<CatalogSubscriptionSummary> podcasts) {
        Comparator<CatalogSubscriptionSummary> names = Comparator
                .comparing(CatalogSubscriptionSummary::getTitle, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(CatalogSubscriptionSummary::getSubscriptionId);
        var result = new ArrayList<>(podcasts);
        result.sort(this == NAME ? names : Comparator
                .comparingLong(CatalogSubscriptionSummary::getLatestEpisodeAtMillis).reversed().thenComparing(names));
        return result;
    }
}
