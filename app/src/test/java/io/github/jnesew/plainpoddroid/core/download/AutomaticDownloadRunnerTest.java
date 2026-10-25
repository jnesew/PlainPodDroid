package io.github.jnesew.plainpoddroid.core.download;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AutomaticDownloadRunnerTest {
    @Test public void finalPreparationReservationAllowsOnlyOneCompleteTransfer() throws Exception {
        FakeQueue queue = new FakeQueue("one", "two", "three");
        FakeDownloads downloads = new FakeDownloads(queue);
        var runner = new AutomaticDownloadRunner(queue, downloads);
        assertTrue(runner.run(() -> false, 1));
        assertEquals(List.of("one"), downloads.attempted);
        assertEquals(1, downloads.completed);
        assertEquals(List.of("two", "three"), queue.pending);
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> runner.run(() -> false, 0));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> runner.run(() -> false, 4));
    }

    @Test
    public void boundedBatchLeavesMoreWorkForLater() throws Exception {
        FakeQueue queue = new FakeQueue("one", "two", "three", "four");
        FakeDownloads downloads = new FakeDownloads(queue);

        assertTrue(new AutomaticDownloadRunner(queue, downloads).run(() -> false));
        assertEquals(List.of("one", "two", "three"), downloads.attempted);
        assertEquals(List.of("four"), queue.pending);
        assertEquals(4, queue.maximumRequested);
    }

    @Test
    public void optOutBeforeAndDuringTransferPreventsCompletion() throws Exception {
        FakeQueue queue = new FakeQueue("one", "two");
        queue.disabled.add("one");
        FakeDownloads downloads = new FakeDownloads(queue);
        downloads.disableDuringTransfer = "two";

        assertFalse(new AutomaticDownloadRunner(queue, downloads).run(() -> false));
        assertEquals(List.of("two"), downloads.attempted);
        assertEquals(0, downloads.completed);
        assertTrue(queue.pending.isEmpty());
    }

    @Test
    public void manualDownloadCollisionDefersWithoutClearingMarker() throws Exception {
        FakeQueue queue = new FakeQueue("one");
        FakeDownloads downloads = new FakeDownloads(queue);
        downloads.failure = EpisodeDownloadException.Reason.ALREADY_ACTIVE;

        assertTrue(new AutomaticDownloadRunner(queue, downloads).run(() -> false));
        assertEquals(List.of("one"), queue.pending);
    }

    @Test
    public void transientFailureRetriesAtMostThreeTimes() throws Exception {
        FakeQueue queue = new FakeQueue("one");
        FakeDownloads downloads = new FakeDownloads(queue);
        downloads.failure = EpisodeDownloadException.Reason.FETCH_FAILED;
        AutomaticDownloadRunner runner = new AutomaticDownloadRunner(queue, downloads);

        assertTrue(runner.run(() -> false));
        assertTrue(runner.run(() -> false));
        assertFalse(runner.run(() -> false));
        assertEquals(List.of("one", "one", "one"), downloads.attempted);
        assertTrue(queue.pending.isEmpty());
    }

    @Test
    public void quotaFailureDoesNotLoopAndJobCancellationPreservesWork() throws Exception {
        FakeQueue queue = new FakeQueue("one", "two");
        FakeDownloads downloads = new FakeDownloads(queue);
        downloads.failure = EpisodeDownloadException.Reason.QUOTA_EXCEEDED;

        assertFalse(new AutomaticDownloadRunner(queue, downloads).run(() -> false));
        assertEquals(List.of("one", "two"), downloads.attempted);
        assertTrue(queue.pending.isEmpty());

        FakeQueue interrupted = new FakeQueue("three");
        assertTrue(new AutomaticDownloadRunner(interrupted, new FakeDownloads(interrupted))
                .run(() -> true));
        assertEquals(List.of("three"), interrupted.pending);
    }

    @Test
    public void offlinePolicyPreservesCandidatesWithoutConsumingRetries() throws Exception {
        FakeQueue queue = new FakeQueue("one", "two");
        FakeDownloads downloads = new FakeDownloads(queue);
        downloads.failure = EpisodeDownloadException.Reason.OFFLINE_MODE;
        AutomaticDownloadRunner runner = new AutomaticDownloadRunner(queue, downloads);
        for (int i = 0; i < 5; i++) assertTrue(runner.run(() -> false));
        assertEquals(0, queue.failures);
        assertEquals(List.of("one", "two"), queue.pending);
        downloads.failure = null;
        assertFalse(runner.run(() -> false));
        assertEquals(2, downloads.completed);
    }

    @Test public void authenticationDenialsDoNotRetryInBackground() throws Exception {
        for (var reason : new EpisodeDownloadException.Reason[] {
                EpisodeDownloadException.Reason.LOGIN_REQUIRED, EpisodeDownloadException.Reason.ACCESS_DENIED}) {
            FakeQueue queue = new FakeQueue("one"); FakeDownloads downloads = new FakeDownloads(queue);
            downloads.failure = reason;
            AutomaticDownloadRunner runner = new AutomaticDownloadRunner(queue, downloads);
            assertFalse(runner.run(() -> false)); assertEquals(0, queue.failures);
            assertFalse(runner.run(() -> false)); assertEquals(List.of("one"), downloads.attempted);
        }
    }

    private static final class FakeQueue implements AutomaticDownloadQueue {
        private final List<String> pending = new ArrayList<>();
        private final List<String> disabled = new ArrayList<>();
        private int maximumRequested;
        private int failures;

        private FakeQueue(String... values) {
            pending.addAll(List.of(values));
        }

        @Override
        public List<String> listAutomaticDownloadCandidates(int limit) {
            maximumRequested = Math.max(maximumRequested, limit);
            return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
        }

        @Override
        public boolean isAutomaticDownloadCandidate(String mediaId) {
            if (disabled.contains(mediaId)) pending.remove(mediaId);
            return pending.contains(mediaId) && !disabled.contains(mediaId);
        }

        @Override
        public void clearAutomaticDownloadCandidate(String mediaId) {
            pending.remove(mediaId);
        }

        @Override
        public void recordAutomaticDownloadFailure(String mediaId) {
            if (pending.contains(mediaId) && ++failures >= 3) pending.remove(mediaId);
        }
    }

    private static final class FakeDownloads implements EpisodeDownloadActions {
        private final FakeQueue queue;
        private final List<String> attempted = new ArrayList<>();
        private EpisodeDownloadException.Reason failure;
        private String disableDuringTransfer;
        private int completed;

        private FakeDownloads(FakeQueue queue) {
            this.queue = queue;
        }

        @Override
        public EpisodeDownloadResult download(String mediaId, BooleanSupplier cancelled)
                throws EpisodeDownloadException {
            attempted.add(mediaId);
            if (failure != null) {
                throw new EpisodeDownloadException(failure, "Fake failure");
            }
            if (mediaId.equals(disableDuringTransfer)) {
                queue.disabled.add(mediaId);
                queue.pending.remove(mediaId);
            }
            if (cancelled.getAsBoolean()) {
                throw new EpisodeDownloadException(
                        EpisodeDownloadException.Reason.CANCELLED, "Cancelled");
            }
            completed++;
            queue.pending.remove(mediaId);
            return new EpisodeDownloadResult(mediaId, 1L);
        }

        @Override
        public void delete(String mediaId) throws EpisodeDownloadException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getStorageUsageBytes() throws EpisodeDownloadException {
            return 0L;
        }
    }
}
