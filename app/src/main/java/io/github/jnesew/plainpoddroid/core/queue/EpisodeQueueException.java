package io.github.jnesew.plainpoddroid.core.queue;

import java.io.IOException;
import java.util.Objects;

public final class EpisodeQueueException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_MEDIA_ID,
        EPISODE_NOT_FOUND,
        QUEUE_FULL,
        NOT_QUEUED,
        INVALID_POSITION,
        CORRUPT_STATE,
        STORAGE_FAILURE
    }

    private final Reason reason;

    public EpisodeQueueException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason getReason() {
        return reason;
    }
}
