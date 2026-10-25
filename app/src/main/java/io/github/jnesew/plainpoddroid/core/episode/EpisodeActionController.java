package io.github.jnesew.plainpoddroid.core.episode;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/** Coordinates explicit local episode actions without retaining an Android object. */
public final class EpisodeActionController implements AutoCloseable {
    public enum Phase {
        IDLE,
        LOADING_DETAILS,
        DETAILS_READY,
        UPDATING,
        SUCCEEDED,
        ERROR
    }

    public enum Operation {
        NONE,
        LOAD_DETAILS,
        MARK_PLAYED,
        MARK_UNPLAYED,
        FAVORITE,
        UNFAVORITE
    }

    public enum Failure {
        NONE,
        INVALID_MEDIA_ID,
        EPISODE_NOT_FOUND,
        STORAGE_FAILED,
        BACKGROUND_WORK_UNAVAILABLE
    }

    public interface Listener {
        void onEpisodeActionStateChanged(State state);
    }

    public static final class State {
        private final Phase phase;
        private final Operation operation;
        private final Failure failure;
        private final EpisodeStateMutation mutation;
        private final EpisodeDetails details;
        private final String mediaId;

        private State(
                Phase phase,
                Operation operation,
                Failure failure,
                EpisodeStateMutation mutation,
                EpisodeDetails details,
                String mediaId) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.operation = Objects.requireNonNull(operation, "operation");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.mutation = mutation;
            this.details = details;
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

        public EpisodeStateMutation getMutation() {
            return mutation;
        }

        public EpisodeDetails getDetails() {
            return details;
        }

        public String getMediaId() {
            return mediaId;
        }

        public boolean isBusy() {
            return phase == Phase.LOADING_DETAILS || phase == Phase.UPDATING;
        }
    }

    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    private final EpisodeActionStore store;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Object lock = new Object();

    private Listener listener;
    private State state = state(Phase.IDLE, Operation.NONE, Failure.NONE, null, null, "");
    private long generation;
    private boolean closed;

