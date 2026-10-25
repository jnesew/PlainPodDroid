package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshException;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefresher;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class ManualRefreshAllControllerTest {
    @Test
    public void workerAndCallbackSeparationProducesAggregateSuccess() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        StateRecorder states = new StateRecorder();
        FakeRefresher refresher = new FakeRefresher();
        ManualRefreshAllController controller = controller(
                refresher,
                store(1L, 2L),
                worker,
                callback,
                states);

        assertTrue(controller.refreshAll());
        assertEquals(ManualRefreshAllController.Phase.REFRESHING,
                controller.getState().getPhase());
        assertEquals(1, worker.size());
        assertEquals(1, callback.size());

        callback.runNext();
        worker.runNext();
        assertEquals(List.of(1L, 2L), refresher.ids);
        callback.runNext();

        ManualRefreshAllController.State state = controller.getState();
        assertEquals(ManualRefreshAllController.Phase.COMPLETED, state.getPhase());
        assertEquals(ManualRefreshAllController.Failure.NONE, state.getFailure());
        assertNotNull(state.getResult());
        assertEquals(2, state.getResult().getSucceeded());
        assertEquals(state, states.last());
    }

    @Test
    public void secondRequestAndResetAreRejectedWhileActive() {
        ManualExecutor worker = new ManualExecutor();
        ManualRefreshAllController controller = controller(
                new FakeRefresher(),
                store(1L),
                worker,
                new ManualExecutor(),
                ignored -> { });

        assertTrue(controller.refreshAll());
        assertFalse(controller.refreshAll());
        assertFalse(controller.reset());
        assertEquals(1, worker.size());
    }

    @Test
    public void cancelBeforeWorkerRunsProducesCancelledEmptyResult() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        ManualRefreshAllController controller = controller(
                new FakeRefresher(),
                store(1L, 2L),
                worker,
                callback,
                ignored -> { });

        assertTrue(controller.refreshAll());
        assertTrue(controller.cancel());
        assertTrue(controller.cancel());
        callback.runNext();
        worker.runNext();
        callback.runNext();

        ManualRefreshAllResult result = controller.getState().getResult();
        assertNotNull(result);
        assertTrue(result.isCancelled());
        assertEquals(0, result.getAttempted());
        assertFalse(controller.cancel());
    }

    @Test
    public void storageFailureIsRedactedToTypedControllerFailure() {
        ScheduledRefreshStateStore failing = new ScheduledRefreshStateStore() {
            @Override
            public List<ScheduledRefreshCandidate> listScheduledRefreshCandidates()
                    throws IOException {
                throw new IOException("database path and private detail");
            }

            @Override
            public boolean recordScheduledRefreshFailure(
                    ScheduledRefreshCandidate candidate,
                    int newConsecutiveFailures,
                    long attemptedAtMillis,
                    ScheduledRefreshFailure failure) {
                return false;
            }
        };
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        ManualRefreshAllController controller = controller(
                new FakeRefresher(),
                failing,
                worker,
                callback,
                ignored -> { });

        assertTrue(controller.refreshAll());
        callback.runNext();
        worker.runNext();
        callback.runNext();

        assertEquals(ManualRefreshAllController.Phase.ERROR,
                controller.getState().getPhase());
        assertEquals(ManualRefreshAllController.Failure.STORAGE_FAILED,
                controller.getState().getFailure());
        assertEquals(null, controller.getState().getResult());
    }

    @Test
    public void rejectedWorkerIsReportedOnCallbackExecutor() {
        ManualExecutor callback = new ManualExecutor();
        Executor rejected = ignored -> {
            throw new RejectedExecutionException("executor detail");
        };
        ManualRefreshAllController controller = controller(
                new FakeRefresher(),
                store(1L),
                rejected,
                callback,
                ignored -> { });

        assertTrue(controller.refreshAll());
        callback.runNext();
        callback.runNext();

        assertEquals(ManualRefreshAllController.Phase.ERROR,
                controller.getState().getPhase());
        assertEquals(ManualRefreshAllController.Failure.BACKGROUND_WORK_UNAVAILABLE,
                controller.getState().getFailure());
    }

    @Test
    public void closeCancelsWorkAndDropsOutstandingCompletion() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        StateRecorder states = new StateRecorder();
        ManualRefreshAllController controller = controller(
                new FakeRefresher(),
                store(1L, 2L),
                worker,
                callback,
                states);

        assertTrue(controller.refreshAll());
        callback.runNext();
        controller.close();
        worker.runNext();
        callback.runNext();

        assertEquals(1, states.states.size());
        assertFalse(controller.refreshAll());
        assertFalse(controller.cancel());
        assertFalse(controller.reset());
    }

    @Test public void retryTargetsOnlyFailuresAndDoesNotRepeatSuccessfulFeeds() {
        ManualExecutor worker=new ManualExecutor(),callback=new ManualExecutor();
        List<Long> attempted=new ArrayList<>();boolean[] fail={true};
        SubscriptionRefresher refresher=id->{
            attempted.add(id);
            if(id==2L&&fail[0])throw new SubscriptionRefreshException(
                    SubscriptionRefreshException.Reason.FETCH_FAILED,"private detail");
        };
        var controller=controller(refresher,store(1L,2L,3L),worker,callback,ignored->{});
        assertFalse(controller.retryFailures());
        controller.refreshAll();callback.runNext();worker.runNext();callback.runNext();
        assertEquals(1,controller.getState().getResult().getFailureDetails().size());
        fail[0]=false;
        assertTrue(controller.retryFailures());assertFalse(controller.retryFailures());
        callback.runNext();worker.runNext();callback.runNext();
        assertEquals(List.of(1L,2L,3L,2L),attempted);
        assertEquals(1,controller.getState().getResult().getSucceeded());
        assertFalse(controller.retryFailures());
        controller.close();assertFalse(controller.retryFailures());
    }

    private static ManualRefreshAllController controller(
            SubscriptionRefresher refresher,
            ScheduledRefreshStateStore store,
            Executor worker,
            Executor callback,
            ManualRefreshAllController.Listener listener) {
        return new ManualRefreshAllController(
                refresher,
                store,
                worker,
                callback,
                listener);
    }

    private static ScheduledRefreshStateStore store(long... ids) {
        List<ScheduledRefreshCandidate> candidates = new ArrayList<>();
        for (long id : ids) {
            candidates.add(new ScheduledRefreshCandidate(id, 100L, 0, 0L));
        }
        return new ScheduledRefreshStateStore() {
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
                throw new AssertionError("Manual refresh must not record scheduled failure.");
            }
        };
    }

    private static final class FakeRefresher implements SubscriptionRefresher {
        private final List<Long> ids = new ArrayList<>();

        @Override
        public void refresh(long subscriptionId) throws SubscriptionRefreshException {
            ids.add(subscriptionId);
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }
    }

    private static final class StateRecorder
            implements ManualRefreshAllController.Listener {
        private final List<ManualRefreshAllController.State> states = new ArrayList<>();

        @Override
        public void onManualRefreshAllStateChanged(
                ManualRefreshAllController.State state) {
            states.add(state);
        }

        private ManualRefreshAllController.State last() {
            return states.get(states.size() - 1);
        }
    }
}
