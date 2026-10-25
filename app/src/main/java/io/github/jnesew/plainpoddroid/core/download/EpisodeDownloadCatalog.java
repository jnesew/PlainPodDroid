package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;

public interface EpisodeDownloadCatalog {
    EpisodeDownloadTarget findEpisodeDownloadTarget(String mediaId) throws IOException;

    /** Local deletion also applies to downloads retained in an archive. */
    default EpisodeDownloadTarget findEpisodeDeletionTarget(String mediaId)
            throws IOException {
        return findEpisodeDownloadTarget(mediaId);
    }

    boolean markEpisodeDownloadComplete(EpisodeDownloadTarget target, long downloadedBytes)
            throws IOException;

    void markEpisodeDownloadAbsent(String mediaId) throws IOException;
}
