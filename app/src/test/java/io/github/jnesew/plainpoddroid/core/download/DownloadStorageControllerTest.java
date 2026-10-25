package io.github.jnesew.plainpoddroid.core.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

public final class DownloadStorageControllerTest {
    @Test
    public void refreshPublishesBoundedUsageState() {
        FakeActions actions = new FakeActions();
        actions.usageBytes = 123L;
        QueuedExecutor worker = new QueuedExecutor();
        List<DownloadStorageController.State> states = new ArrayList<>();
        DownloadStorageController controller = controller(actions, worker, states);

        assertTrue(controller.refresh());
        assertEquals(DownloadStorageController.Phase.LOADING,
                controller.getState().getPhase());
        assertFalse(controller.refresh());
        worker.runNext();

        assertEquals(DownloadStorageController.Phase.READY,
                controller.getState().getPhase());
        assertEquals(123L, controller.getState().getUsageBytes());
        assertEquals(2, states.size());
    }

    @Test
    public void storageDetailsCollapseToOneFailure() {
        FakeActions actions = new FakeActions();
        actions.failure = new EpisodeDownloadException(
                EpisodeDownloadException.Reason.STORAGE_FAILED,
                "sensitive path");
        QueuedExecutor worker = new QueuedExecutor();
        DownloadStorageController controller = controller(
                actions,
                worker,
                new ArrayList<>());

        assertTrue(controller.refresh());
        worker.runNext();

        assertEquals(DownloadStorageController.Phase.ERROR,
                controller.getState().getPhase());
        assertEquals(DownloadStorageController.Failure.STORAGE_FAILED,
                controller.getState().getFailure());
    }

    @Test
    public void negativeUsageFailsClosed() {
        FakeActions actions = new FakeActions();
        actions.usageBytes = -1L;
        QueuedExecutor worker = new QueuedExecutor();
        DownloadStorageController controller = controller(
                actions,
                worker,
                new ArrayList<>());

        assertTrue(controller.refresh());
        worker.runNext();

        assertEquals(DownloadStorageController.Phase.ERROR,
                controller.getState().getPhase());
        assertEquals(DownloadStorageController.Failure.STORAGE_FAILED,
                controller.getState().getFailure());
    }

    @Test
    public void rejectedWorkerBecomesRedactedFailure() {
        Executor rejecting = ignored -> {
            throw new RejectedExecutionException("closed");
        };
        DownloadStorageController controller = new DownloadStorageController(
                new FakeActions(),
                rejecting,
                Runnable::run,
                ignored -> { });

        assertTrue(controller.refresh());

        assertEquals(DownloadStorageController.Phase.ERROR,
                controller.getState().getPhase());
        assertEquals(DownloadStorageController.Failure.BACKGROUND_WORK_UNAVAILABLE,
                controller.getState().getFailure());
    }

    @Test
    public void closeSuppressesStaleCompletion() {
        FakeActions actions = new FakeActions();
        QueuedExecutor worker = new QueuedExecutor();
        List<DownloadStorageController.State> states = new ArrayList<>();
        DownloadStorageController controller = controller(actions, worker, states);

        assertTrue(controller.refresh());
        controller.close();
        worker.runNext();

        assertEquals(1, states.size());
        assertFalse(controller.refresh());
    }

    private static DownloadStorageController controller(
            FakeActions actions,
            Executor worker,
            List<DownloadStorageController.State> states) {
        return new DownloadStorageController(
                actions,
                worker,
                Runnable::run,
                states::add);
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }
    }

    private static final class FakeActions implements EpisodeDownloadActions {
        private long usageBytes;
        private EpisodeDownloadException failure;

        @Override
        public EpisodeDownloadResult download(
                String mediaId,
                BooleanSupplier cancellationRequested) {
            throw new AssertionError("Unexpected download");
        }

        @Override
        public void delete(String mediaId) {
            throw new AssertionError("Unexpected delete");
        }

        @Override
        public long getStorageUsageBytes() throws EpisodeDownloadException {
            if (failure != null) {
                throw failure;
            }
            return usageBytes;
        }
    }
}
