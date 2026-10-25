package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshException;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefresher;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class ScheduledRefreshRunner {
    private final SubscriptionRefresher refresher;
    private final ScheduledRefreshStateStore stateStore;
    private final RefreshBackoffPolicy backoffPolicy;

    public ScheduledRefreshRunner(
            SubscriptionRefresher refresher,
            ScheduledRefreshStateStore stateStore) {
        this(refresher, stateStore, new RefreshBackoffPolicy());
    }

    ScheduledRefreshRunner(
            SubscriptionRefresher refresher,
            ScheduledRefreshStateStore stateStore,
            RefreshBackoffPolicy backoffPolicy) {
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.backoffPolicy = Objects.requireNonNull(backoffPolicy, "backoffPolicy");
    }

    public ScheduledRefreshRunResult run(
            RefreshInterval interval,
            long nowMillis,
            BooleanSupplier cancellationRequested) throws IOException {
        return run(interval, nowMillis, cancellationRequested, false);
    }

    public ScheduledRefreshRunResult runPreparation(long nowMillis, BooleanSupplier cancellationRequested)
            throws IOException {
        return run(RefreshInterval.ONE_HOUR, nowMillis, cancellationRequested, true);
    }

    private ScheduledRefreshRunResult run(RefreshInterval interval, long nowMillis,
            BooleanSupplier cancellationRequested, boolean preparation) throws IOException {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        if (!interval.isEnabled() || nowMillis < 1L) {
            throw new IllegalArgumentException("Invalid scheduled refresh run.");
        }
        List<ScheduledRefreshCandidate> candidates = Objects.requireNonNull(
                stateStore.listScheduledRefreshCandidates(),
                "scheduled refresh candidates");
        if (candidates.size() > SecurityLimits.MAX_SUBSCRIPTIONS) {
            throw new IOException("Scheduled refresh candidate limit was exceeded.");
        }

        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        int deferred = 0;
        int disappeared = 0;
        boolean cancelled = false;
        for (ScheduledRefreshCandidate candidate : candidates) {
            boolean due = preparation && candidate.getConsecutiveFailures() == 0
                    ? candidate.getUpdatedAtMillis() <= nowMillis - SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS
                    : backoffPolicy.isDue(candidate, interval, nowMillis);
            if (!due) {
                // Recently refreshed feeds are already prepared; skipping them is successful deduplication.
                // A failed feed still waiting for backoff leaves preparation incomplete.
                if (preparation && candidate.getConsecutiveFailures() > 0) deferred++;
                continue;
            }
            if (cancellationRequested.getAsBoolean()) {
                cancelled = true;
                break;
            }
            attempted++;
            try {
                refresher.refresh(candidate.getSubscriptionId());
                succeeded++;
            } catch (SubscriptionRefreshException exception) {
                if (cancellationRequested.getAsBoolean()) { cancelled = true; break; }
                switch (exception.getReason()) {
                    case SUBSCRIPTION_NOT_FOUND -> disappeared++;
                    case ALREADY_RUNNING -> deferred++;
                    case FETCH_FAILED, LOGIN_REQUIRED, ACCESS_DENIED, INVALID_FEED, STORAGE_FAILED -> {
                        int newFailureCount = Math.min(
                                candidate.getConsecutiveFailures() + 1,
                                SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES);
                        boolean recorded = stateStore.recordScheduledRefreshFailure(
                                candidate,
                                newFailureCount,
                                nowMillis,
                                failureFor(exception.getReason()));
                        if (recorded) {
                            failed++;
                        } else {
                            deferred++;
                        }
                    }
                }
            }
        }
        return new ScheduledRefreshRunResult(
                attempted,
                succeeded,
                failed,
                deferred,
                disappeared,
                cancelled);
    }

    private static ScheduledRefreshFailure failureFor(
            SubscriptionRefreshException.Reason reason) {
        return switch (reason) {
            case FETCH_FAILED -> ScheduledRefreshFailure.FETCH_FAILED;
            case LOGIN_REQUIRED -> ScheduledRefreshFailure.LOGIN_REQUIRED;
            case ACCESS_DENIED -> ScheduledRefreshFailure.ACCESS_DENIED;
            case INVALID_FEED -> ScheduledRefreshFailure.INVALID_FEED;
            case STORAGE_FAILED -> ScheduledRefreshFailure.STORAGE_FAILED;
            case SUBSCRIPTION_NOT_FOUND, ALREADY_RUNNING ->
                    throw new IllegalArgumentException("Failure is not persistent.");
        };
    }
}
