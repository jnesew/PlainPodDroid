package io.github.jnesew.plainpoddroid.core.episode;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class EpisodeActionControllerTest {
    @Test
    public void detailsEnforceBoundedInertState() throws Exception {
        EpisodeDetails details = details(1);

        assertEquals(mediaId(1), details.getMediaId());
        assertTrue(details.isChapterReferenceAvailable());
        assertTrue(details.withFavorite(true).isChapterReferenceAvailable());
        assertTrue(details.withPlayed(true).isChapterReferenceAvailable());
        assertEquals("https://episodes.example/1", details.getWebsiteUrl().toString());
        assertThrows(IllegalArgumentException.class, () -> new EpisodeDetails(
                "https://secret.example/item",
                "Title",
                "Author",
                "Notes",
                null,
                "Published",
                1L,
                0L,
                false,
                false,
                false,
                false,
                false,
                false));
        assertThrows(IllegalArgumentException.class, () -> new EpisodeDetails(
                mediaId(1),
                "Title",
                "Author",
                "x".repeat(SecurityLimits.MAX_PLAIN_NOTES_CHARS + 1),
                null,
                "Published",
                1L,
                0L,
                false,
                false,
                false,
                false,
                false,
                false));
    }

    @Test
    public void detailsLoadPublishesOnlyMatchingEpisode() throws Exception {
        FakeStore store = new FakeStore();
        store.details = details(1);
        QueuedExecutor worker = new QueuedExecutor();
        List<EpisodeActionController.State> states = new ArrayList<>();
        EpisodeActionController controller = controller(store, worker, states);

        assertTrue(controller.loadDetails(mediaId(1)));
        assertEquals(EpisodeActionController.Phase.LOADING_DETAILS,
                controller.getState().getPhase());
        assertFalse(controller.loadDetails(mediaId(2)));
        worker.runNext();

        assertEquals(EpisodeActionController.Phase.DETAILS_READY,
                controller.getState().getPhase());
        assertEquals(mediaId(1), controller.getState().getDetails().getMediaId());
        assertEquals(2, states.size());
    }

    @Test
    public void explicitMutationsUseOnlyOpaqueMediaId() {
        FakeStore store = new FakeStore();
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeActionController controller = controller(store, worker, new ArrayList<>());

        assertTrue(controller.setPlayed(mediaId(1), true));
        worker.runNext();
        assertEquals(EpisodeActionController.Operation.MARK_PLAYED, store.lastOperation);
        assertTrue(store.lastValue);
        assertEquals(EpisodeStateMutation.UPDATED, controller.getState().getMutation());

        assertTrue(controller.setPlayed(mediaId(1), false));
        worker.runNext();
        assertEquals(EpisodeActionController.Operation.MARK_UNPLAYED, store.lastOperation);
        assertFalse(store.lastValue);

        assertTrue(controller.setFavorite(mediaId(1), true));
        worker.runNext();
        assertEquals(EpisodeActionController.Operation.FAVORITE, store.lastOperation);

        assertTrue(controller.setFavorite(mediaId(1), false));
        worker.runNext();
        assertEquals(EpisodeActionController.Operation.UNFAVORITE, store.lastOperation);
    }

    @Test
    public void loadedDetailsRemainStableAndReflectSuccessfulLocalMutations()
            throws Exception {
        FakeStore store = new FakeStore();
        store.details = details(1);
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeActionController controller = controller(store, worker, new ArrayList<>());
        assertTrue(controller.loadDetails(mediaId(1)));
        worker.runNext();

        assertTrue(controller.setPlayed(mediaId(1), true));
        assertNotNull(controller.getState().getDetails());
        worker.runNext();
        assertTrue(controller.getState().getDetails().isPlayed());
        assertEquals(1_000L, controller.getState().getDetails()
                .getPlaybackPositionMillis());

        assertTrue(controller.setPlayed(mediaId(1), false));
        worker.runNext();
        assertFalse(controller.getState().getDetails().isPlayed());
        assertEquals(0L, controller.getState().getDetails()
                .getPlaybackPositionMillis());

        assertTrue(controller.setFavorite(mediaId(1), true));
        worker.runNext();
        assertTrue(controller.getState().getDetails().isFavorite());
        assertEquals("Plain notes", controller.getState().getDetails().getNotes());
    }

    @Test
    public void invalidCallerTextIsRejectedAndDiscarded() {
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeActionController controller = controller(
                new FakeStore(),
                worker,
                new ArrayList<>());

        assertFalse(controller.loadDetails("https://private.example/episode"));

        assertEquals(EpisodeActionController.Failure.INVALID_MEDIA_ID,
                controller.getState().getFailure());
        assertEquals("", controller.getState().getMediaId());
        assertNull(controller.getState().getDetails());
        assertTrue(worker.isEmpty());
    }

    @Test
    public void missingAndTypedFailuresAreRedacted() throws Exception {
        FakeStore missing = new FakeStore();
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeActionController controller = controller(missing, worker, new ArrayList<>());
        assertTrue(controller.loadDetails(mediaId(1)));
        worker.runNext();
        assertEquals(EpisodeActionController.Failure.EPISODE_NOT_FOUND,
                controller.getState().getFailure());

        FakeStore failed = new FakeStore();
        failed.failure = new EpisodeActionException(
                EpisodeActionException.Reason.STORAGE_FAILURE,
                "sensitive database detail");
        controller = controller(failed, worker, new ArrayList<>());
        assertTrue(controller.setFavorite(mediaId(1), true));
        worker.runNext();
        assertEquals(EpisodeActionController.Failure.STORAGE_FAILED,
                controller.getState().getFailure());
    }

    @Test
    public void missingMutationClearsAStaleLoadedDetail() throws Exception {
        FakeStore store = new FakeStore();
        store.details = details(1);
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeActionController controller = controller(store, worker, new ArrayList<>());
        assertTrue(controller.loadDetails(mediaId(1)));
        worker.runNext();

        store.failure = new EpisodeActionException(
                EpisodeActionException.Reason.EPISODE_NOT_FOUND,
                "sensitive missing-row detail");
        assertTrue(controller.setFavorite(mediaId(1), true));
        worker.runNext();

        assertEquals(EpisodeActionController.Failure.EPISODE_NOT_FOUND,
                controller.getState().getFailure());
        assertNull(controller.getState().getDetails());
    }

    @Test
    public void rejectedWorkerAndCloseSuppressStaleCompletion() {
        Executor rejecting = ignored -> {
            throw new RejectedExecutionException("closed");
        };
        EpisodeActionController rejected = new EpisodeActionController(
                new FakeStore(),
                rejecting,
                Runnable::run,
                ignored -> { });
        assertTrue(rejected.setPlayed(mediaId(1), true));
        assertEquals(EpisodeActionController.Failure.BACKGROUND_WORK_UNAVAILABLE,
                rejected.getState().getFailure());

        QueuedExecutor worker = new QueuedExecutor();
        List<EpisodeActionController.State> states = new ArrayList<>();
        EpisodeActionController closed = controller(new FakeStore(), worker, states);
        assertTrue(closed.setPlayed(mediaId(1), true));
        closed.close();
        worker.runNext();
        assertEquals(1, states.size());
        assertFalse(closed.reset());
    }

    @Test
    public void resetClearsCompletedDetailsWithoutInterruptingWork() throws Exception {
        FakeStore store = new FakeStore();
        store.details = details(1);
        QueuedExecutor worker = new QueuedExecutor();
        EpisodeActionController controller = controller(store, worker, new ArrayList<>());
        assertTrue(controller.loadDetails(mediaId(1)));
        assertFalse(controller.reset());
        worker.runNext();

        assertTrue(controller.reset());
        assertEquals(EpisodeActionController.Phase.IDLE,
                controller.getState().getPhase());
        assertNull(controller.getState().getDetails());
    }

    private static EpisodeActionController controller(
            FakeStore store,
            Executor worker,
            List<EpisodeActionController.State> states) {
        return new EpisodeActionController(store, worker, Runnable::run, states::add);
    }

    private static EpisodeDetails details(int value) throws Exception {
        return new EpisodeDetails(
                mediaId(value),
                "Episode " + value,
                "Author",
                "Plain notes",
                CanonicalHttpsUrl.parse("https://episodes.example/" + value),
                "2026-09-05",
                60_000L,
                1_000L,
                false,
                false,
                false,
                false,
                false,
                true);
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

    private static final class FakeStore implements EpisodeActionStore {
        private EpisodeDetails details;
        private EpisodeActionException failure;
        private EpisodeActionController.Operation lastOperation;
        private boolean lastValue;

        @Override
        public EpisodeDetails findDetails(String mediaId) throws IOException {
            if (failure != null) {
                throw failure;
            }
            return details;
        }

        @Override
        public EpisodeStateMutation setPlayed(String mediaId, boolean played)
                throws EpisodeActionException {
            lastOperation = played
                    ? EpisodeActionController.Operation.MARK_PLAYED
                    : EpisodeActionController.Operation.MARK_UNPLAYED;
            return mutate(mediaId, played);
        }

        @Override
        public EpisodeStateMutation setFavorite(String mediaId, boolean favorite)
                throws EpisodeActionException {
            lastOperation = favorite
                    ? EpisodeActionController.Operation.FAVORITE
                    : EpisodeActionController.Operation.UNFAVORITE;
            return mutate(mediaId, favorite);
        }

        private EpisodeStateMutation mutate(String mediaId, boolean value)
                throws EpisodeActionException {
            if (failure != null) {
                throw failure;
            }
            assertTrue(mediaId.matches("episode-[0-9a-f]{64}"));
            lastValue = value;
            return EpisodeStateMutation.UPDATED;
        }
    }
}
