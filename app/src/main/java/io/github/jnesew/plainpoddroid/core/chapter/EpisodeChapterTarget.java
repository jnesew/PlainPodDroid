package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.util.Objects;

public final class EpisodeChapterTarget {
    private final String mediaId;
    private final CanonicalHttpsUrl url;

    public EpisodeChapterTarget(String mediaId, CanonicalHttpsUrl url) {
        if (mediaId == null || !mediaId.matches("episode-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid chapter episode ID.");
        }
        this.mediaId = mediaId;
        this.url = Objects.requireNonNull(url, "url");
    }
    public String getMediaId() { return mediaId; }
    public CanonicalHttpsUrl getUrl() { return url; }
}
