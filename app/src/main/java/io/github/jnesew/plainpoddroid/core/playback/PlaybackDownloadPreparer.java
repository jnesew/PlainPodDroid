package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.download.DownloadLookup;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadActions;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException;
import io.github.jnesew.plainpoddroid.core.download.VerifiedDownloadStore;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Called on the player's loader thread. Audio is exposed only after a complete download. */
public final class PlaybackDownloadPreparer {
    private final VerifiedDownloadStore store;
    private final EpisodeDownloadActions downloads;
    private final ExecutorService transferLane;
    private final BooleanSupplier offline;
    private final AtomicInteger waiting = new AtomicInteger();

    public PlaybackDownloadPreparer(VerifiedDownloadStore store, EpisodeDownloadActions downloads,
            ExecutorService transferLane, BooleanSupplier offline) {
        this.store = Objects.requireNonNull(store);
        this.downloads = Objects.requireNonNull(downloads);
        this.transferLane = Objects.requireNonNull(transferLane);
        this.offline = Objects.requireNonNull(offline);
    }

    public boolean hasWaitingPlayback() { return waiting.get() > 0; }

    public File prepare(String mediaId, BooleanSupplier cancelled) throws IOException {
        DownloadPlaybackUri.create(mediaId);
        Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        File existing = complete(mediaId);
        if (existing != null) return existing;
        requireOnline();
        AtomicBoolean abandoned = new AtomicBoolean();
        BooleanSupplier stopped = () -> abandoned.get() || cancelled.getAsBoolean();
        Future<File> future = null;
        waiting.incrementAndGet();
        try {
            future = transferLane.submit(() -> {
                checkCancelled(stopped);
                File local = complete(mediaId);
                if (local != null) return local;
                requireOnline();
                try { downloads.download(mediaId, stopped); }
                catch (EpisodeDownloadException exception) {
                    if (exception.getReason() != EpisodeDownloadException.Reason.ALREADY_DOWNLOADED)
                        throw exception;
                }
                checkCancelled(stopped);
                requireOnline();
                File downloaded = complete(mediaId);
                if (downloaded == null) throw new IOException("Complete audio download is unavailable.");
                return downloaded;
            });
            while (true) {
                checkCancelled(stopped);
                requireOnline();
                try {
                    File ready = future.get(200, TimeUnit.MILLISECONDS);
                    checkCancelled(stopped);
                    return ready;
                }
                catch (TimeoutException pending) { /* Cancellation remains responsive while queued. */ }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Playback download was cancelled.");
        } catch (ExecutionException exception) {
            throw new IOException("Unable to download this episode before playback.", exception.getCause());
        } catch (RuntimeException exception) {
            throw new IOException("Playback download is unavailable.", exception);
        } finally {
            abandoned.set(true);
            if (future != null && !future.isDone()) future.cancel(false);
            waiting.decrementAndGet();
        }
    }

    private File complete(String id) throws IOException {
        DownloadLookup lookup = store.lookup(id);
        return lookup.getState() == DownloadLookup.State.COMPLETE ? lookup.getCompleteFile() : null;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Playback download was cancelled.");
    }

    private void requireOnline() throws IOException {
        if (offline.getAsBoolean()) throw new IOException("Offline mode blocks audio downloads.");
    }
}
