package io.github.jnesew.plainpoddroid.core.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PlaybackBatchResolution {
    private final List<PlaybackSource> sources;
    private final int blockedIndex;
    private final PlaybackResolution.BlockReason blockReason;

    private PlaybackBatchResolution(
            List<PlaybackSource> sources,
            int blockedIndex,
            PlaybackResolution.BlockReason blockReason) {
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        this.blockedIndex = blockedIndex;
        this.blockReason = blockReason;
    }

    public static PlaybackBatchResolution allowed(List<PlaybackSource> sources) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("A playback batch may not be empty");
        }
        return new PlaybackBatchResolution(sources, -1, null);
    }

    public static PlaybackBatchResolution blocked(
            int index,
            PlaybackResolution.BlockReason reason) {
        if (index < 0) {
            throw new IllegalArgumentException("Blocked index must be non-negative");
        }
        return new PlaybackBatchResolution(
                Collections.emptyList(),
                index,
                Objects.requireNonNull(reason, "reason"));
    }

    public boolean isAllowed() {
        return blockReason == null;
    }

    public List<PlaybackSource> getSources() {
        return sources;
    }

    public int getBlockedIndex() {
        if (isAllowed()) {
            throw new IllegalStateException("Allowed batch has no blocked index");
        }
        return blockedIndex;
    }

    public PlaybackResolution.BlockReason getBlockReason() {
        if (blockReason == null) {
            throw new IllegalStateException("Allowed batch has no block reason");
        }
        return blockReason;
    }
}
