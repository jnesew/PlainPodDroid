package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;

public final class EpisodeDownloadException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_SELECTION,
        EPISODE_NOT_FOUND,
        ALREADY_ACTIVE,
        ALREADY_DOWNLOADED,
        CANCELLED,
        FETCH_FAILED,
        LOGIN_REQUIRED,
        ACCESS_DENIED,
        HTTP_STATUS,
        INVALID_REDIRECT,
        TOO_MANY_REDIRECTS,
        REDIRECT_LOOP,
        UNSUPPORTED_RESPONSE,
        INVALID_LENGTH,
        QUOTA_EXCEEDED,
        TARGET_CHANGED,
        OFFLINE_MODE,
        PLAYBACK_IN_USE,
        STORAGE_FAILED
    }

    private final Reason reason;

    EpisodeDownloadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
