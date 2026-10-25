package io.github.jnesew.plainpoddroid.core.playback;

import java.util.Objects;

public final class PlaybackResolution {
    public enum BlockReason {
        INVALID_MEDIA_ID,
        UNKNOWN_EPISODE,
        DOWNLOAD_REQUIRED,
        PARTIAL_DOWNLOAD,
        INVALID_DOWNLOAD,
        INVALID_REMOTE_SOURCE,
        STORAGE_ERROR
    }

    private final PlaybackSource source;
    private final BlockReason blockReason;

    private PlaybackResolution(PlaybackSource source, BlockReason blockReason) {
        this.source = source;
        this.blockReason = blockReason;
    }

    public static PlaybackResolution allowed(PlaybackSource source) {
        return new PlaybackResolution(Objects.requireNonNull(source, "source"), null);
    }

    public static PlaybackResolution blocked(BlockReason reason) {
        return new PlaybackResolution(null, Objects.requireNonNull(reason, "reason"));
    }

    public boolean isAllowed() {
        return source != null;
    }

    public PlaybackSource getSource() {
        if (source == null) {
            throw new IllegalStateException("Blocked playback has no source");
        }
        return source;
    }

    public BlockReason getBlockReason() {
        if (blockReason == null) {
            throw new IllegalStateException("Allowed playback has no block reason");
        }
        return blockReason;
    }
}
