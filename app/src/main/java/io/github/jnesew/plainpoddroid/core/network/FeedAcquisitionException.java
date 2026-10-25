package io.github.jnesew.plainpoddroid.core.network;

import java.io.IOException;

public final class FeedAcquisitionException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        TOO_MANY_REDIRECTS,
        REDIRECT_LOOP,
        INVALID_REDIRECT,
        HTTP_STATUS,
        LOGIN_REQUIRED,
        ACCESS_DENIED,
        INVALID_CONTENT_LENGTH,
        RAW_BODY_LIMIT,
        EXPANDED_BODY_LIMIT,
        EXPANSION_RATIO,
        UNSUPPORTED_ENCODING,
        INVALID_COMPRESSION,
        UNSUPPORTED_CONTENT_TYPE,
        MISSING_BODY
    }

    private final Reason reason;

    FeedAcquisitionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    FeedAcquisitionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
