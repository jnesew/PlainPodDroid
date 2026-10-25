package io.github.jnesew.plainpoddroid.core.chapter;

import java.io.IOException;

/** Fixed diagnostics deliberately omit publisher URLs and underlying network messages. */
public final class ChapterAcquisitionException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Reason {
        TOO_MANY_REDIRECTS, REDIRECT_LOOP, INVALID_REDIRECT, HTTP_STATUS,
        INVALID_CONTENT_LENGTH, RAW_BODY_LIMIT, EXPANDED_BODY_LIMIT,
        EXPANSION_RATIO, UNSUPPORTED_ENCODING, INVALID_COMPRESSION,
        UNSUPPORTED_CONTENT_TYPE, MISSING_BODY
    }
    private final Reason reason;

    ChapterAcquisitionException(Reason reason) {
        super("Chapter acquisition failed: " + reason.name() + ".");
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
