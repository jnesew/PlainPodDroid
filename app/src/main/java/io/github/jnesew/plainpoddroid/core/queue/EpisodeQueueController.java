package io.github.jnesew.plainpoddroid.core.queue;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/** Coordinates serialized durable queue reads and mutations without retaining Android objects. */
public final class EpisodeQueueController implements AutoCloseable {
    public enum Phase {
        IDLE,
        LOADING,
        READY,
        MUTATING,
        ERROR
    }

    public enum Operation {
        NONE,
        APPEND,
        PLAY_NEXT,
        REMOVE,
        MOVE
    }

    public enum Failure {
        NONE,
        INVALID_MEDIA_ID,
        EPISODE_NOT_FOUND,
        QUEUE_FULL,
        NOT_QUEUED,
        INVALID_POSITION,
        CORRUPT_STATE,
        STORAGE_FAILED,
        BACKGROUND_WORK_UNAVAILABLE
    }

    public interface Listener {
        void onEpisodeQueueStateChanged(State state);
    }

    public static final class State {
        private final Phase phase;
        private final Operation operation;
        private final Failure failure;
        private final EpisodeQueueMutation mutation;
        private final List<EpisodeQueueEntry> entries;
        private final String mediaId;

        private State(
                Phase phase,
                Operation operation,
                Failure failure,
                EpisodeQueueMutation mutation,
                List<EpisodeQueueEntry> entries,
                String mediaId) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.operation = Objects.requireNonNull(operation, "operation");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.mutation = Objects.requireNonNull(mutation, "mutation");
            this.entries = immutableCopy(entries);
            this.mediaId = Objects.requireNonNull(mediaId, "mediaId");
        }

        public Phase getPhase() {
            return phase;
        }

        public Operation getOperation() {
            return operation;
        }

        public Failure getFailure() {
            return failure;
        }

        public EpisodeQueueMutation getMutation() {
            return mutation;
        }

        public List<EpisodeQueueEntry> getEntries() {
            return entries;
        }

        public String getMediaId() {
            return mediaId;
        }

        public boolean isBusy() {
            return phase == Phase.LOADING || phase == Phase.MUTATING;
        }