    public EpisodeActionController(
            EpisodeActionStore store,
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

    public boolean loadDetails(String mediaId) {
        return start(Operation.LOAD_DETAILS, mediaId);
    }

    public boolean setPlayed(String mediaId, boolean played) {
        return start(played ? Operation.MARK_PLAYED : Operation.MARK_UNPLAYED, mediaId);
    }

    public boolean setFavorite(String mediaId, boolean favorite) {
        return start(favorite ? Operation.FAVORITE : Operation.UNFAVORITE, mediaId);
    }

    public boolean reset() {
        final State nextState;
        synchronized (lock) {
            if (closed || state.isBusy()) {
                return false;
            }
            generation++;
            nextState = state(
                    Phase.IDLE,
                    Operation.NONE,
                    Failure.NONE,
                    null,
                    null,
                    "");
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

    private boolean start(Operation operation, String mediaId) {
        final long requestGeneration;
        final State nextState;
        synchronized (lock) {
            if (closed || state.isBusy()) {
                return false;
            }
            generation++;
            requestGeneration = generation;
            if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
                nextState = state(
                        Phase.ERROR,
                        operation,
                        Failure.INVALID_MEDIA_ID,
                        null,
                        null,
                        "");
                state = nextState;
                dispatch(nextState);
                return false;
            }
            EpisodeDetails retainedDetails = state.details != null
                    && mediaId.equals(state.details.getMediaId())
                    ? state.details
                    : null;
            nextState = state(
                    operation == Operation.LOAD_DETAILS
                            ? Phase.LOADING_DETAILS
                            : Phase.UPDATING,
                    operation,
                    Failure.NONE,
                    null,
                    retainedDetails,
                    mediaId);
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> run(requestGeneration, operation, mediaId));
        } catch (RejectedExecutionException exception) {
            postFailure(
                    requestGeneration,
                    operation,
                    mediaId,
                    Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    private void run(long requestGeneration, Operation operation, String mediaId) {
        try {
            if (operation == Operation.LOAD_DETAILS) {
                EpisodeDetails details = store.findDetails(mediaId);
                if (details == null) {
                    postFailure(
                            requestGeneration,
                            operation,
                            mediaId,
                            Failure.EPISODE_NOT_FOUND);
                    return;
                }
                if (!mediaId.equals(details.getMediaId())) {
                    postFailure(
                            requestGeneration,
                            operation,
                            mediaId,
                            Failure.STORAGE_FAILED);
                    return;
                }
                callbackExecutor.execute(() -> completeDetails(
                        requestGeneration,
                        mediaId,
                        details));
                return;
            }
            EpisodeStateMutation mutation;
            switch (operation) {
                case MARK_PLAYED:
                    mutation = store.setPlayed(mediaId, true);
                    break;
                case MARK_UNPLAYED:
                    mutation = store.setPlayed(mediaId, false);
                    break;
                case FAVORITE:
                    mutation = store.setFavorite(mediaId, true);
                    break;
                case UNFAVORITE:
                    mutation = store.setFavorite(mediaId, false);
                    break;
                case LOAD_DETAILS:
                case NONE:
                default:
                    throw new AssertionError("Invalid episode action.");
            }
            if (mutation == null) {
                postFailure(
                        requestGeneration,
                        operation,
                        mediaId,
                        Failure.STORAGE_FAILED);
                return;
            }
            callbackExecutor.execute(() -> completeMutation(
                    requestGeneration,
                    operation,
                    mediaId,
                    mutation));
        } catch (EpisodeActionException exception) {
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

    private void completeDetails(
            long requestGeneration,
            String mediaId,
            EpisodeDetails details) {
        final State nextState;
        synchronized (lock) {
            if (closed
                    || generation != requestGeneration
                    || state.phase != Phase.LOADING_DETAILS) {
                return;
            }
            nextState = state(
                    Phase.DETAILS_READY,
                    Operation.LOAD_DETAILS,
                    Failure.NONE,
                    null,
                    details,
                    mediaId);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completeMutation(
            long requestGeneration,
            Operation operation,
            String mediaId,
            EpisodeStateMutation mutation) {
        final State nextState;
        synchronized (lock) {
            if (closed || generation != requestGeneration || state.phase != Phase.UPDATING) {
                return;
            }
            EpisodeDetails updatedDetails = updatedDetails(state.details, operation);
            nextState = state(
                    Phase.SUCCEEDED,
                    operation,
                    Failure.NONE,
                    mutation,
                    updatedDetails,
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
                    null,
                    shouldRetainDetails(operation, failure) ? state.details : null,
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
            currentListener.onEpisodeActionStateChanged(nextState);
        }
    }

    private static Failure mapFailure(EpisodeActionException.Reason reason) {
        switch (reason) {
            case INVALID_MEDIA_ID:
                return Failure.INVALID_MEDIA_ID;
            case EPISODE_NOT_FOUND:
                return Failure.EPISODE_NOT_FOUND;
            case STORAGE_FAILURE:
            default:
                return Failure.STORAGE_FAILED;
        }
    }

    private static EpisodeDetails updatedDetails(
            EpisodeDetails details,
            Operation operation) {
        if (details == null) {
            return null;
        }
        switch (operation) {
            case MARK_PLAYED:
                return details.withPlayed(true);
            case MARK_UNPLAYED:
                return details.withPlayed(false);
            case FAVORITE:
                return details.withFavorite(true);
            case UNFAVORITE:
                return details.withFavorite(false);
            case LOAD_DETAILS:
            case NONE:
            default:
                throw new AssertionError("Invalid episode mutation.");
        }
    }

    private static boolean shouldRetainDetails(
            Operation operation,
            Failure failure) {
        return operation != Operation.LOAD_DETAILS
                && (failure == Failure.STORAGE_FAILED
                        || failure == Failure.BACKGROUND_WORK_UNAVAILABLE);
    }

    private static State state(
            Phase phase,
            Operation operation,
            Failure failure,
            EpisodeStateMutation mutation,
            EpisodeDetails details,
            String mediaId) {
        return new State(phase, operation, failure, mutation, details, mediaId);
    }
}
