package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

public final class ScheduledRefreshCandidate {
    private final long subscriptionId;
    private final long updatedAtMillis;
    private final int consecutiveFailures;
    private final long lastAttemptAtMillis;

    public ScheduledRefreshCandidate(
            long subscriptionId,
            long updatedAtMillis,
            int consecutiveFailures,
            long lastAttemptAtMillis) {
        if (subscriptionId < 1L || updatedAtMillis < 0L || lastAttemptAtMillis < 0L
                || consecutiveFailures < 0
                || consecutiveFailures > SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES
                || (consecutiveFailures == 0 && lastAttemptAtMillis != 0L)
                || (consecutiveFailures > 0 && lastAttemptAtMillis == 0L)) {
            throw new IllegalArgumentException("Invalid scheduled refresh candidate.");
        }
        this.subscriptionId = subscriptionId;
        this.updatedAtMillis = updatedAtMillis;
        this.consecutiveFailures = consecutiveFailures;
        this.lastAttemptAtMillis = lastAttemptAtMillis;
    }

    public long getSubscriptionId() {
        return subscriptionId;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public long getLastAttemptAtMillis() {
        return lastAttemptAtMillis;
    }
}
