package io.github.jnesew.plainpoddroid.core.opml;

/** Fixed diagnostics: never include publisher-controlled OPML text or URLs. */
public final class OpmlParseException extends Exception {
    public enum Reason {
        INPUT_LIMIT, INVALID_ENCODING, DTD_FORBIDDEN, STRUCTURE,
        DEPTH_LIMIT, OUTLINE_LIMIT, CANDIDATE_LIMIT, ATTRIBUTE_LIMIT, MALFORMED_XML,
        PARSER_CONFIGURATION
    }

    private final Reason reason;

    public OpmlParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
