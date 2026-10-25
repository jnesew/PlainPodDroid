package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.Objects;

/** Bounded parent metadata for episode views independent of a selected podcast list. */
public record PodcastContext(long subscriptionId, String title,
        CanonicalHttpsUrl artworkUrl, boolean subscribed) {
    public PodcastContext {
        Objects.requireNonNull(title, "title");
        if (subscriptionId < 1L || title.length() > SecurityLimits.MAX_FEED_TITLE_CHARS) {
            throw new IllegalArgumentException("Invalid podcast context.");
        }
    }
}
