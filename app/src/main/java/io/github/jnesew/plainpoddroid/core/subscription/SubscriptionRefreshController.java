package io.github.jnesew.plainpoddroid.core.subscription;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Runs one explicit refresh without exposing stored feed URLs to the UI. */
public final class SubscriptionRefreshController implements AutoCloseable {
    public enum Phase {
        IDLE,
        REFRESHING,
        SUCCEEDED,
        ERROR
    }

    public enum Failure {
        NONE,
        INVALID_SELECTION,
        SUBSCRIPTION_NOT_FOUND,
        ALREADY_RUNNING,
        FETCH_FAILED,
        LOGIN_REQUIRED,
        ACCESS_DENIED,
        INVALID_FEED,
        STORAGE_FAILED,
        BACKGROUND_WORK_UNAVAILABLE
    }

    public interface Listener {
        void onSubscriptionRefreshStateChanged(State state);
    }

    public static final class State {
        private final Phase phase;
        private final Failure failure;
        private final long subscriptionId;

        private State(Phase phase, Failure failure, long subscriptionId) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.subscriptionId = subscriptionId;
        }

        public Phase getPhase() {
            return phase;
        }

        public Failure getFailure() {
            return failure;
        }

        public long getSubscriptionId() {
            return subscriptionId;
        }
    }

    private final SubscriptionRefresher refresher;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Object lock = new Object();

    private Listener listener;
    private State state = state(Phase.IDLE, Failure.NONE, -1L);
    private long generation;
    private boolean closed;

    public SubscriptionRefreshController(
            SubscriptionRefresher refresher,
            Executor workerExecutor,
            Executor callbackExecutor,
            Listener listener) {
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean refresh(long subscriptionId) {
        final long requestGeneration;
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase == Phase.REFRESHING) {
                return false;
            }
            generation++;
            requestGeneration = generation;
            if (subscriptionId < 1L) {
                nextState = state(Phase.ERROR, Failure.INVALID_SELECTION, -1L);
                state = nextState;
                dispatch(nextState);
                return false;
            }
            nextState = state(Phase.REFRESHING, Failure.NONE, subscriptionId);
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> runRefresh(requestGeneration, subscriptionId));
        } catch (RejectedExecutionException exception) {
            postFailure(requestGeneration, subscriptionId,
                    Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    public boolean reset() {
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase == Phase.REFRESHING) {
                return false;
            }
            generation++;
            nextState = state(Phase.IDLE, Failure.NONE, -1L);
            state = nextState;
        }
        dispatch(nextState);
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

    private void runRefresh(long requestGeneration, long subscriptionId) {
        try {
            refresher.refresh(subscriptionId);
            callbackExecutor.execute(
                    () -> completeSuccess(requestGeneration, subscriptionId));
        } catch (SubscriptionRefreshException exception) {
            postFailure(requestGeneration, subscriptionId, mapFailure(exception.getReason()));
        }
    }

    private void completeSuccess(long requestGeneration, long subscriptionId) {
        final State nextState;
        synchronized (lock) {
            if (!matchesActiveRequest(requestGeneration, subscriptionId)) {
                return;
            }
            nextState = state(Phase.SUCCEEDED, Failure.NONE, subscriptionId);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void postFailure(
            long requestGeneration,
            long subscriptionId,
            Failure failure) {
        callbackExecutor.execute(
                () -> completeFailure(requestGeneration, subscriptionId, failure));
    }

    private void completeFailure(
            long requestGeneration,
            long subscriptionId,
            Failure failure) {
        final State nextState;
        synchronized (lock) {
            if (!matchesActiveRequest(requestGeneration, subscriptionId)) {
                return;
            }
            nextState = state(Phase.ERROR, failure, subscriptionId);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private boolean matchesActiveRequest(long requestGeneration, long subscriptionId) {
        return !closed
                && generation == requestGeneration
                && state.phase == Phase.REFRESHING
                && state.subscriptionId == subscriptionId;
    }

    private void dispatch(State expectedState) {
        callbackExecutor.execute(() -> notifyListener(expectedState));
    }

    private void notifyListener(State expectedState) {
        final Listener target;
        synchronized (lock) {
            if (closed || state != expectedState) {
                return;
            }
            target = listener;
        }
        if (target != null) {
            target.onSubscriptionRefreshStateChanged(expectedState);
        }
    }

    private static Failure mapFailure(SubscriptionRefreshException.Reason reason) {
        switch (reason) {
            case SUBSCRIPTION_NOT_FOUND:
                return Failure.SUBSCRIPTION_NOT_FOUND;
            case ALREADY_RUNNING:
                return Failure.ALREADY_RUNNING;
            case LOGIN_REQUIRED: return Failure.LOGIN_REQUIRED;
            case ACCESS_DENIED: return Failure.ACCESS_DENIED;
            case FETCH_FAILED:
                return Failure.FETCH_FAILED;
            case INVALID_FEED:
                return Failure.INVALID_FEED;
            case STORAGE_FAILED:
                return Failure.STORAGE_FAILED;
            default:
                throw new AssertionError("Unknown subscription refresh failure.");
        }
    }

    private static State state(Phase phase, Failure failure, long subscriptionId) {
        return new State(phase, failure, subscriptionId);
    }
}
