package io.github.jnesew.plainpoddroid.core.chapter;

public final class ChapterParseException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INPUT_LIMIT,
        DEPTH_LIMIT,
        TOKEN_LIMIT,
        CHAPTER_LIMIT,
        FIELD_LIMIT,
        UNSUPPORTED_VERSION,
        INVALID_SCHEMA,
        MALFORMED_JSON
    }

    private final Reason reason;

    ChapterParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    ChapterParseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
