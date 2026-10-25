package io.github.jnesew.plainpoddroid.core.catalog;

import java.util.Objects;

/** Bounded, local-only criteria for a selected subscription's episode list. */
public final class CatalogBrowseQuery {
    public static final int MAX_SEARCH_CHARS = 128;

    public enum Filter { ALL, UNPLAYED, DOWNLOADED, QUEUED, COMPLETED }
    public enum Order { NEWEST_FIRST, OLDEST_FIRST }

    public static final CatalogBrowseQuery DEFAULT = new CatalogBrowseQuery(Filter.ALL, "");

    private final Filter filter;
    private final String titleSearch;

    public CatalogBrowseQuery(Filter filter, String titleSearch) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.titleSearch = Objects.requireNonNull(titleSearch, "titleSearch").trim();
        if (this.titleSearch.length() > MAX_SEARCH_CHARS
                || this.titleSearch.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid local title search.");
        }
    }

    public Filter getFilter() {
        return filter;
    }

    public String getTitleSearch() {
        return titleSearch;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CatalogBrowseQuery query
                && filter == query.filter && titleSearch.equals(query.titleSearch);
    }

    @Override
    public int hashCode() {
        return 31 * filter.hashCode() + titleSearch.hashCode();
    }
}
