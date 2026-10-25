package io.github.jnesew.plainpoddroid.core.catalog;

import java.io.IOException;
import java.util.List;

public interface CatalogBrowserStore {
    List<CatalogSubscriptionSummary> listSubscriptions() throws IOException;

    /** Explicit successful viewing, bounded to the generation actually displayed. */
    default void markSubscriptionViewed(long subscriptionId, long snapshotGeneration) throws IOException { }

    CatalogEpisodePage listEpisodes(long subscriptionId, int offset, int limit,
            CatalogBrowseQuery query)
            throws IOException;
}
