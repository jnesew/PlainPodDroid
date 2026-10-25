package io.github.jnesew.plainpoddroid.core.opml;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Snapshot preview; storage recomputes this plan inside the commit transaction. */
public final class OpmlImportPlan {
    private final List<OpmlDocument.Feed> accepted;
    private final int duplicateCount;
    private final int rejectedCount;
    private final boolean exceedsSubscriptionLimit;

    private OpmlImportPlan(List<OpmlDocument.Feed> accepted, int duplicateCount,
            int rejectedCount, boolean exceedsSubscriptionLimit) {
        this.accepted = Collections.unmodifiableList(new ArrayList<>(accepted));
        this.duplicateCount = duplicateCount;
        this.rejectedCount = rejectedCount;
        this.exceedsSubscriptionLimit = exceedsSubscriptionLimit;
    }

    public static OpmlImportPlan calculate(OpmlDocument document,
            Set<CanonicalHttpsUrl> existing) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(existing, "existing");
        if (existing.size() > SecurityLimits.MAX_SUBSCRIPTIONS) {
            throw new IllegalArgumentException("Stored subscription count exceeds the limit");
        }
        List<OpmlDocument.Feed> accepted = new ArrayList<>();
        Set<CanonicalHttpsUrl> known = new HashSet<>(existing);
        int duplicates = document.getDuplicateCount();
        for (OpmlDocument.Feed feed : document.getFeeds()) {
            if (known.add(feed.getUrl())) {
                accepted.add(feed);
            } else {
                duplicates++;
            }
        }
        return new OpmlImportPlan(accepted, duplicates,
                document.getRejectedCount(),
                accepted.size() > SecurityLimits.MAX_SUBSCRIPTIONS - existing.size());
    }

    public List<OpmlDocument.Feed> getAccepted() { return accepted; }

    public int getDuplicateCount() { return duplicateCount; }

    public int getRejectedCount() { return rejectedCount; }

    public boolean exceedsSubscriptionLimit() { return exceedsSubscriptionLimit; }
}
