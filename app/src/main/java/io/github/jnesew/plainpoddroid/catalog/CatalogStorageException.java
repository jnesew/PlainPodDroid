package io.github.jnesew.plainpoddroid.catalog;

import java.io.IOException;

public final class CatalogStorageException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        ALREADY_SUBSCRIBED,
        SUBSCRIPTION_NOT_FOUND,
        SUBSCRIPTION_LIMIT,
        EPISODE_LIMIT,
        STORAGE_FAILURE
    }

    private final Reason reason;

    CatalogStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
