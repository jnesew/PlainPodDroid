package io.github.jnesew.plainpoddroid.core.subscription;

public final class SubscriptionRefreshException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        SUBSCRIPTION_NOT_FOUND,
        ALREADY_RUNNING,
        FETCH_FAILED,
        LOGIN_REQUIRED,
        ACCESS_DENIED,
        INVALID_FEED,
        STORAGE_FAILED
    }

    private final Reason reason;

    public SubscriptionRefreshException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
