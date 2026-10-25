package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshException;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefresher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ScheduledRefreshRunnerTest {
    private static final long NOW = 100L * 60L * 60L * 1_000L;

    @Test public void preparationRefreshesRecentShowsWithoutBypassingDeduplicationOrFailureBackoff() throws Exception {
        FakeStore store = new FakeStore();
        store.candidates.add(new ScheduledRefreshCandidate(1L, NOW - 16 * 60_000L, 0, 0));
        store.candidates.add(new ScheduledRefreshCandidate(2L, NOW - 14 * 60_000L, 0, 0));
        store.candidates.add(new ScheduledRefreshCandidate(3L, NOW - 4 * 3_600_000L, 3, NOW - 20 * 60_000L));
        store.candidates.add(new ScheduledRefreshCandidate(4L, NOW - 4 * 3_600_000L, 3, NOW - 2 * 3_600_000L));
        FakeRefresher refresher = new FakeRefresher();
        var result = new ScheduledRefreshRunner(refresher, store).runPreparation(NOW, () -> false);
        assertEquals(List.of(1L, 4L), refresher.requested);
        assertEquals(2, result.getSucceeded()); assertEquals(1, result.getDeferred());
        assertTrue(store.failedIds.isEmpty());
    }

    @Test public void cancelledPreparationCannotPenalizeFeedsOrStartTheNextOne() throws Exception {
        FakeStore store = new FakeStore(); store.candidates.add(healthyDue(1)); store.candidates.add(healthyDue(2));
        AtomicBoolean cancelled = new AtomicBoolean();
        SubscriptionRefresher refresher = id -> {
            cancelled.set(true);
            throw new SubscriptionRefreshException(SubscriptionRefreshException.Reason.FETCH_FAILED, "redacted");
        };
        var result = new ScheduledRefreshRunner(refresher, store).runPreparation(NOW, cancelled::get);
        assertTrue(result.isCancelled()); assertEquals(1, result.getAttempted());
        assertEquals(0, result.getFailed()); assertTrue(store.failedIds.isEmpty());
    }

    @Test public void cancellationDuringFailureDoesNotPenalizeFeedOrContinue() throws Exception {
        FakeStore store = new FakeStore(); store.candidates.add(healthyDue(1L)); store.candidates.add(healthyDue(2L));
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeRefresher refresher = new FakeRefresher();
        refresher.failures.put(1L, SubscriptionRefreshException.Reason.FETCH_FAILED);
        SubscriptionRefresher interrupting = id -> {
            cancelled.set(true); refresher.refresh(id);
        };
        ScheduledRefreshRunResult result = new ScheduledRefreshRunner(interrupting, store)
                .run(RefreshInterval.ONE_HOUR, NOW, cancelled::get);
        assertTrue(result.isCancelled());
        assertEquals(0, result.getFailed()); assertTrue(store.failedIds.isEmpty());
        assertEquals(List.of(1L), refresher.requested);
    }

    @Test
    public void runsOnlyDueIdsAndPersistsOnlyActionableFailures() throws Exception {
        FakeStore store = new FakeStore();
        store.candidates.add(healthyDue(1L));
        store.candidates.add(healthyDue(2L));
        store.candidates.add(healthyDue(3L));
        store.candidates.add(healthyDue(4L));
        store.candidates.add(new ScheduledRefreshCandidate(5L, NOW, 0, 0L));
        FakeRefresher refresher = new FakeRefresher();
        refresher.failures.put(2L, SubscriptionRefreshException.Reason.FETCH_FAILED);
        refresher.failures.put(3L, SubscriptionRefreshException.Reason.SUBSCRIPTION_NOT_FOUND);
        refresher.failures.put(4L, SubscriptionRefreshException.Reason.ALREADY_RUNNING);

        ScheduledRefreshRunResult result = new ScheduledRefreshRunner(refresher, store)
                .run(RefreshInterval.ONE_HOUR, NOW, () -> false);

        assertEquals(List.of(1L, 2L, 3L, 4L), refresher.requested);
        assertEquals(4, result.getAttempted());
        assertEquals(1, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getDeferred());
        assertEquals(1, result.getDisappeared());
        assertFalse(result.isCancelled());
        assertEquals(List.of(2L), store.failedIds);
        assertEquals(List.of(ScheduledRefreshFailure.FETCH_FAILED), store.failures);
        assertEquals(1, store.newFailureCounts.get(0).intValue());
        assertEquals(NOW, store.attemptTimes.get(0).longValue());
    }

    @Test
    public void cancellationStopsBeforeStartingAnotherFeed() throws Exception {
        FakeStore store = new FakeStore();
        store.candidates.add(healthyDue(1L));
        store.candidates.add(healthyDue(2L));
        AtomicBoolean cancel = new AtomicBoolean();
        FakeRefresher refresher = new FakeRefresher();
        refresher.afterRefresh = () -> cancel.set(true);

        ScheduledRefreshRunResult result = new ScheduledRefreshRunner(refresher, store)
                .run(RefreshInterval.ONE_HOUR, NOW, cancel::get);

        assertEquals(List.of(1L), refresher.requested);
        assertEquals(1, result.getSucceeded());
        assertTrue(result.isCancelled());
    }

    @Test
    public void staleFailureRecordIsDeferredInsteadOfOverwritingNewerState()
            throws Exception {
        FakeStore store = new FakeStore();
        store.candidates.add(healthyDue(7L));
        store.acceptFailure = false;
        FakeRefresher refresher = new FakeRefresher();
        refresher.failures.put(7L, SubscriptionRefreshException.Reason.INVALID_FEED);

        ScheduledRefreshRunResult result = new ScheduledRefreshRunner(refresher, store)
                .run(RefreshInterval.ONE_HOUR, NOW, () -> false);

        assertEquals(0, result.getFailed());
        assertEquals(1, result.getDeferred());
    }

    @Test
    public void candidateLimitAndDisabledRunFailBeforeAnyRefresh() {
        FakeStore store = new FakeStore();
        for (int index = 0; index <= SecurityLimits.MAX_SUBSCRIPTIONS; index++) {
            store.candidates.add(healthyDue(index + 1L));
        }
        FakeRefresher refresher = new FakeRefresher();
        ScheduledRefreshRunner runner = new ScheduledRefreshRunner(refresher, store);

        assertThrows(
                IOException.class,
                () -> runner.run(RefreshInterval.ONE_HOUR, NOW, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(RefreshInterval.OFF, NOW, () -> false));
        assertTrue(refresher.requested.isEmpty());
    }

    private static ScheduledRefreshCandidate healthyDue(long id) {
        return new ScheduledRefreshCandidate(
                id,
                NOW - RefreshInterval.ONE_HOUR.getIntervalMillis(),
                0,
                0L);
    }

    private static final class FakeRefresher implements SubscriptionRefresher {
        private final List<Long> requested = new ArrayList<>();
        private final Map<Long, SubscriptionRefreshException.Reason> failures = new HashMap<>();
        private Runnable afterRefresh = () -> { };

        @Override
        public void refresh(long subscriptionId) throws SubscriptionRefreshException {
            requested.add(subscriptionId);
            SubscriptionRefreshException.Reason reason = failures.get(subscriptionId);
            if (reason != null) {
                throw new SubscriptionRefreshException(reason, "fixed redacted failure");
            }
            afterRefresh.run();
        }
    }

    private static final class FakeStore implements ScheduledRefreshStateStore {
        private final List<ScheduledRefreshCandidate> candidates = new ArrayList<>();
        private final List<Long> failedIds = new ArrayList<>();
        private final List<Integer> newFailureCounts = new ArrayList<>();
        private final List<Long> attemptTimes = new ArrayList<>();
        private final List<ScheduledRefreshFailure> failures = new ArrayList<>();
        private boolean acceptFailure = true;

        @Override
        public List<ScheduledRefreshCandidate> listScheduledRefreshCandidates() {
            return List.copyOf(candidates);
        }

        @Override
        public boolean recordScheduledRefreshFailure(
                ScheduledRefreshCandidate candidate,
                int newConsecutiveFailures,
                long attemptedAtMillis,
                ScheduledRefreshFailure failure) {
            failedIds.add(candidate.getSubscriptionId());
            newFailureCounts.add(newConsecutiveFailures);
            attemptTimes.add(attemptedAtMillis);
            failures.add(failure);
            return acceptFailure;
        }
    }
}
