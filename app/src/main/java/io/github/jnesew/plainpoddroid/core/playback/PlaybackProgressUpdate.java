package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validated, URL-free state submitted to the app-private catalog. */
public final class PlaybackProgressUpdate {
    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    private final String mediaId;
    private final long positionMillis;
    private final boolean markPlayed;
    private final long updatedAtMillis;

    public PlaybackProgressUpdate(
            String mediaId,
            long positionMillis,
            boolean markPlayed,
            long updatedAtMillis) {
        this.mediaId = requireMediaId(mediaId);
        if (positionMillis < 0L
                || positionMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS) {
            throw new IllegalArgumentException("Invalid playback position.");
        }
        if (updatedAtMillis < 0L) {
            throw new IllegalArgumentException("Invalid playback update time.");
        }
        this.positionMillis = positionMillis;
        this.markPlayed = markPlayed;
        this.updatedAtMillis = updatedAtMillis;
    }

    public String getMediaId() {
        return mediaId;
    }

    public long getPositionMillis() {
        return positionMillis;
    }

    public boolean shouldMarkPlayed() {
        return markPlayed;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    boolean hasSameProgress(PlaybackProgressUpdate other) {
        return other != null
                && mediaId.equals(other.mediaId)
                && positionMillis == other.positionMillis
                && markPlayed == other.markPlayed;
    }

    static String requireMediaId(String mediaId) {
        Objects.requireNonNull(mediaId, "mediaId");
        if (!MEDIA_ID.matcher(mediaId).matches()) {
            throw new IllegalArgumentException("Invalid playback media ID.");
        }
        return mediaId;
    }
}
