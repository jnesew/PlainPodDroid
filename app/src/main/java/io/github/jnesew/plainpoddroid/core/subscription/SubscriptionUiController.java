package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.feed.FeedParseException;
import io.github.jnesew.plainpoddroid.core.network.FeedAcquisitionException;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;



/**
 * Owns one screen's preview/confirmation state without retaining Android objects.
 *
 * <p>Public actions are expected on the callback executor's thread. Network and storage work is
 * always submitted to the worker executor. Generation checks make late results inert, and every
 * late preview is explicitly discarded so it cannot retain a confirmation capability.</p>
 */
public final class SubscriptionUiController implements AutoCloseable {
    public enum Phase {
        IDLE,
        PREVIEWING,
        READY_TO_CONFIRM,
        CONFIRMING,
        SUBSCRIBED,
        ERROR
    }

    public enum Failure {
        NONE,
        INPUT_REQUIRED,
        INPUT_TOO_LONG,
        INVALID_URL,
        FETCH_FAILED,
        LOGIN_REQUIRED,
        ACCESS_DENIED,
        INVALID_FEED,
        FEED_LIMIT,
        APPLE_LOOKUP_FAILED,
        APPLE_NO_PUBLIC_FEED,
        APPLE_LOGIN_UNSUPPORTED,
        TOO_MANY_PENDING_PREVIEWS,
        CONFIRMATION_EXPIRED,
        SAVE_FAILED,
        BACKGROUND_WORK_UNAVAILABLE
    }

    public interface Listener {
        void onSubscriptionStateChanged(State state);
    }

    public static final class State {
        private final Phase phase;
        private final Failure failure;
        private final SubscriptionPreview preview;
        private final long subscriptionId;

        private State(
                Phase phase,
                Failure failure,
                SubscriptionPreview preview,
                long subscriptionId) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.preview = preview;
            this.subscriptionId = subscriptionId;
        }

        public Phase getPhase() {
            return phase;
        }

        public Failure getFailure() {
            return failure;
        }

        public SubscriptionPreview getPreview() {
            return preview;
        }

