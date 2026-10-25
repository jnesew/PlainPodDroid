package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.Objects;

/** Local completion metadata. Zero time/bytes mean unknown for pre-migration downloads. */
public record DownloadedEpisode(CatalogEpisodeSummary episode,
        long completedAtMillis, long downloadedBytes) {
    public DownloadedEpisode {
        Objects.requireNonNull(episode, "episode");
        if (!episode.isDownloaded() || episode.getPodcast() == null
                || completedAtMillis < 0L || downloadedBytes < 0L
                || downloadedBytes > SecurityLimits.MAX_DOWNLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid downloaded episode.");
        }
    }
}
