package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Coalesces player samples and submits URL-free updates through a caller-supplied
 * serial executor. Calls are safe from any thread.
 */
public final class PlaybackProgressCoordinator {
    public interface FailureListener {
        void onStorageFailure();
    }

    private static final long NO_WRITE = Long.MIN_VALUE;

    private final PlaybackProgressStore store;
    private final Executor executor;
    private final FailureListener failureListener;
    private final Supplier<CompletionMode> completionMode;

    private String currentMediaId;
    private long currentPositionMillis;
    private long currentDurationMillis = PlaybackProgressPolicy.UNKNOWN_DURATION_MILLIS;
    private boolean currentCompleted;
    private CompletionMode lastCompletionMode;
    private long lastSubmittedElapsedMillis = NO_WRITE;
    private PlaybackProgressUpdate lastSubmittedUpdate;
    private boolean closed;
    private String lastHistoryMediaId;
    private long lastHistoryElapsedMillis = NO_WRITE;

    public PlaybackProgressCoordinator(
            PlaybackProgressStore store,
            Executor executor,
            FailureListener failureListener) {
        this(store, executor, failureListener, () -> CompletionMode.NEAR_END);
    }

    public PlaybackProgressCoordinator(
            PlaybackProgressStore store,
            Executor executor,
            FailureListener failureListener,
            Supplier<CompletionMode> completionMode) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        this.completionMode = Objects.requireNonNull(completionMode, "completionMode");
    }

    public synchronized void sample(
            String mediaId,
            long positionMillis,
            long durationMillis,
            long elapsedRealtimeMillis,
            long wallClockMillis) {
        recordSample(
                mediaId,
                positionMillis,
                durationMillis,
                elapsedRealtimeMillis,
                wallClockMillis,
                false);
    }

    /** Called only while audio is playing; position changes alone are not listening. */
    public synchronized void recordListening(String mediaId, long elapsedRealtimeMillis, long wallClockMillis) {
        PlaybackProgressUpdate.requireMediaId(mediaId);
        requireTimes(elapsedRealtimeMillis, wallClockMillis);
        if (closed || (mediaId.equals(lastHistoryMediaId) && lastHistoryElapsedMillis != NO_WRITE
                && elapsedRealtimeMillis - lastHistoryElapsedMillis
                < SecurityLimits.PLAYBACK_PROGRESS_WRITE_INTERVAL_MILLIS)) return;
        try {
            executor.execute(() -> {
                try { store.recordListening(mediaId, wallClockMillis); }
                catch (IOException | RuntimeException e) { notifyFailure(); }
            });
            lastHistoryMediaId = mediaId;
            lastHistoryElapsedMillis = elapsedRealtimeMillis;
        } catch (RuntimeException e) { notifyFailure(); }
    }

    public synchronized void force(
            String mediaId,
            long positionMillis,
            long durationMillis,
            long elapsedRealtimeMillis,
            long wallClockMillis) {
        recordSample(
                mediaId,
                positionMillis,
                durationMillis,
                elapsedRealtimeMillis,
                wallClockMillis,
                true);
    }

    /** Forces a precise old position only when it still belongs to the tracked episode. */
    public synchronized void forceCurrentPosition(
            String mediaId,
            long positionMillis,
            long elapsedRealtimeMillis,
            long wallClockMillis) {
        PlaybackProgressUpdate.requireMediaId(mediaId);
        requireTimes(elapsedRealtimeMillis, wallClockMillis);
        if (closed || currentMediaId == null || !currentMediaId.equals(mediaId)) {
            return;
        }
        currentPositionMillis = PlaybackProgressPolicy.normalizePosition(
                positionMillis,
                currentDurationMillis);
        updateCompletion();
        submitCurrent(elapsedRealtimeMillis, wallClockMillis, true);
    }

    public synchronized void close(long elapsedRealtimeMillis, long wallClockMillis) {
        requireTimes(elapsedRealtimeMillis, wallClockMillis);
        if (closed) {
            return;
        }
        submitCurrent(elapsedRealtimeMillis, wallClockMillis, true);
        closed = true;
        clearCurrent();
    }

    private void recordSample(
            String mediaId,
            long positionMillis,
            long durationMillis,
            long elapsedRealtimeMillis,
            long wallClockMillis,
            boolean force) {
        PlaybackProgressUpdate.requireMediaId(mediaId);
        requireTimes(elapsedRealtimeMillis, wallClockMillis);
        if (closed) {
            return;
        }

        if (currentMediaId != null && !currentMediaId.equals(mediaId)) {
            submitCurrent(elapsedRealtimeMillis, wallClockMillis, true);
            clearCurrent();
        }
        if (currentMediaId == null) {
            currentMediaId = mediaId;
        }
        currentDurationMillis = PlaybackProgressPolicy.normalizeDuration(durationMillis);
        currentPositionMillis = PlaybackProgressPolicy.normalizePosition(
                positionMillis,
                currentDurationMillis);
        updateCompletion();
        submitCurrent(elapsedRealtimeMillis, wallClockMillis, force);
    }

    private void submitCurrent(
            long elapsedRealtimeMillis,
            long wallClockMillis,
            boolean force) {
        if (currentMediaId == null) {
            return;
        }
        PlaybackProgressUpdate update = new PlaybackProgressUpdate(
                currentMediaId,
                currentPositionMillis,
                currentCompleted,
                wallClockMillis);
        if (update.hasSameProgress(lastSubmittedUpdate)) {
            return;
        }
        if (!force && lastSubmittedElapsedMillis != NO_WRITE
                && elapsedRealtimeMillis - lastSubmittedElapsedMillis
                < SecurityLimits.PLAYBACK_PROGRESS_WRITE_INTERVAL_MILLIS) {
            return;
        }
        try {
            executor.execute(() -> persist(update));
        } catch (RuntimeException exception) {
            notifyFailure();
            return;
        }
        lastSubmittedUpdate = update;
        lastSubmittedElapsedMillis = elapsedRealtimeMillis;
    }

    private void persist(PlaybackProgressUpdate update) {
        try {
            store.savePlaybackProgress(update);
        } catch (IOException | RuntimeException exception) {
            notifyFailure();
        }
    }

    private void notifyFailure() {
        try {
            failureListener.onStorageFailure();
        } catch (RuntimeException ignored) {
            // A diagnostic callback must not break the serial persistence worker.
        }
    }

    private void clearCurrent() {
        currentMediaId = null;
        currentPositionMillis = 0L;
        currentDurationMillis = PlaybackProgressPolicy.UNKNOWN_DURATION_MILLIS;
        currentCompleted = false;
        lastCompletionMode = null;
        lastSubmittedElapsedMillis = NO_WRITE;
    }

    private void updateCompletion() {
        CompletionMode mode = Objects.requireNonNull(completionMode.get(), "completion mode");
        if (lastCompletionMode != mode) {
            currentCompleted = false;
            lastCompletionMode = mode;
        }
        currentCompleted = currentCompleted || PlaybackProgressPolicy.isCompleted(
                currentPositionMillis, currentDurationMillis, mode);
    }

    private static void requireTimes(long elapsedRealtimeMillis, long wallClockMillis) {
        if (elapsedRealtimeMillis < 0L || wallClockMillis < 0L) {
            throw new IllegalArgumentException("Invalid playback sample time.");
        }
    }
}
