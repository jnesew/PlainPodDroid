package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefresher;

import java.io.IOException;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates one explicit, cancellable refresh-all request for a UI. */
public final class ManualRefreshAllController implements AutoCloseable {
    public enum Phase {
        IDLE,
        REFRESHING,
        COMPLETED,
        ERROR
    }

    public enum Failure {
        NONE,
        STORAGE_FAILED,
        BACKGROUND_WORK_UNAVAILABLE
    }

    public interface Listener {
        void onManualRefreshAllStateChanged(State state);
    }

    public static final class State {
        private final Phase phase;
        private final Failure failure;
        private final ManualRefreshAllResult result;

        private State(Phase phase, Failure failure, ManualRefreshAllResult result) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.result = result;
        }

        public Phase getPhase() {
            return phase;
        }

        public Failure getFailure() {
            return failure;
        }

        public ManualRefreshAllResult getResult() {
            return result;
        }
    }

    private final ManualRefreshAllRunner runner;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Object owner = new Object();

    private Listener listener;
    private State state = state(Phase.IDLE, Failure.NONE, null);
    private ActiveRun activeRun;
    private long generation;
    private boolean closed;

    public ManualRefreshAllController(
            SubscriptionRefresher refresher,
            ScheduledRefreshStateStore stateStore,
            Executor workerExecutor,
            Executor callbackExecutor,
            Listener listener) {
        runner = new ManualRefreshAllRunner(refresher, stateStore);
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public State getState() {
        synchronized (owner) {
            return state;
        }
    }

    public boolean refreshAll() { return start(null); }

    public boolean retryFailures() {
        final List<Long> ids;
        synchronized (owner) {
            if (closed || state.phase != Phase.COMPLETED || state.result == null
                    || state.result.getFailureDetails().isEmpty()) return false;
            ids = new ArrayList<>();
            for(var failure:state.result.getFailureDetails()) ids.add(failure.subscriptionId());
        }
        return start(ids);
    }

    private boolean start(List<Long> ids) {
        final ActiveRun run;
        final State nextState;
        synchronized (owner) {
            if (closed || state.phase == Phase.REFRESHING) {
                return false;
            }
            generation++;
            run = new ActiveRun(generation,ids);
            activeRun = run;
            nextState = state(Phase.REFRESHING, Failure.NONE, null);
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> executeRun(run));
        } catch (RejectedExecutionException exception) {
            postFailure(run, Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    public boolean cancel() {
        synchronized (owner) {
            if (closed || state.phase != Phase.REFRESHING || activeRun == null) {
                return false;
            }
            activeRun.cancelRequested.set(true);
            return true;
        }
    }

    public boolean reset() {
        final State nextState;
        synchronized (owner) {
            if (closed || state.phase == Phase.REFRESHING) {
                return false;
            }
            generation++;
            nextState = state(Phase.IDLE, Failure.NONE, null);
            state = nextState;
        }
        dispatch(nextState);
        return true;
    }

    @Override
    public void close() {
        synchronized (owner) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            if (activeRun != null) {
                activeRun.cancelRequested.set(true);
                activeRun = null;
            }
            listener = null;
        }
    }

    private void executeRun(ActiveRun run) {
        try {
            ManualRefreshAllResult result = runner.runOnly(run.ids,run.cancelRequested::get);
            callbackExecutor.execute(() -> completeSuccess(run, result));
        } catch (IOException | RuntimeException exception) {
            postFailure(run, Failure.STORAGE_FAILED);
        }
    }

    private void postFailure(ActiveRun run, Failure failure) {
        callbackExecutor.execute(() -> completeFailure(run, failure));
    }

    private void completeSuccess(ActiveRun run, ManualRefreshAllResult result) {
        final State nextState;
        synchronized (owner) {
            if (!matches(run)) {
                return;
            }
            activeRun = null;
            nextState = state(Phase.COMPLETED, Failure.NONE, result);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completeFailure(ActiveRun run, Failure failure) {
        final State nextState;
        synchronized (owner) {
            if (!matches(run)) {
                return;
            }
            activeRun = null;
            nextState = state(Phase.ERROR, failure, null);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private boolean matches(ActiveRun run) {
        return !closed
                && activeRun == run
                && generation == run.generation
                && state.phase == Phase.REFRESHING;
    }

    private void dispatch(State expectedState) {
        callbackExecutor.execute(() -> notifyListener(expectedState));
    }

    private void notifyListener(State expectedState) {
        final Listener target;
        synchronized (owner) {
            if (closed || state != expectedState) {
                return;
            }
            target = listener;
        }
        if (target != null) {
            target.onManualRefreshAllStateChanged(expectedState);
        }
    }

    private static State state(
            Phase phase,
            Failure failure,
            ManualRefreshAllResult result) {
        return new State(phase, failure, result);
    }

    private static final class ActiveRun {
        private final long generation;
        private final List<Long> ids;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        private ActiveRun(long generation,List<Long> ids) {
            this.generation = generation;
            this.ids = ids == null ? null : List.copyOf(ids);
        }
    }
}
