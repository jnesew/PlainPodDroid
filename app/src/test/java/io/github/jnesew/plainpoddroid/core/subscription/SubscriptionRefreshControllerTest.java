package io.github.jnesew.plainpoddroid.core.subscription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SubscriptionRefreshControllerTest {
    @Test
    public void workerAndCallbackSeparationProducesTypedSuccess() {
        FakeRefresher refresher = new FakeRefresher();
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        StateRecorder states = new StateRecorder();
        SubscriptionRefreshController controller = new SubscriptionRefreshController(
                refresher,
                worker,
                callback,
                states);

        assertTrue(controller.refresh(7L));
        assertEquals(SubscriptionRefreshController.Phase.REFRESHING,
                controller.getState().getPhase());
        assertEquals(1, worker.size());
        assertEquals(1, callback.size());
        assertEquals(0, refresher.subscriptionIds.size());

        callback.runNext();
        assertEquals(SubscriptionRefreshController.Phase.REFRESHING,
                states.last().getPhase());
        worker.runNext();
        assertEquals(List.of(7L), refresher.subscriptionIds);
        assertEquals(1, callback.size());
        callback.runNext();

        assertEquals(SubscriptionRefreshController.Phase.SUCCEEDED,
                controller.getState().getPhase());
        assertEquals(SubscriptionRefreshController.Failure.NONE,
                states.last().getFailure());
        assertEquals(7L, states.last().getSubscriptionId());
    }

    @Test
    public void invalidSelectionNeverReachesWorkerAndCanReset() {
        FakeRefresher refresher = new FakeRefresher();
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        StateRecorder states = new StateRecorder();
        SubscriptionRefreshController controller = new SubscriptionRefreshController(
                refresher,
                worker,
                callback,
                states);

        assertFalse(controller.refresh(0L));
        callback.runNext();
        assertEquals(0, worker.size());
        assertEquals(SubscriptionRefreshController.Phase.ERROR,
                states.last().getPhase());
        assertEquals(SubscriptionRefreshController.Failure.INVALID_SELECTION,
                states.last().getFailure());
        assertEquals(-1L, states.last().getSubscriptionId());

        assertTrue(controller.reset());
        callback.runNext();
        assertEquals(SubscriptionRefreshController.Phase.IDLE,
                states.last().getPhase());
    }

    @Test
    public void secondRequestAndResetAreRejectedWhileRefreshIsActive() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        SubscriptionRefreshController controller = new SubscriptionRefreshController(
                new FakeRefresher(),
                worker,
                callback,
                ignored -> { });

        assertTrue(controller.refresh(7L));
        assertFalse(controller.refresh(8L));
        assertFalse(controller.reset());
        assertEquals(1, worker.size());
        assertEquals(7L, controller.getState().getSubscriptionId());
    }

    @Test
    public void everyCoordinatorFailureMapsWithoutExceptionText() {
        assertFailure(
                SubscriptionRefreshException.Reason.SUBSCRIPTION_NOT_FOUND,
                SubscriptionRefreshController.Failure.SUBSCRIPTION_NOT_FOUND);
        assertFailure(
                SubscriptionRefreshException.Reason.ALREADY_RUNNING,
                SubscriptionRefreshController.Failure.ALREADY_RUNNING);
        assertFailure(
                SubscriptionRefreshException.Reason.FETCH_FAILED,
                SubscriptionRefreshController.Failure.FETCH_FAILED);
        assertFailure(
                SubscriptionRefreshException.Reason.INVALID_FEED,
                SubscriptionRefreshController.Failure.INVALID_FEED);
        assertFailure(
                SubscriptionRefreshException.Reason.STORAGE_FAILED,
                SubscriptionRefreshController.Failure.STORAGE_FAILED);
    }

    @Test
    public void rejectedWorkerIsReportedOnCallbackExecutor() {
        ManualExecutor callback = new ManualExecutor();
        StateRecorder states = new StateRecorder();
        Executor rejectingWorker = ignored -> {
            throw new RejectedExecutionException("secret executor detail");
        };
        SubscriptionRefreshController controller = new SubscriptionRefreshController(
                new FakeRefresher(),
                rejectingWorker,
                callback,
                states);

        assertTrue(controller.refresh(7L));
        callback.runNext();
        callback.runNext();

        assertEquals(SubscriptionRefreshController.Phase.ERROR,
                states.last().getPhase());
        assertEquals(
                SubscriptionRefreshController.Failure.BACKGROUND_WORK_UNAVAILABLE,
                states.last().getFailure());
    }

    @Test
    public void closeDropsOutstandingCompletionAndFurtherActions() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        StateRecorder states = new StateRecorder();
        SubscriptionRefreshController controller = new SubscriptionRefreshController(
                new FakeRefresher(),
                worker,
                callback,
                states);

        assertTrue(controller.refresh(7L));
        callback.runNext();
        controller.close();
        worker.runNext();
        callback.runNext();

        assertEquals(1, states.states.size());
        assertFalse(controller.refresh(7L));
        assertFalse(controller.reset());
    }

    private static void assertFailure(
            SubscriptionRefreshException.Reason reason,
            SubscriptionRefreshController.Failure expected) {
        FakeRefresher refresher = new FakeRefresher();
        refresher.failure = new SubscriptionRefreshException(
                reason,
                "secret tokenized URL and storage detail");
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor callback = new ManualExecutor();
        StateRecorder states = new StateRecorder();
        SubscriptionRefreshController controller = new SubscriptionRefreshController(
                refresher,
                worker,
                callback,
                states);

        assertTrue(controller.refresh(7L));
        callback.runNext();
        worker.runNext();
        callback.runNext();

        assertEquals(SubscriptionRefreshController.Phase.ERROR,
                states.last().getPhase());
        assertEquals(expected, states.last().getFailure());
        assertEquals(7L, states.last().getSubscriptionId());
    }

    private static final class FakeRefresher implements SubscriptionRefresher {
        private final List<Long> subscriptionIds = new ArrayList<>();
        private SubscriptionRefreshException failure;

        @Override
        public void refresh(long subscriptionId) throws SubscriptionRefreshException {
            subscriptionIds.add(subscriptionId);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class StateRecorder implements SubscriptionRefreshController.Listener {
        private final List<SubscriptionRefreshController.State> states = new ArrayList<>();

        @Override
        public void onSubscriptionRefreshStateChanged(
                SubscriptionRefreshController.State state) {
            states.add(state);
        }

        private SubscriptionRefreshController.State last() {
            return states.get(states.size() - 1);
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }

        private int size() {
            return tasks.size();
        }
    }
}
