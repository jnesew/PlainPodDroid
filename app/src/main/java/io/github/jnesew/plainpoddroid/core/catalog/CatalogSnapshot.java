package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CatalogSnapshot {
    private final CanonicalHttpsUrl subscriptionUrl;
    private final CanonicalHttpsUrl finalFeedUrl;
    private final String title;
    private final String author;
    private final String notes;
    private final CanonicalHttpsUrl websiteUrl;
    private final CanonicalHttpsUrl artworkUrl;
    private final List<String> categories;
    private final List<CatalogEpisode> episodes;
    private final int rejectedEpisodeCount;
    private final int duplicateEpisodeCount;

    CatalogSnapshot(
            CanonicalHttpsUrl subscriptionUrl,
            CanonicalHttpsUrl finalFeedUrl,
            String title,
            String author,
            String notes,
            CanonicalHttpsUrl websiteUrl,
            CanonicalHttpsUrl artworkUrl,
            List<String> categories,
            List<CatalogEpisode> episodes,
            int rejectedEpisodeCount,
            int duplicateEpisodeCount) {
        this.subscriptionUrl = Objects.requireNonNull(subscriptionUrl, "subscriptionUrl");
        this.finalFeedUrl = Objects.requireNonNull(finalFeedUrl, "finalFeedUrl");
        this.title = Objects.requireNonNull(title, "title");
        this.author = Objects.requireNonNull(author, "author");
        this.notes = Objects.requireNonNull(notes, "notes");
        this.websiteUrl = websiteUrl;
        this.artworkUrl = artworkUrl;
        this.categories = immutableCopy(categories);
        this.episodes = immutableCopy(episodes);
        this.rejectedEpisodeCount = rejectedEpisodeCount;
        this.duplicateEpisodeCount = duplicateEpisodeCount;
    }

    public CanonicalHttpsUrl getSubscriptionUrl() {
        return subscriptionUrl;
    }

    public CanonicalHttpsUrl getFinalFeedUrl() {
        return finalFeedUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getNotes() {
        return notes;
    }

    public CanonicalHttpsUrl getWebsiteUrl() {
        return websiteUrl;
    }

    public CanonicalHttpsUrl getArtworkUrl() {
        return artworkUrl;
    }

    public List<String> getCategories() {
        return categories;
    }

    public List<CatalogEpisode> getEpisodes() {
        return episodes;
    }

    public int getRejectedEpisodeCount() {
        return rejectedEpisodeCount;
    }

    public int getDuplicateEpisodeCount() {
        return duplicateEpisodeCount;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
