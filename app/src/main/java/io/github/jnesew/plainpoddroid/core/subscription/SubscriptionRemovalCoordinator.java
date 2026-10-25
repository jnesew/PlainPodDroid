package io.github.jnesew.plainpoddroid.core.subscription;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** File cleanup precedes the database cascade, so a failed delete remains retryable. */
public final class SubscriptionRemovalCoordinator {
    @FunctionalInterface
    public interface DownloadFiles {
        void delete(String mediaId) throws IOException;
    }

    private final SubscriptionRemovalStore catalog;
    private final DownloadFiles files;

    public SubscriptionRemovalCoordinator(
            SubscriptionRemovalStore catalog, DownloadFiles files) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.files = Objects.requireNonNull(files, "files");
    }

    public synchronized boolean remove(long subscriptionId, boolean keepDownloads)
            throws IOException {
        if (subscriptionId < 1L) {
            throw new IllegalArgumentException("Invalid subscription ID.");
        }
        // The Android catalog synchronizes its mutations on this same store object.
        // Hold it through file cleanup so a confirmed re-subscription cannot race
        // with deletion of newly acquired files for the same opaque episode IDs.
        synchronized (catalog) {
            catalog.archiveSubscription(subscriptionId);
            List<String> mediaIds = catalog.listArchivedMediaIds(subscriptionId);
            if (mediaIds == null) {
                return false;
            }
            if (keepDownloads) {
                return true;
            }
            for (String mediaId : mediaIds) {
                files.delete(mediaId);
            }
            return catalog.deleteArchivedSubscription(subscriptionId);
        }
    }
}
