package io.github.jnesew.plainpoddroid.core.download;

import java.util.function.BooleanSupplier;

/** Explicit episode download operations exposed to UI coordination code. */
public interface EpisodeDownloadActions {
    EpisodeDownloadResult download(
            String mediaId,
            BooleanSupplier cancellationRequested) throws EpisodeDownloadException;

    void delete(String mediaId) throws EpisodeDownloadException;

    long getStorageUsageBytes() throws EpisodeDownloadException;
}
