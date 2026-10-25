package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.Objects;
import java.util.regex.Pattern;

public final class EpisodeDownloadTarget {
    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    private final String mediaId;
    private final CanonicalHttpsUrl audioUrl;
    private final String mimeType;
    private final long declaredLength;
    private final boolean downloaded;

    public EpisodeDownloadTarget(
            String mediaId,
            CanonicalHttpsUrl audioUrl,
            String mimeType,
            long declaredLength,
            boolean downloaded) {
        this.audioUrl = Objects.requireNonNull(audioUrl, "audioUrl");
        String normalizedMimeType = ProgressiveAudioPolicy.normalizeMimeType(mimeType);
        if (mediaId == null
                || !MEDIA_ID.matcher(mediaId).matches()
                || normalizedMimeType == null
                || !ProgressiveAudioPolicy.isEligible(audioUrl, normalizedMimeType)
                || (declaredLength != AppPrivateDownloadStore.UNKNOWN_LENGTH
                        && (declaredLength < 0L
                                || declaredLength > SecurityLimits.MAX_DOWNLOAD_BYTES))) {
            throw new IllegalArgumentException("Invalid episode download target.");
        }
        this.mediaId = mediaId;
        this.mimeType = normalizedMimeType;
        this.declaredLength = declaredLength;
        this.downloaded = downloaded;
    }

    public String getMediaId() {
        return mediaId;
    }

    public CanonicalHttpsUrl getAudioUrl() {
        return audioUrl;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getDeclaredLength() {
        // Publishers use zero for an unavailable RSS size. Retain the stored hint
        // for catalog compare-and-set; the coordinator validates actual HTTP bytes.
        return declaredLength;
    }

    public boolean isDownloaded() {
        return downloaded;
    }
}
