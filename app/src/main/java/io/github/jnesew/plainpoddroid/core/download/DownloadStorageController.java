package io.github.jnesew.plainpoddroid.core.download;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Reads app-private download usage without exposing filesystem paths. */
public final class DownloadStorageController implements AutoCloseable {
    public enum Phase {
        IDLE,
        LOADING,
        READY,
        ERROR
    }

    public enum Failure {
        NONE,
        STORAGE_FAILED,
        BACKGROUND_WORK_UNAVAILABLE
    }

    public interface Listener {
        void onDownloadStorageStateChanged(State state);
    }

    public static final class State {
        private final Phase phase;
        private final Failure failure;
        private final long usageBytes;

        private State(Phase phase, Failure failure, long usageBytes) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.failure = Objects.requireNonNull(failure, "failure");
            if (usageBytes < 0L) {
                throw new IllegalArgumentException("Usage must not be negative");
            }
            this.usageBytes = usageBytes;
        }

        public Phase getPhase() {
            return phase;
        }

        public Failure getFailure() {
            return failure;
        }

        public long getUsageBytes() {
            return usageBytes;
        }

        public boolean isBusy() {
            return phase == Phase.LOADING;
        }
    }

    private final EpisodeDownloadActions actions;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Object lock = new Object();

    private Listener listener;
    private State state = state(Phase.IDLE, Failure.NONE, 0L);
    private long generation;
    private boolean closed;

    public DownloadStorageController(
            EpisodeDownloadActions actions,
            Executor workerExecutor,
            Executor callbackExecutor,
            Listener listener) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean refresh() {
        final long requestGeneration;
        final State nextState;
        synchronized (lock) {
            if (closed || state.isBusy()) {
                return false;
            }
            generation++;
            requestGeneration = generation;
            nextState = state(Phase.LOADING, Failure.NONE, state.usageBytes);
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> loadUsage(requestGeneration));
        } catch (RejectedExecutionException exception) {
            postFailure(requestGeneration, Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            listener = null;
        }
    }

    private void loadUsage(long requestGeneration) {
        try {
            long usageBytes = actions.getStorageUsageBytes();
            if (usageBytes < 0L) {
                postFailure(requestGeneration, Failure.STORAGE_FAILED);
                return;
            }
            callbackExecutor.execute(
                    () -> complete(requestGeneration, usageBytes));
        } catch (EpisodeDownloadException | RuntimeException exception) {
            postFailure(requestGeneration, Failure.STORAGE_FAILED);
        }
    }

    private void postFailure(long requestGeneration, Failure failure) {
        callbackExecutor.execute(
                () -> completeFailure(requestGeneration, failure));
    }

    private void complete(long requestGeneration, long usageBytes) {
        final State nextState;
        synchronized (lock) {
            if (closed || generation != requestGeneration || !state.isBusy()) {
                return;
            }
            nextState = state(Phase.READY, Failure.NONE, usageBytes);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completeFailure(long requestGeneration, Failure failure) {
        final State nextState;
        synchronized (lock) {
            if (closed || generation != requestGeneration || !state.isBusy()) {
                return;
            }
            nextState = state(Phase.ERROR, failure, state.usageBytes);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void dispatch(State nextState) {
        callbackExecutor.execute(() -> notifyListener(nextState));
    }

    private void notifyListener(State nextState) {
        final Listener currentListener;
        synchronized (lock) {
            if (closed || state != nextState) {
                return;
            }
            currentListener = listener;
        }
        if (currentListener != null) {
            currentListener.onDownloadStorageStateChanged(nextState);
        }
    }

    private static State state(Phase phase, Failure failure, long usageBytes) {
        return new State(phase, failure, usageBytes);
    }
}
