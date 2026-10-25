package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

import java.util.Objects;

public final class CatalogSubscriptionSummary {
    private final long subscriptionId;
    private final String title;
    private final String author;
    private final int episodeCount;
    private final int downloadedCount;
    private final int unplayedCount;
    private final long updatedAtMillis;
    private final boolean subscribed;
    private final int refreshFailureCount;
    private final long lastRefreshAttemptAtMillis;
    private final CanonicalHttpsUrl artworkUrl;
    private final boolean autoDownload;
    private final CanonicalHttpsUrl sourceUrl;
    private final int newEpisodeCount;
    private final long latestEpisodeAtMillis;
    private final CatalogBrowseQuery.Order episodeOrder;

    public CatalogSubscriptionSummary(
            long subscriptionId,
            String title,
            String author,
            int episodeCount,
            int downloadedCount,
            long updatedAtMillis) {
        this(subscriptionId, title, author, episodeCount, downloadedCount,
                0, updatedAtMillis, true);
    }

    public CatalogSubscriptionSummary(
            long subscriptionId,
            String title,
            String author,
            int episodeCount,
            int downloadedCount,
            long updatedAtMillis,
            boolean subscribed) {
        this(subscriptionId, title, author, episodeCount, downloadedCount,
                0, updatedAtMillis, subscribed);
    }

    public CatalogSubscriptionSummary(
            long subscriptionId,
            String title,
            String author,
            int episodeCount,
            int downloadedCount,
            int unplayedCount,
            long updatedAtMillis,
            boolean subscribed) {
        this(subscriptionId, title, author, episodeCount, downloadedCount,
                unplayedCount, updatedAtMillis, subscribed, 0, 0L);
    }

    public CatalogSubscriptionSummary(
            long subscriptionId, String title, String author, int episodeCount,
            int downloadedCount, int unplayedCount, long updatedAtMillis,
            boolean subscribed, int refreshFailureCount, long lastRefreshAttemptAtMillis) {
        this(subscriptionId, title, author, episodeCount, downloadedCount,
                unplayedCount, updatedAtMillis, subscribed, refreshFailureCount,
                lastRefreshAttemptAtMillis, null);
    }

    public CatalogSubscriptionSummary(
            long subscriptionId, String title, String author, int episodeCount,
            int downloadedCount, int unplayedCount, long updatedAtMillis,
            boolean subscribed, int refreshFailureCount, long lastRefreshAttemptAtMillis,
            CanonicalHttpsUrl artworkUrl) {
        this(subscriptionId, title, author, episodeCount, downloadedCount,
                unplayedCount, updatedAtMillis, subscribed, refreshFailureCount,
                lastRefreshAttemptAtMillis, artworkUrl, false);
    }

    public CatalogSubscriptionSummary(
            long subscriptionId, String title, String author, int episodeCount,
            int downloadedCount, int unplayedCount, long updatedAtMillis,
            boolean subscribed, int refreshFailureCount, long lastRefreshAttemptAtMillis,
            CanonicalHttpsUrl artworkUrl, boolean autoDownload) {
        this(subscriptionId, title, author, episodeCount, downloadedCount, unplayedCount,
                updatedAtMillis, subscribed, refreshFailureCount, lastRefreshAttemptAtMillis,
                artworkUrl, autoDownload, null);
    }

    public CatalogSubscriptionSummary(
            long subscriptionId, String title, String author, int episodeCount,
            int downloadedCount, int unplayedCount, long updatedAtMillis,
            boolean subscribed, int refreshFailureCount, long lastRefreshAttemptAtMillis,
            CanonicalHttpsUrl artworkUrl, boolean autoDownload, CanonicalHttpsUrl sourceUrl) {
        this(subscriptionId, title, author, episodeCount, downloadedCount, unplayedCount,
                updatedAtMillis, subscribed, refreshFailureCount, lastRefreshAttemptAtMillis,
                artworkUrl, autoDownload, sourceUrl, 0, 0L, CatalogBrowseQuery.Order.NEWEST_FIRST);
    }

    public CatalogSubscriptionSummary(long subscriptionId, String title, String author,
            int episodeCount, int downloadedCount, int unplayedCount, long updatedAtMillis,
            boolean subscribed, int refreshFailureCount, long lastRefreshAttemptAtMillis,
            CanonicalHttpsUrl artworkUrl, boolean autoDownload, CanonicalHttpsUrl sourceUrl,
            int newEpisodeCount, long latestEpisodeAtMillis, CatalogBrowseQuery.Order episodeOrder) {
        this.title = bounded(title, SecurityLimits.MAX_FEED_TITLE_CHARS, "title");
        this.author = bounded(author, SecurityLimits.MAX_AUTHOR_CHARS, "author");
        if (subscriptionId < 1L
                || episodeCount < 0
                || episodeCount > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION
                || downloadedCount < 0
                || downloadedCount > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION
                || unplayedCount < 0
                || unplayedCount > episodeCount
                || newEpisodeCount < 0 || newEpisodeCount > episodeCount || latestEpisodeAtMillis < 0L
                || updatedAtMillis < 0L
                || refreshFailureCount < 0
                || refreshFailureCount > SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES
                || lastRefreshAttemptAtMillis < 0L
                || ((refreshFailureCount == 0) != (lastRefreshAttemptAtMillis == 0L))) {
            throw new IllegalArgumentException("Invalid subscription summary.");
        }
        this.subscriptionId = subscriptionId;
        this.episodeCount = episodeCount;
        this.downloadedCount = downloadedCount;
        this.unplayedCount = unplayedCount;
        this.updatedAtMillis = updatedAtMillis;
        this.subscribed = subscribed;
        this.refreshFailureCount = refreshFailureCount;
        this.lastRefreshAttemptAtMillis = lastRefreshAttemptAtMillis;
        this.artworkUrl = artworkUrl;
        this.autoDownload = autoDownload;
        this.sourceUrl = sourceUrl;
        this.newEpisodeCount = newEpisodeCount;
        this.latestEpisodeAtMillis = latestEpisodeAtMillis;
        this.episodeOrder = Objects.requireNonNull(episodeOrder, "episodeOrder");
    }

    public int getNewEpisodeCount() { return newEpisodeCount; }
    public long getLatestEpisodeAtMillis() { return latestEpisodeAtMillis; }
    public CatalogBrowseQuery.Order getEpisodeOrder() { return episodeOrder; }

    public long getSubscriptionId() {
        return subscriptionId;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public int getEpisodeCount() {
        return episodeCount;
    }

    public int getDownloadedCount() {
        return downloadedCount;
    }

    public int getUnplayedCount() {
        return unplayedCount;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public boolean isSubscribed() {
        return subscribed;
    }

    public int getRefreshFailureCount() {
        return refreshFailureCount;
    }

    public long getLastRefreshAttemptAtMillis() {
        return lastRefreshAttemptAtMillis;
    }

    public CanonicalHttpsUrl getArtworkUrl() {
        return artworkUrl;
    }

    public CanonicalHttpsUrl getSourceUrl() { return sourceUrl; }

    public boolean isAutoDownload() {
        return autoDownload;
    }

    private static String bounded(String value, int limit, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > limit) {
            throw new IllegalArgumentException("Invalid " + name + ".");
        }
        return value;
    }
}
