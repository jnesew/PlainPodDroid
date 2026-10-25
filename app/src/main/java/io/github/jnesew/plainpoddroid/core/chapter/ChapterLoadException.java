package io.github.jnesew.plainpoddroid.core.chapter;

import java.io.IOException;

public final class ChapterLoadException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Reason { NO_REFERENCE, CATALOG_FAILURE }
    private final Reason reason;

    ChapterLoadException(Reason reason) {
        super("Chapter load failed: " + reason.name() + ".");
        this.reason = reason;
    }
    public Reason getReason() { return reason; }
}
