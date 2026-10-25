package io.github.jnesew.plainpoddroid.core.chapter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Explicit asynchronous load; documents live only for this details view. */
public final class ChapterLoadController implements AutoCloseable {
    public enum Phase { IDLE, LOADING, READY, ERROR }
    public enum Failure { NONE, INVALID_MEDIA_ID, NO_REFERENCE, LOAD_FAILED, BACKGROUND_WORK_UNAVAILABLE }
    public interface Listener { void onChapterLoadStateChanged(State state); }

    public static final class State {
        private final Phase phase;
        private final Failure failure;
        private final String mediaId;
        private final PodcastChapterDocument document;

        private State(Phase phase, Failure failure, String mediaId, PodcastChapterDocument document) {
            this.phase = phase; this.failure = failure; this.mediaId = mediaId; this.document = document;
        }
        public Phase getPhase() { return phase; }
        public Failure getFailure() { return failure; }
        public String getMediaId() { return mediaId; }
        public PodcastChapterDocument getDocument() { return document; }
        public boolean isBusy() { return phase == Phase.LOADING; }
    }

    private final EpisodeChapterCoordinator coordinator;
    private final Executor worker;
    private final Executor callbacks;
    private final Object lock = new Object();
    private Listener listener;
    private State state = new State(Phase.IDLE, Failure.NONE, "", null);
    private long generation;
    private boolean closed;

    public ChapterLoadController(EpisodeChapterCoordinator coordinator, Executor worker,
            Executor callbacks, Listener listener) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public State getState() { synchronized (lock) { return state; } }

    public boolean load(String mediaId) {
        final long request;
        synchronized (lock) {
            if (closed || state.isBusy()) { return false; }
            request = ++generation;
            if (mediaId == null || !mediaId.matches("episode-[0-9a-f]{64}")) {
                state = new State(Phase.ERROR, Failure.INVALID_MEDIA_ID, "", null);
            } else {
                state = new State(Phase.LOADING, Failure.NONE, mediaId, null);
            }
        }
        if (!notifyState(request)) { return false; }
        if (!getState().isBusy()) { return false; }
        try {
            worker.execute(() -> run(request, mediaId));
            return true;
        } catch (RejectedExecutionException exception) {
            complete(request, new State(Phase.ERROR, Failure.BACKGROUND_WORK_UNAVAILABLE, mediaId, null));
            return false;
        }
    }

    private void run(long request, String mediaId) {
        // Closing/resetting a queued view must not initiate a later publisher contact.
        synchronized (lock) {
            if (closed || request != generation || !state.isBusy()) { return; }
        }
        State result;
        try {
            result = new State(Phase.READY, Failure.NONE, mediaId, coordinator.load(mediaId));
        } catch (ChapterLoadException exception) {
            result = new State(Phase.ERROR, exception.getReason() == ChapterLoadException.Reason.NO_REFERENCE
                    ? Failure.NO_REFERENCE : Failure.LOAD_FAILED, mediaId, null);
        } catch (IOException | ChapterParseException | RuntimeException exception) {
            result = new State(Phase.ERROR, Failure.LOAD_FAILED, mediaId, null);
        }
        complete(request, result);
    }

    private void complete(long request, State result) {
        synchronized (lock) {
            if (closed || request != generation) { return; }
            state = result;
        }
        notifyState(request);
    }

    private boolean notifyState(long request) {
        try {
            callbacks.execute(() -> {
                synchronized (lock) {
                    if (!closed && request == generation && listener != null) {
                        listener.onChapterLoadStateChanged(state);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            synchronized (lock) {
                if (!closed && request == generation) {
                    state = new State(Phase.ERROR, Failure.BACKGROUND_WORK_UNAVAILABLE, state.mediaId, null);
                }
            }
            return false;
        }
    }

    public boolean reset() {
        final long request;
        synchronized (lock) {
            if (closed || state.isBusy()) { return false; }
            request = ++generation;
            state = new State(Phase.IDLE, Failure.NONE, "", null);
        }
        notifyState(request);
        return true;
    }

    @Override public void close() {
        synchronized (lock) {
            closed = true; generation++; listener = null;
            state = new State(Phase.IDLE, Failure.NONE, "", null);
        }
    }
}
