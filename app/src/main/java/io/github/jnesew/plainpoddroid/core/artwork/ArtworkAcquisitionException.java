package io.github.jnesew.plainpoddroid.core.artwork;

import java.io.IOException;

public final class ArtworkAcquisitionException extends IOException {
    public enum Reason {
        HTTP_STATUS, TOO_MANY_REDIRECTS, INVALID_REDIRECT, REDIRECT_LOOP,
        UNSUPPORTED_CONTENT_TYPE, UNSUPPORTED_ENCODING, INVALID_CONTENT_LENGTH,
        RAW_BODY_LIMIT, EXPANDED_BODY_LIMIT, EXPANSION_RATIO, MISSING_BODY,
        INVALID_COMPRESSION, INVALID_IMAGE
    }

    private final Reason reason;

    public ArtworkAcquisitionException(Reason reason) {
        super("Publisher artwork was rejected (" + reason.name() + ").");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
