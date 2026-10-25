package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import java.io.IOException;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;



public interface SubscriptionStore {
    SubscriptionRefreshTarget findSubscriptionRefreshTarget(long subscriptionId)
            throws IOException;

    long addConfirmedSubscription(CatalogSnapshot snapshot, FeedValidators validators)
            throws IOException;

    default long addConfirmedSubscription(CatalogSnapshot snapshot, FeedValidators validators,
            BasicFeedLogin login) throws IOException {
        if (login != null) throw new IOException("Authenticated storage is unavailable.");
        return addConfirmedSubscription(snapshot, validators);
    }

    /** Commit only against the exact membership generation captured before network acquisition. */
    void refreshExistingSubscription(SubscriptionRefreshTarget target, CatalogSnapshot snapshot, FeedValidators validators)
            throws IOException;

    void recordUnmodifiedSubscription(
            SubscriptionRefreshTarget target,
            FeedValidators validators) throws IOException;
}
