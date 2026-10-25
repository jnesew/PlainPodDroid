package io.github.jnesew.plainpoddroid.core.feed;

public final class FeedParseException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INPUT_LIMIT,
        DTD_FORBIDDEN,
        ENTITY_FORBIDDEN,
        DEPTH_LIMIT,
        ATTRIBUTE_LIMIT,
        ATTRIBUTE_VALUE_LIMIT,
        EPISODE_LIMIT,
        TEXT_LIMIT,
        FIELD_LIMIT,
        UNSUPPORTED_DOCUMENT,
        MALFORMED_XML,
        PARSER_CONFIGURATION
    }

    private final Reason reason;

    FeedParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    FeedParseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
