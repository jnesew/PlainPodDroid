package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.playback.PlaybackDownloadPreparer;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackDownloadPreparerTest {
    private static final String ID = "episode-" + "a".repeat(64);
    private final ExecutorService lane = Executors.newSingleThreadExecutor();
    private final ExecutorService caller = Executors.newSingleThreadExecutor();
    private final AtomicReference<DownloadLookup> state = new AtomicReference<>(DownloadLookup.withoutFile(DownloadLookup.State.ABSENT));
    private final AtomicBoolean offline = new AtomicBoolean();
    private final File file = new File("/private/verified.audio");
    private final AtomicInteger calls = new AtomicInteger();
    private interface Action { void run(BooleanSupplier cancelled) throws EpisodeDownloadException; }
    private PlaybackDownloadPreparer preparer(Action action) {
        VerifiedDownloadStore store = new VerifiedDownloadStore() {
            public DownloadLookup lookup(String id) { return state.get(); }
            public boolean isVerifiedCompleteUri(String uri) { return file.toURI().toString().equals(uri); }
        };
        EpisodeDownloadActions downloads = new EpisodeDownloadActions() {
            public EpisodeDownloadResult download(String id, BooleanSupplier cancelled) throws EpisodeDownloadException {
                calls.incrementAndGet(); action.run(cancelled); return new EpisodeDownloadResult(id, 1L);
            }
            public void delete(String id) { throw new AssertionError(); }
            public long getStorageUsageBytes() { return 0L; }
        };
        return new PlaybackDownloadPreparer(store, downloads, lane, offline::get);
    }
    @After public void close() { lane.shutdownNow(); caller.shutdownNow(); }

    @Test public void completedFileNeedsNoTransferEvenOffline() throws Exception {
        state.set(DownloadLookup.complete(file)); offline.set(true);
        assertEquals(file, preparer(cancelled -> fail()).prepare(ID, () -> false));
        assertEquals(0, calls.get());
    }

    @Test public void offlineAndFailedTransfersNeverProducePlaybackFiles() {
        PlaybackDownloadPreparer ready = preparer(cancelled -> {
            throw new EpisodeDownloadException(EpisodeDownloadException.Reason.QUOTA_EXCEEDED, "Quota full");
        });
        offline.set(true);
        assertThrows(IOException.class, () -> ready.prepare(ID, () -> false));
        assertEquals(0, calls.get()); offline.set(false);
        assertThrows(IOException.class, () -> ready.prepare(ID, () -> false));
        assertEquals(1, calls.get()); assertFalse(ready.hasWaitingPlayback());
    }

    @Test public void waitsForCommittedCompleteStateAndDoesNotExposePartialAudio() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        PlaybackDownloadPreparer ready = preparer(cancelled -> {
            state.set(DownloadLookup.withoutFile(DownloadLookup.State.PARTIAL)); started.countDown();
            await(finish); state.set(DownloadLookup.complete(file));
        });
        Future<File> result = caller.submit(() -> ready.prepare(ID, () -> false));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertTrue(ready.hasWaitingPlayback()); assertFalse(result.isDone());
        finish.countDown(); assertEquals(file, result.get(3, TimeUnit.SECONDS));
        assertFalse(ready.hasWaitingPlayback());
    }

    @Test public void anUncommittedTransferCannotBecomeAPlaybackSource() {
        assertThrows(IOException.class, () -> preparer(cancelled -> { }).prepare(ID, () -> false));
    }

    @Test public void cancellationWhileQueuedNeverStartsTheDownloadLater() throws Exception {
        CountDownLatch blocker = new CountDownLatch(1), queued = new CountDownLatch(1);
        lane.submit(() -> await(blocker));
        PlaybackDownloadPreparer ready = preparer(cancelled -> state.set(DownloadLookup.complete(file)));
        AtomicBoolean cancelled = new AtomicBoolean();
        Future<File> result = caller.submit(() -> ready.prepare(ID, () -> {
            if (ready.hasWaitingPlayback()) queued.countDown(); return cancelled.get();
        }));
        assertTrue(queued.await(3, TimeUnit.SECONDS)); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        blocker.countDown(); lane.submit(() -> { }).get(3, TimeUnit.SECONDS);
        assertEquals(0, calls.get()); assertFalse(ready.hasWaitingPlayback());
    }

    @Test public void cancellationDuringTransferRevokesTheOldRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(), revoked = new AtomicBoolean();
        PlaybackDownloadPreparer ready = preparer(stopped -> {
            started.countDown(); await(finish); revoked.set(stopped.getAsBoolean());
            if (stopped.getAsBoolean()) throw new EpisodeDownloadException(EpisodeDownloadException.Reason.CANCELLED, "Cancelled");
            state.set(DownloadLookup.complete(file));
        });
        Future<File> result = caller.submit(() -> ready.prepare(ID, cancelled::get));
        assertTrue(started.await(3, TimeUnit.SECONDS)); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        finish.countDown(); lane.submit(() -> { }).get(3, TimeUnit.SECONDS);
        assertTrue(revoked.get()); assertEquals(DownloadLookup.State.ABSENT, state.get().getState());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
}
