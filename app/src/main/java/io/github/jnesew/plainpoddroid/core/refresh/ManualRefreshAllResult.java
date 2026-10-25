package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class ManualRefreshAllResult {
    public record FailedPodcast(long subscriptionId, SubscriptionRefreshException.Reason reason) {
        public FailedPodcast {
            if (subscriptionId < 1L) throw new IllegalArgumentException("Invalid subscription.");
            Objects.requireNonNull(reason,"reason");
        }
    }
    private final List<FailedPodcast> failureDetails;
    private final int candidateCount;
    private final int attempted;
    private final int succeeded;
    private final int subscriptionNotFound;
    private final int alreadyRunning;
    private final int fetchFailed;
    private final int invalidFeed;
    private final int storageFailed;
    private final boolean cancelled;

    ManualRefreshAllResult(
            int candidateCount,
            int attempted,
            int succeeded,
            int subscriptionNotFound,
            int alreadyRunning,
            int fetchFailed,
            int invalidFeed,
            int storageFailed,
            boolean cancelled, List<FailedPodcast> failureDetails) {
        int classified = succeeded + subscriptionNotFound + alreadyRunning
                + fetchFailed + invalidFeed + storageFailed;
        if (candidateCount < 0 || attempted < 0 || attempted > candidateCount
                || succeeded < 0 || subscriptionNotFound < 0 || alreadyRunning < 0
                || fetchFailed < 0 || invalidFeed < 0 || storageFailed < 0
                || classified != attempted
                || (!cancelled && attempted != candidateCount)) {
            throw new IllegalArgumentException("Invalid refresh-all result.");
        }
        Objects.requireNonNull(failureDetails,"failureDetails");
        if (candidateCount > SecurityLimits.MAX_SUBSCRIPTIONS || failureDetails.size() != attempted - succeeded)
            throw new IllegalArgumentException("Invalid failure details.");
        HashSet<Long> seen=new HashSet<>();
        for(FailedPodcast failure:failureDetails)
            if(failure==null||!seen.add(failure.subscriptionId())) throw new IllegalArgumentException("Invalid failure details.");
        this.failureDetails = List.copyOf(failureDetails);
        this.candidateCount = candidateCount;
        this.attempted = attempted;
        this.succeeded = succeeded;
        this.subscriptionNotFound = subscriptionNotFound;
        this.alreadyRunning = alreadyRunning;
        this.fetchFailed = fetchFailed;
        this.invalidFeed = invalidFeed;
        this.storageFailed = storageFailed;
        this.cancelled = cancelled;
    }

    public List<FailedPodcast> getFailureDetails() { return failureDetails; }

    public int getCandidateCount() {
        return candidateCount;
    }

    public int getAttempted() {
        return attempted;
    }

    public int getSucceeded() {
        return succeeded;
    }

    public int getSubscriptionNotFound() {
        return subscriptionNotFound;
    }

    public int getAlreadyRunning() {
        return alreadyRunning;
    }

    public int getFetchFailed() {
        return fetchFailed;
    }

    public int getInvalidFeed() {
        return invalidFeed;
    }

    public int getStorageFailed() {
        return storageFailed;
    }

    public int getFailed() {
        return fetchFailed + invalidFeed + storageFailed;
    }

    public int getUnavailable() {
        return subscriptionNotFound + alreadyRunning;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
