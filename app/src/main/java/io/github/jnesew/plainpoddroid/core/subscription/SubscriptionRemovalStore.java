package io.github.jnesew.plainpoddroid.core.subscription;

import java.io.IOException;
import java.util.List;

/** Catalog operations used while withdrawing a subscription. */
public interface SubscriptionRemovalStore {
    boolean archiveSubscription(long subscriptionId) throws IOException;

    /** Returns null when the archive no longer exists. */
    List<String> listArchivedMediaIds(long subscriptionId) throws IOException;

    boolean deleteArchivedSubscription(long subscriptionId) throws IOException;
}
