package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshException;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefresher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Refreshes every stored subscription once without exposing its URL or schedule state. */
public final class ManualRefreshAllRunner {
    private final SubscriptionRefresher refresher;
    private final ScheduledRefreshStateStore stateStore;

    public ManualRefreshAllRunner(
            SubscriptionRefresher refresher,
            ScheduledRefreshStateStore stateStore) {
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public ManualRefreshAllResult run(BooleanSupplier cancellationRequested)
            throws IOException {
        return runOnly(null,cancellationRequested);
    }

    /** Retry only previously failed IDs which are still active subscriptions. */
    ManualRefreshAllResult runOnly(List<Long> requested, BooleanSupplier cancellationRequested)
            throws IOException {
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Set<Long> retry = null;
        if (requested != null) {
            if (requested.size() > SecurityLimits.MAX_SUBSCRIPTIONS) throw new IOException("Invalid retry list.");
            retry = new HashSet<>();
            for (Long id : requested) {
                if (id == null || id < 1L || !retry.add(id)) throw new IOException("Invalid retry list.");
            }
        }
        List<Long> subscriptionIds = validatedSubscriptionIds(stateStore.listScheduledRefreshCandidates());
        if (retry != null) subscriptionIds.retainAll(retry);
        List<ManualRefreshAllResult.FailedPodcast> failures = new ArrayList<>();

        int attempted = 0;
        int succeeded = 0;
        int subscriptionNotFound = 0;
        int alreadyRunning = 0;
        int fetchFailed = 0;
        int invalidFeed = 0;
        int storageFailed = 0;
        boolean cancelled = false;
        for (long subscriptionId : subscriptionIds) {
            if (cancellationRequested.getAsBoolean()) {
                cancelled = true;
                break;
            }
            attempted++;
            try {
                refresher.refresh(subscriptionId);
                succeeded++;
            } catch (SubscriptionRefreshException exception) {
                failures.add(new ManualRefreshAllResult.FailedPodcast(subscriptionId,exception.getReason()));
                switch (exception.getReason()) {
                    case SUBSCRIPTION_NOT_FOUND -> subscriptionNotFound++;
                    case ALREADY_RUNNING -> alreadyRunning++;
                    case FETCH_FAILED, LOGIN_REQUIRED, ACCESS_DENIED -> fetchFailed++;
                    case INVALID_FEED -> invalidFeed++;
                    case STORAGE_FAILED -> storageFailed++;
                }
            }
        }
        return new ManualRefreshAllResult(
                subscriptionIds.size(),
                attempted,
                succeeded,
                subscriptionNotFound,
                alreadyRunning,
                fetchFailed,
                invalidFeed,
                storageFailed,
                cancelled,failures);
    }

    private static List<Long> validatedSubscriptionIds(
            List<ScheduledRefreshCandidate> candidates) throws IOException {
        if (candidates == null || candidates.size() > SecurityLimits.MAX_SUBSCRIPTIONS) {
            throw new IOException("Refresh-all candidate list is invalid.");
        }
        List<Long> result = new ArrayList<>(candidates.size());
        Set<Long> seen = new HashSet<>();
        for (ScheduledRefreshCandidate candidate : candidates) {
            if (candidate == null || !seen.add(candidate.getSubscriptionId())) {
                throw new IOException("Refresh-all candidate list is invalid.");
            }
            result.add(candidate.getSubscriptionId());
        }
        return result;
    }
}
