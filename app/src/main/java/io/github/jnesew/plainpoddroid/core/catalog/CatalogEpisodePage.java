package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CatalogEpisodePage {
    private final long subscriptionId;
    private final int offset;
    private final List<CatalogEpisodeSummary> episodes;
    private final boolean hasMore;
    private final long snapshotGeneration;

    public CatalogEpisodePage(
            long subscriptionId,
            int offset,
            List<CatalogEpisodeSummary> episodes,
            boolean hasMore) {
        this(subscriptionId, offset, episodes, hasMore, 0L);
    }

    public CatalogEpisodePage(long subscriptionId, int offset,
            List<CatalogEpisodeSummary> episodes, boolean hasMore, long snapshotGeneration) {
        Objects.requireNonNull(episodes, "episodes");
        if (subscriptionId < 1L || snapshotGeneration < 0L
                || offset < 0
                || offset > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION
                || episodes.size() > SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE
                || offset + episodes.size() > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION
                || (hasMore
                        && offset + episodes.size()
                        >= SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION)) {
            throw new IllegalArgumentException("Invalid catalog page.");
        }
        this.subscriptionId = subscriptionId;
        this.offset = offset;
        this.episodes = Collections.unmodifiableList(new ArrayList<>(episodes));
        this.hasMore = hasMore;
        this.snapshotGeneration = snapshotGeneration;
    }

    public long getSnapshotGeneration() { return snapshotGeneration; }

    public long getSubscriptionId() {
        return subscriptionId;
    }

    public int getOffset() {
        return offset;
    }

    public List<CatalogEpisodeSummary> getEpisodes() {
        return episodes;
    }

    public boolean hasMore() {
        return hasMore;
    }

    public int getNextOffset() {
        return offset + episodes.size();
    }
}
