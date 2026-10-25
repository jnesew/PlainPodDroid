package io.github.jnesew.plainpoddroid.core.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Filters the already loaded, bounded subscription summaries without network access. */
public final class CatalogSubscriptionSearch {
    private CatalogSubscriptionSearch() {
        throw new AssertionError("No instances");
    }

    public static String validate(String text) {
        String value = Objects.requireNonNull(text, "text").trim();
        if (value.length() > CatalogBrowseQuery.MAX_SEARCH_CHARS
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid podcast title search.");
        }
        return value;
    }

    public static List<CatalogSubscriptionSummary> filter(
            List<CatalogSubscriptionSummary> subscriptions, String search,
            long selectedSubscriptionId) {
        Objects.requireNonNull(subscriptions, "subscriptions");
        String needle = validate(search).toLowerCase(Locale.ROOT);
        List<CatalogSubscriptionSummary> result = new ArrayList<>();
        for (CatalogSubscriptionSummary subscription : subscriptions) {
            if (needle.isEmpty()
                    || subscription.getSubscriptionId() == selectedSubscriptionId
                    || subscription.getTitle().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(subscription);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