        private static List<EpisodeQueueEntry> immutableCopy(
                List<EpisodeQueueEntry> values) {
            Objects.requireNonNull(values, "values");
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    private final EpisodeQueueStore store;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Object lock = new Object();

    private Listener listener;
    private State state = state(
            Phase.IDLE,
            Operation.NONE,
            Failure.NONE,
            EpisodeQueueMutation.NONE,
            Collections.emptyList(),
            "");
    private long generation;
    private boolean closed;

    public EpisodeQueueController(
            EpisodeQueueStore store,
            Executor workerExecutor,
            Executor callbackExecutor,
            Listener listener) {
        this.store = Objects.requireNonNull(store, "store");
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
            nextState = state(
                    Phase.LOADING,
                    Operation.NONE,
                    Failure.NONE,
                    EpisodeQueueMutation.NONE,
                    state.entries,
                    "");
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> load(requestGeneration));
        } catch (RejectedExecutionException exception) {
            postFailure(
                    requestGeneration,
                    Operation.NONE,
                    "",
                    Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    public boolean append(String mediaId) {
        return mutate(Operation.APPEND, mediaId, -1);
    }

    public boolean playNext(String mediaId) {
        return mutate(Operation.PLAY_NEXT, mediaId, 0);
    }

    public boolean remove(String mediaId) {
        return mutate(Operation.REMOVE, mediaId, -1);
    }

    public boolean move(String mediaId, int targetPosition) {
        return mutate(Operation.MOVE, mediaId, targetPosition);
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

    private boolean mutate(Operation operation, String mediaId, int targetPosition) {
        final long requestGeneration;
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase != Phase.READY) {
                return false;
            }
            Failure validationFailure = validate(operation, mediaId, targetPosition);
            generation++;
            requestGeneration = generation;
            if (validationFailure != Failure.NONE) {
                nextState = state(
                        Phase.ERROR,
                        operation,
                        validationFailure,
                        EpisodeQueueMutation.NONE,
                        state.entries,
                        safeMediaId(mediaId));
                state = nextState;
                dispatch(nextState);
                return false;
            }
            nextState = state(
                    Phase.MUTATING,
                    operation,
                    Failure.NONE,
                    EpisodeQueueMutation.NONE,
                    state.entries,
                    mediaId);
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> runMutation(
                    requestGeneration,
                    operation,
                    mediaId,
                    targetPosition));
        } catch (RejectedExecutionException exception) {
            postFailure(
                    requestGeneration,
                    operation,
                    mediaId,
                    Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    private void load(long requestGeneration) {
        try {
            List<EpisodeQueueEntry> entries = checkedSnapshot(store.listQueue());
            callbackExecutor.execute(() -> complete(
                    requestGeneration,
                    Operation.NONE,
                    "",
                    EpisodeQueueMutation.NONE,
                    entries));
        } catch (EpisodeQueueException exception) {
            postFailure(
                    requestGeneration,
                    Operation.NONE,
                    "",
                    mapFailure(exception.getReason()));
        } catch (IOException | RuntimeException exception) {
            postFailure(
                    requestGeneration,
                    Operation.NONE,
                    "",
                    Failure.STORAGE_FAILED);
        }
    }

    private void runMutation(
            long requestGeneration,
            Operation operation,
            String mediaId,
            int targetPosition) {
        try {
            EpisodeQueueMutation mutation;
            switch (operation) {
                case APPEND:
                    mutation = store.append(mediaId);
                    break;
                case PLAY_NEXT:
                    mutation = store.playNext(mediaId);
                    break;
                case REMOVE:
                    mutation = store.remove(mediaId);
                    break;
                case MOVE:
                    mutation = store.move(mediaId, targetPosition);
                    break;
                case NONE:
                default:
                    throw new AssertionError("Invalid queue operation.");
            }
            if (mutation == null || mutation == EpisodeQueueMutation.NONE) {
                postFailure(
                        requestGeneration,
                        operation,
                        mediaId,
                        Failure.CORRUPT_STATE);
                return;
            }
            List<EpisodeQueueEntry> entries = checkedSnapshot(store.listQueue());
            callbackExecutor.execute(() -> complete(
                    requestGeneration,
                    operation,
                    mediaId,
                    mutation,
                    entries));
        } catch (EpisodeQueueException exception) {
            postFailure(
                    requestGeneration,
                    operation,
                    mediaId,
                    mapFailure(exception.getReason()));
        } catch (IOException | RuntimeException exception) {
            postFailure(
                    requestGeneration,
                    operation,
                    mediaId,
                    Failure.STORAGE_FAILED);
        }
    }

    private void postFailure(
            long requestGeneration,
            Operation operation,
            String mediaId,
            Failure failure) {
        callbackExecutor.execute(() -> completeFailure(
                requestGeneration,
                operation,
                mediaId,
                failure));
    }

    private void complete(
            long requestGeneration,
            Operation operation,
            String mediaId,
            EpisodeQueueMutation mutation,
            List<EpisodeQueueEntry> entries) {
        final State nextState;
        synchronized (lock) {
            if (closed || generation != requestGeneration || !state.isBusy()) {
                return;
            }
            nextState = state(
                    Phase.READY,
                    operation,
                    Failure.NONE,
                    mutation,
                    entries,
                    mediaId);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completeFailure(
            long requestGeneration,
            Operation operation,
            String mediaId,
            Failure failure) {
        final State nextState;
        synchronized (lock) {
            if (closed || generation != requestGeneration || !state.isBusy()) {
                return;
            }
            nextState = state(
                    Phase.ERROR,
                    operation,
                    failure,
                    EpisodeQueueMutation.NONE,
                    state.entries,
                    mediaId);
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
            currentListener.onEpisodeQueueStateChanged(nextState);
        }
    }

    private static Failure validate(
            Operation operation,
            String mediaId,
            int targetPosition) {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            return Failure.INVALID_MEDIA_ID;
        }
        if (operation == Operation.MOVE
                && (targetPosition < 0
                        || targetPosition >= SecurityLimits.MAX_QUEUE_ENTRIES)) {
            return Failure.INVALID_POSITION;
        }
        return Failure.NONE;
    }

    private static List<EpisodeQueueEntry> checkedSnapshot(
            List<EpisodeQueueEntry> entries) throws EpisodeQueueException {
        if (entries == null || entries.size() > SecurityLimits.MAX_QUEUE_ENTRIES) {
            throw corruptState();
        }
        List<EpisodeQueueEntry> result = new ArrayList<>(entries.size());
        Set<String> mediaIds = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            EpisodeQueueEntry entry = entries.get(index);
            if (entry == null
                    || entry.getPosition() != index
                    || !mediaIds.add(entry.getMediaId())) {
                throw corruptState();
            }
            result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    private static EpisodeQueueException corruptState() {
        return new EpisodeQueueException(
                EpisodeQueueException.Reason.CORRUPT_STATE,
                "Queue state was inconsistent.");
    }

    private static Failure mapFailure(EpisodeQueueException.Reason reason) {
        switch (reason) {
            case INVALID_MEDIA_ID:
                return Failure.INVALID_MEDIA_ID;
            case EPISODE_NOT_FOUND:
                return Failure.EPISODE_NOT_FOUND;
            case QUEUE_FULL:
                return Failure.QUEUE_FULL;
            case NOT_QUEUED:
                return Failure.NOT_QUEUED;
            case INVALID_POSITION:
                return Failure.INVALID_POSITION;
            case CORRUPT_STATE:
                return Failure.CORRUPT_STATE;
            case STORAGE_FAILURE:
            default:
                return Failure.STORAGE_FAILED;
        }
    }

    private static String safeMediaId(String mediaId) {
        return mediaId != null && MEDIA_ID.matcher(mediaId).matches() ? mediaId : "";
    }

    private static State state(
            Phase phase,
            Operation operation,
            Failure failure,
            EpisodeQueueMutation mutation,
            List<EpisodeQueueEntry> entries,
            String mediaId) {
        return new State(phase, operation, failure, mutation, entries, mediaId);
    }
}
