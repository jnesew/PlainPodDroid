package io.github.jnesew.plainpoddroid.core.queue;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class EpisodeQueueControllerTest {
    @Test
    public void mutationsRequireOneVerifiedQueueSnapshot() {
        FakeStore store = new FakeStore();
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeQueueController controller = controller(store, worker, new ArrayList<>());

        assertFalse(controller.append(mediaId(1)));
        assertEquals(EpisodeQueueController.Phase.IDLE,
                controller.getState().getPhase());
        assertTrue(worker.isEmpty());
    }

    @Test
    public void entryEnforcesOpaqueIdTextDurationAndPositionBounds() {
        EpisodeQueueEntry entry = entry(0, 1);

        assertEquals(mediaId(1), entry.getMediaId());
        assertEquals(0, entry.getPosition());
        assertThrows(IllegalArgumentException.class, () -> new EpisodeQueueEntry(
                "https://private.example/item",
                "Title",
                "Author",
                1L,
                0L,
                false,
                false,
                false,
                0));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeQueueEntry(
                mediaId(1),
                "x".repeat(SecurityLimits.MAX_EPISODE_TITLE_CHARS + 1),
                "Author",
                1L,
                0L,
                false,
                false,
                false,
                0));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeQueueEntry(
                mediaId(1),
                "Title",
                "Author",
                1L,
                0L,
                false,
                false,
                false,
                SecurityLimits.MAX_QUEUE_ENTRIES));
    }

    @Test
    public void refreshPublishesImmutableContiguousSnapshot() {
        FakeStore store = new FakeStore();
        store.entries = List.of(entry(0, 1), entry(1, 2));
        QueuedExecutor worker = new QueuedExecutor();
        List<EpisodeQueueController.State> states = new ArrayList<>();
        EpisodeQueueController controller = controller(store, worker, states);

        assertTrue(controller.refresh());
        assertEquals(EpisodeQueueController.Phase.LOADING,
                controller.getState().getPhase());
        assertFalse(controller.refresh());
        worker.runNext();

        assertEquals(EpisodeQueueController.Phase.READY,
                controller.getState().getPhase());
        assertEquals(2, controller.getState().getEntries().size());
        assertThrows(UnsupportedOperationException.class, () ->
                controller.getState().getEntries().add(entry(2, 3)));
        assertEquals(2, states.size());
    }

    @Test
    public void everyMutationUsesInternalIdAndReloadsQueue() {
        FakeStore store = new FakeStore();
        store.entries = List.of(entry(0, 1));
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeQueueController controller = controller(store, worker, new ArrayList<>());
        assertTrue(controller.refresh());
        worker.runNext();

        assertTrue(controller.append(mediaId(1)));
        worker.runNext();
        assertEquals(EpisodeQueueController.Operation.APPEND, store.lastOperation);
        assertEquals(EpisodeQueueMutation.ADDED, controller.getState().getMutation());

        store.mutation = EpisodeQueueMutation.MOVED;
        assertTrue(controller.playNext(mediaId(1)));
        worker.runNext();
        assertEquals(EpisodeQueueController.Operation.PLAY_NEXT, store.lastOperation);

        assertTrue(controller.move(mediaId(1), 0));
        worker.runNext();
        assertEquals(EpisodeQueueController.Operation.MOVE, store.lastOperation);
        assertEquals(0, store.lastTargetPosition);

        store.entries = Collections.emptyList();
        store.mutation = EpisodeQueueMutation.REMOVED;
        assertTrue(controller.remove(mediaId(1)));
        worker.runNext();
        assertEquals(EpisodeQueueController.Operation.REMOVE, store.lastOperation);
        assertTrue(controller.getState().getEntries().isEmpty());
    }

    @Test
    public void invalidInputsFailBeforeBackgroundWork() {
        FakeStore store = new FakeStore();
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeQueueController controller = controller(store, worker, new ArrayList<>());
        assertTrue(controller.refresh());
        worker.runNext();

        assertFalse(controller.append("https://secret.example/audio.mp3"));
        assertEquals(EpisodeQueueController.Failure.INVALID_MEDIA_ID,
                controller.getState().getFailure());
        assertEquals("", controller.getState().getMediaId());
        assertTrue(controller.refresh());
        worker.runNext();
        assertFalse(controller.move(mediaId(1), SecurityLimits.MAX_QUEUE_ENTRIES));
        assertEquals(EpisodeQueueController.Failure.INVALID_POSITION,
                controller.getState().getFailure());
        assertTrue(worker.isEmpty());
    }

    @Test
    public void typedStoreFailuresBecomeFixedControllerFailures() {
        FakeStore store = new FakeStore();
        store.failure = new EpisodeQueueException(
                EpisodeQueueException.Reason.QUEUE_FULL,
                "sensitive database detail");
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeQueueController controller = controller(store, worker, new ArrayList<>());
        assertTrue(controller.refresh());
        worker.runNext();

        assertTrue(controller.append(mediaId(1)));
        worker.runNext();

        assertEquals(EpisodeQueueController.Phase.ERROR,
                controller.getState().getPhase());
        assertEquals(EpisodeQueueController.Failure.QUEUE_FULL,
                controller.getState().getFailure());
    }

    @Test
    public void malformedSnapshotsFailClosed() {
        FakeStore store = new FakeStore();
        store.entries = List.of(entry(1, 1));
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeQueueController controller = controller(store, worker, new ArrayList<>());

        assertTrue(controller.refresh());
        worker.runNext();

        assertEquals(EpisodeQueueController.Phase.ERROR,
                controller.getState().getPhase());
        assertEquals(EpisodeQueueController.Failure.CORRUPT_STATE,
                controller.getState().getFailure());
    }

    @Test
    public void rejectedWorkerAndCloseSuppressUnsafeCompletion() {
        Executor rejecting = ignored -> {
            throw new RejectedExecutionException("closed");
        };
        EpisodeQueueController rejected = new EpisodeQueueController(
                new FakeStore(),
                rejecting,
                Runnable::run,
                ignored -> { });

        assertTrue(rejected.refresh());
        assertEquals(EpisodeQueueController.Failure.BACKGROUND_WORK_UNAVAILABLE,
                rejected.getState().getFailure());

        QueuedExecutor worker = new QueuedExecutor();
        List<EpisodeQueueController.State> states = new ArrayList<>();
        EpisodeQueueController closed = controller(new FakeStore(), worker, states);
        assertTrue(closed.refresh());
        closed.close();
        worker.runNext();
        assertEquals(1, states.size());
        assertFalse(closed.refresh());
    }

    private static EpisodeQueueController controller(
            FakeStore store,
            Executor worker,
            List<EpisodeQueueController.State> states) {
        return new EpisodeQueueController(store, worker, Runnable::run, states::add);
    }

    private static EpisodeQueueEntry entry(int position, int value) {
        return new EpisodeQueueEntry(
                mediaId(value),
                "Episode " + value,
                "Author",
                60_000L,
                0L,
                false,
                false,
                false,
                position);
    }

    private static String mediaId(int value) {
        return "episode-" + String.format("%064x", value);
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

        private boolean isEmpty() {
            return tasks.isEmpty();
        }
    }

    private static final class FakeStore implements EpisodeQueueStore {
        private List<EpisodeQueueEntry> entries = Collections.emptyList();
        private EpisodeQueueMutation mutation = EpisodeQueueMutation.ADDED;
        private EpisodeQueueException failure;
        private EpisodeQueueController.Operation lastOperation;
        private int lastTargetPosition = -1;

        @Override
        public List<EpisodeQueueEntry> listQueue() throws IOException {
            return entries;
        }

        @Override
        public EpisodeQueueMutation append(String mediaId) throws EpisodeQueueException {
            return mutate(EpisodeQueueController.Operation.APPEND, mediaId, -1);
        }

        @Override
        public EpisodeQueueMutation playNext(String mediaId)
                throws EpisodeQueueException {
            return mutate(EpisodeQueueController.Operation.PLAY_NEXT, mediaId, 0);
        }

        @Override
        public EpisodeQueueMutation remove(String mediaId) throws EpisodeQueueException {
            return mutate(EpisodeQueueController.Operation.REMOVE, mediaId, -1);
        }

        @Override
        public EpisodeQueueMutation move(String mediaId, int targetPosition)
                throws EpisodeQueueException {
            return mutate(EpisodeQueueController.Operation.MOVE, mediaId, targetPosition);
        }

        private EpisodeQueueMutation mutate(
                EpisodeQueueController.Operation operation,
                String mediaId,
                int targetPosition) throws EpisodeQueueException {
            if (failure != null) {
                throw failure;
            }
            lastOperation = operation;
            lastTargetPosition = targetPosition;
            assertTrue(mediaId.matches("episode-[0-9a-f]{64}"));
            return mutation;
        }
    }
}
