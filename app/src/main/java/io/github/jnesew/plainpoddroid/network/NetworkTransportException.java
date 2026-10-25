package io.github.jnesew.plainpoddroid.network;

import java.io.IOException;

public final class NetworkTransportException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        CONCURRENCY_LIMIT,
        INTERRUPTED,
        REQUEST_FAILED
    }

    private final Reason reason;

    NetworkTransportException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
