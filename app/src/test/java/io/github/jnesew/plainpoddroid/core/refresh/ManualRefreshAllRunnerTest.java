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

public final class ManualRefreshAllRunnerTest {
    @Test
    public void refreshesEveryInternalIdAndClassifiesTypedFailures() throws Exception {
        FakeStore store = new FakeStore(candidates(1L, 2L, 3L, 4L, 5L, 6L));
        FakeRefresher refresher = new FakeRefresher();
        refresher.failures.put(2L, SubscriptionRefreshException.Reason.SUBSCRIPTION_NOT_FOUND);
        refresher.failures.put(3L, SubscriptionRefreshException.Reason.ALREADY_RUNNING);
        refresher.failures.put(4L, SubscriptionRefreshException.Reason.FETCH_FAILED);
        refresher.failures.put(5L, SubscriptionRefreshException.Reason.INVALID_FEED);
        refresher.failures.put(6L, SubscriptionRefreshException.Reason.STORAGE_FAILED);

        ManualRefreshAllResult result =
                new ManualRefreshAllRunner(refresher, store).run(() -> false);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), refresher.ids);
        assertEquals(6, result.getCandidateCount());
        assertEquals(6, result.getAttempted());
        assertEquals(1, result.getSucceeded());
        assertEquals(1, result.getSubscriptionNotFound());
        assertEquals(1, result.getAlreadyRunning());
        assertEquals(1, result.getFetchFailed());
        assertEquals(1, result.getInvalidFeed());
        assertEquals(1, result.getStorageFailed());
        assertEquals(3, result.getFailed());
        assertEquals(2, result.getUnavailable());
        assertFalse(result.isCancelled());
        assertEquals(0, store.recordCalls);
        assertEquals(List.of(2L,3L,4L,5L,6L),result.getFailureDetails().stream()
                .map(ManualRefreshAllResult.FailedPodcast::subscriptionId).toList());
        assertEquals(SubscriptionRefreshException.Reason.FETCH_FAILED,result.getFailureDetails().get(2).reason());
        assertThrows(UnsupportedOperationException.class,()->result.getFailureDetails().clear());
    }

    @Test
    public void manualRunIgnoresScheduleAndBackoffTiming() throws Exception {
        ScheduledRefreshCandidate backedOff = new ScheduledRefreshCandidate(
                7L,
                Long.MAX_VALUE,
                5,
                Long.MAX_VALUE);
        FakeRefresher refresher = new FakeRefresher();

        ManualRefreshAllResult result = new ManualRefreshAllRunner(
                refresher,
                new FakeStore(List.of(backedOff))).run(() -> false);

        assertEquals(List.of(7L), refresher.ids);
        assertEquals(1, result.getSucceeded());
    }

    @Test
    public void cancellationStopsBetweenFeedsAndReturnsPartialCounts() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeRefresher refresher = new FakeRefresher();
        refresher.afterRefresh = () -> cancelled.set(true);

        ManualRefreshAllResult result = new ManualRefreshAllRunner(
                refresher,
                new FakeStore(candidates(1L, 2L, 3L))).run(cancelled::get);

        assertEquals(List.of(1L), refresher.ids);
        assertEquals(3, result.getCandidateCount());
        assertEquals(1, result.getAttempted());
        assertEquals(1, result.getSucceeded());
        assertTrue(result.isCancelled());
    }

    @Test
    public void invalidCandidateListsFailBeforeAnyNetworkWork() {
        assertInvalid(null);

        List<ScheduledRefreshCandidate> oversized = new ArrayList<>();
        for (long id = 1L; id <= SecurityLimits.MAX_SUBSCRIPTIONS + 1L; id++) {
            oversized.add(candidate(id));
        }
        assertInvalid(oversized);
        assertInvalid(List.of(candidate(1L), candidate(1L)));

        List<ScheduledRefreshCandidate> withNull = new ArrayList<>();
        withNull.add(candidate(1L));
        withNull.add(null);
        assertInvalid(withNull);
    }

    @Test
    public void storageListingFailurePropagatesBeforeAnyRefresh() {
        FakeRefresher refresher = new FakeRefresher();
        ScheduledRefreshStateStore failingStore = new ScheduledRefreshStateStore() {
            @Override
            public List<ScheduledRefreshCandidate> listScheduledRefreshCandidates()
                    throws IOException {
                throw new IOException("private database detail");
            }

            @Override
            public boolean recordScheduledRefreshFailure(
                    ScheduledRefreshCandidate candidate,
                    int newConsecutiveFailures,
                    long attemptedAtMillis,
                    ScheduledRefreshFailure failure) {
                throw new AssertionError("Must not record manual failures.");
            }
        };

        assertThrows(IOException.class, () ->
                new ManualRefreshAllRunner(refresher, failingStore).run(() -> false));
        assertTrue(refresher.ids.isEmpty());
    }

    @Test public void retryRevalidatesMembershipAndNeverFetchesAnArchivedOrUnrequestedFeed() throws Exception {
        FakeRefresher refresher=new FakeRefresher();
        var runner=new ManualRefreshAllRunner(refresher,new FakeStore(candidates(1L,3L,4L)));
        var result=runner.runOnly(List.of(2L,3L),()->false);
        assertEquals(List.of(3L),refresher.ids);
        assertEquals(1,result.getCandidateCount());
        assertTrue(result.getFailureDetails().isEmpty());
    }

    @Test public void invalidRetryListsFailBeforeAnyNetworkWork() {
        FakeRefresher refresher=new FakeRefresher();
        var runner=new ManualRefreshAllRunner(refresher,new FakeStore(candidates(1L)));
        assertThrows(IOException.class,()->runner.runOnly(List.of(1L,1L),()->false));
        assertThrows(IOException.class,()->runner.runOnly(List.of(0L),()->false));
        assertThrows(IOException.class,()->runner.runOnly(java.util.Arrays.asList(1L,null),()->false));
        assertThrows(IOException.class,()->runner.runOnly(java.util.Collections.nCopies(
                SecurityLimits.MAX_SUBSCRIPTIONS+1,1L),()->false));
        assertTrue(refresher.ids.isEmpty());
    }

    private static void assertInvalid(List<ScheduledRefreshCandidate> candidates) {
        FakeRefresher refresher = new FakeRefresher();
        assertThrows(IOException.class, () -> new ManualRefreshAllRunner(
                refresher,
                new FakeStore(candidates)).run(() -> false));
        assertTrue(refresher.ids.isEmpty());
    }

    private static List<ScheduledRefreshCandidate> candidates(long... ids) {
        List<ScheduledRefreshCandidate> result = new ArrayList<>();
        for (long id : ids) {
            result.add(candidate(id));
        }
        return result;
    }

    private static ScheduledRefreshCandidate candidate(long id) {
        return new ScheduledRefreshCandidate(id, 100L, 0, 0L);
    }

    private static final class FakeRefresher implements SubscriptionRefresher {
        private final List<Long> ids = new ArrayList<>();
        private final Map<Long, SubscriptionRefreshException.Reason> failures =
                new HashMap<>();
        private Runnable afterRefresh = () -> { };

        @Override
        public void refresh(long subscriptionId) throws SubscriptionRefreshException {
            ids.add(subscriptionId);
            SubscriptionRefreshException.Reason reason = failures.get(subscriptionId);
            afterRefresh.run();
            if (reason != null) {
                throw new SubscriptionRefreshException(reason, "private failure detail");
            }
        }
    }

    private static final class FakeStore implements ScheduledRefreshStateStore {
        private final List<ScheduledRefreshCandidate> candidates;
        private int recordCalls;

        private FakeStore(List<ScheduledRefreshCandidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public List<ScheduledRefreshCandidate> listScheduledRefreshCandidates() {
            return candidates;
        }

        @Override
        public boolean recordScheduledRefreshFailure(
                ScheduledRefreshCandidate candidate,
                int newConsecutiveFailures,
                long attemptedAtMillis,
                ScheduledRefreshFailure failure) {
            recordCalls++;
            return true;
        }
    }
}
