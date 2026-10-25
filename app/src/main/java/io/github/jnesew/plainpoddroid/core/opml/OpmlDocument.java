package io.github.jnesew.plainpoddroid.core.opml;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Parsed candidates only; constructing this value cannot subscribe or fetch. */
public final class OpmlDocument {
    public static final class Feed {
        private final CanonicalHttpsUrl url;
        private final String label;

        public Feed(CanonicalHttpsUrl url, String label) {
            this.url = Objects.requireNonNull(url, "url");
            this.label = Objects.requireNonNull(label, "label");
            if (label.length() > SecurityLimits.MAX_OPML_LABEL_CHARS) {
                throw new IllegalArgumentException("OPML label exceeds the limit");
            }
        }

        public CanonicalHttpsUrl getUrl() { return url; }

        public String getLabel() { return label; }
    }

    private final List<Feed> feeds;
    private final int rejectedCount;
    private final int duplicateCount;

    OpmlDocument(List<Feed> feeds, int rejectedCount, int duplicateCount) {
        this.feeds = Collections.unmodifiableList(new ArrayList<>(feeds));
        this.rejectedCount = rejectedCount;
        this.duplicateCount = duplicateCount;
    }

    public List<Feed> getFeeds() { return feeds; }

    public int getRejectedCount() { return rejectedCount; }

    public int getDuplicateCount() { return duplicateCount; }
}
