package io.github.jnesew.plainpoddroid.core.download;

public final class EpisodeDownloadResult {
    private final String mediaId;
    private final long downloadedBytes;

    EpisodeDownloadResult(String mediaId, long downloadedBytes) {
        if (mediaId == null || mediaId.isEmpty() || downloadedBytes <= 0L) {
            throw new IllegalArgumentException("Invalid episode download result.");
        }
        this.mediaId = mediaId;
        this.downloadedBytes = downloadedBytes;
    }

    public String getMediaId() {
        return mediaId;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }
}
