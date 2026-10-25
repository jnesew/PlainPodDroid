package io.github.jnesew.plainpoddroid.core.refresh;

public final class ScheduledRefreshRunResult {
    private final int attempted;
    private final int succeeded;
    private final int failed;
    private final int deferred;
    private final int disappeared;
    private final boolean cancelled;

    ScheduledRefreshRunResult(
            int attempted,
            int succeeded,
            int failed,
            int deferred,
            int disappeared,
            boolean cancelled) {
        this.attempted = attempted;
        this.succeeded = succeeded;
        this.failed = failed;
        this.deferred = deferred;
        this.disappeared = disappeared;
        this.cancelled = cancelled;
    }

    public int getAttempted() {
        return attempted;
    }

    public int getSucceeded() {
        return succeeded;
    }

    public int getFailed() {
        return failed;
    }

    public int getDeferred() {
        return deferred;
    }

    public int getDisappeared() {
        return disappeared;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