        public long getSubscriptionId() {
            return subscriptionId;
        }
    }

    private final SubscriptionCoordinator coordinator;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Object lock = new Object();

    private Listener listener;
    private State state = idleState();
    private BasicFeedLogin pendingLogin;
    private SubscriptionPreview activePreview;
    private long generation;
    private boolean closed;

    public SubscriptionUiController(
            SubscriptionCoordinator coordinator,
            Executor workerExecutor,
            Executor callbackExecutor,
            Listener listener) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean startPreview(String input) {
        return startPreview(input, null);
    }

    public boolean startPreview(String input, BasicFeedLogin login) {
        String normalized = input == null ? "" : input.trim();
        final long requestGeneration;
        final SubscriptionPreview abandoned;
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase == Phase.CONFIRMING) {
                return false;
            }
            generation++;
            requestGeneration = generation;
            abandoned = activePreview;
            activePreview = null;
            if (normalized.isEmpty()) {
                nextState = errorState(Failure.INPUT_REQUIRED);
            } else if (normalized.length() > SecurityLimits.MAX_URL_CHARS) {
                nextState = errorState(Failure.INPUT_TOO_LONG);
            } else {
                nextState = new State(Phase.PREVIEWING, Failure.NONE, null, -1L);
            }
            if (pendingLogin != null) pendingLogin.revoke();
            pendingLogin = nextState.phase == Phase.PREVIEWING ? login : null;
            if (nextState.phase != Phase.PREVIEWING && login != null) login.revoke();
            state = nextState;
        }
        coordinator.discard(abandoned);
        dispatch(nextState);
        if (nextState.phase != Phase.PREVIEWING) {
            return true;
        }
        try {
            workerExecutor.execute(() -> runPreview(requestGeneration, normalized, login));
        } catch (RejectedExecutionException exception) {
            postPreviewFailure(requestGeneration, Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    public boolean confirm() {
        final long requestGeneration;
        final SubscriptionPreview preview;
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase != Phase.READY_TO_CONFIRM || activePreview == null) {
                return false;
            }
            requestGeneration = generation;
            preview = activePreview;
            pendingLogin = null; // Explicit confirmation transfers authority to the coordinator.
            activePreview = null;
            nextState = new State(Phase.CONFIRMING, Failure.NONE, preview, -1L);
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> runConfirmation(requestGeneration, preview));
        } catch (RejectedExecutionException exception) {
            // Confirmation was not submitted, so its one-shot capability remains live and must
            // be consumed locally rather than left pending after the UI reports failure.
            coordinator.discard(preview);
            postConfirmationFailure(
                    requestGeneration,
                    Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    public boolean cancel() {
        final SubscriptionPreview abandoned;
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase == Phase.CONFIRMING) {
                return false;
            }
            if (pendingLogin != null) pendingLogin.revoke();
            pendingLogin = null;
            generation++;
            abandoned = activePreview;
            activePreview = null;
            nextState = idleState();
            state = nextState;
        }
        coordinator.discard(abandoned);
        dispatch(nextState);
        return true;
    }

    @Override
    public void close() {
        final SubscriptionPreview abandoned;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (pendingLogin != null) pendingLogin.revoke();
            pendingLogin = null;
            generation++;
            abandoned = activePreview;
            activePreview = null;
            listener = null;
        }
        coordinator.discard(abandoned);
    }

    private void runPreview(long requestGeneration, String input,
            BasicFeedLogin login) {
        synchronized (lock) {
            if (closed || generation != requestGeneration || state.phase != Phase.PREVIEWING) return;
        }
        try {
            SubscriptionPreview preview = coordinator.preview(input, login, () -> {
                synchronized (lock) {
                    return !closed && generation == requestGeneration && state.phase == Phase.PREVIEWING;
                }
            });
            callbackExecutor.execute(() -> completePreview(requestGeneration, preview));
        } catch (NetworkPolicyException exception) {
            postPreviewFailure(requestGeneration, Failure.INVALID_URL);
        } catch (ApplePodcastLookupException exception) {
            postPreviewFailure(requestGeneration, switch (exception.getReason()) {
                case INVALID_LINK -> Failure.INVALID_URL;
                case NO_PUBLIC_FEED -> Failure.APPLE_NO_PUBLIC_FEED;
                case LOGIN_UNSUPPORTED -> Failure.APPLE_LOGIN_UNSUPPORTED;
                case UNAVAILABLE, INVALID_RESPONSE -> Failure.APPLE_LOOKUP_FAILED;
            });
        } catch (FeedAcquisitionException exception) {
            postPreviewFailure(requestGeneration, exception.getReason() == FeedAcquisitionException.Reason.RAW_BODY_LIMIT
                    || exception.getReason() == FeedAcquisitionException.Reason.EXPANDED_BODY_LIMIT ? Failure.FEED_LIMIT
                    : exception.getReason() == FeedAcquisitionException.Reason.LOGIN_REQUIRED
                    ? Failure.LOGIN_REQUIRED : exception.getReason() == FeedAcquisitionException.Reason.ACCESS_DENIED
                    ? Failure.ACCESS_DENIED : Failure.FETCH_FAILED);
        } catch (FeedParseException exception) {
            postPreviewFailure(requestGeneration, switch (exception.getReason()) {
                case INPUT_LIMIT, EPISODE_LIMIT, TEXT_LIMIT -> Failure.FEED_LIMIT;
                default -> Failure.INVALID_FEED;
            });
        } catch (SubscriptionWorkflowException exception) {
            Failure failure = exception.getReason()
                    == SubscriptionWorkflowException.Reason.PENDING_PREVIEW_LIMIT
                    ? Failure.TOO_MANY_PENDING_PREVIEWS
                    : Failure.FETCH_FAILED;
            postPreviewFailure(requestGeneration, failure);
        } catch (FeedLoginException exception) {
            postPreviewFailure(requestGeneration, Failure.LOGIN_REQUIRED);
        } catch (IOException exception) {
            postPreviewFailure(requestGeneration, Failure.FETCH_FAILED);
        }
    }

    private void runConfirmation(long requestGeneration, SubscriptionPreview preview) {
        try {
            long subscriptionId = coordinator.confirm(preview);
            callbackExecutor.execute(
                    () -> completeConfirmation(requestGeneration, subscriptionId));
        } catch (SubscriptionWorkflowException exception) {
            postConfirmationFailure(
                    requestGeneration,
                    Failure.CONFIRMATION_EXPIRED);
        } catch (IOException exception) {
            postConfirmationFailure(requestGeneration, Failure.SAVE_FAILED);
        }
    }

    private void postPreviewFailure(long requestGeneration, Failure failure) {
        callbackExecutor.execute(() -> completePreviewFailure(requestGeneration, failure));
    }

    private void postConfirmationFailure(long requestGeneration, Failure failure) {
        callbackExecutor.execute(
                () -> completeConfirmationFailure(requestGeneration, failure));
    }

    private void completePreview(long requestGeneration, SubscriptionPreview preview) {
        final State nextState;
        synchronized (lock) {
            if (closed
                    || generation != requestGeneration
                    || state.phase != Phase.PREVIEWING) {
                coordinator.discard(preview);
                return;
            }
            activePreview = preview;
            nextState = new State(Phase.READY_TO_CONFIRM, Failure.NONE, preview, -1L);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completePreviewFailure(long requestGeneration, Failure failure) {
        final State nextState;
        synchronized (lock) {
            if (closed
                    || generation != requestGeneration
                    || state.phase != Phase.PREVIEWING) {
                return;
            }
            nextState = errorState(failure);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completeConfirmation(long requestGeneration, long subscriptionId) {
        final State nextState;
        synchronized (lock) {
            if (closed
                    || generation != requestGeneration
                    || state.phase != Phase.CONFIRMING) {
                return;
            }
            nextState = new State(
                    Phase.SUBSCRIBED,
                    Failure.NONE,
                    null,
                    subscriptionId);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completeConfirmationFailure(long requestGeneration, Failure failure) {
        final State nextState;
        synchronized (lock) {
            if (closed
                    || generation != requestGeneration
                    || state.phase != Phase.CONFIRMING) {
                return;
            }
            nextState = errorState(failure);
            state = nextState;
        }
        notifyListener(nextState);
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
            target.onSubscriptionStateChanged(expectedState);
        }
    }

    private static State idleState() {
        return new State(Phase.IDLE, Failure.NONE, null, -1L);
    }

    private static State errorState(Failure failure) {
        return new State(Phase.ERROR, failure, null, -1L);
    }
}
