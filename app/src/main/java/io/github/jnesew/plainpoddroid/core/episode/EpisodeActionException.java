package io.github.jnesew.plainpoddroid.core.episode;

import java.io.IOException;
import java.util.Objects;

public final class EpisodeActionException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_MEDIA_ID,
        EPISODE_NOT_FOUND,
        STORAGE_FAILURE
    }

    private final Reason reason;

    public EpisodeActionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason getReason() {
        return reason;
    }
}
