package io.github.jnesew.plainpoddroid.core.subscription;

import java.io.IOException;

public final class SubscriptionWorkflowException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        PENDING_PREVIEW_LIMIT,
        INVALID_OR_EXPIRED_CONFIRMATION
    }

    private final Reason reason;

    SubscriptionWorkflowException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
