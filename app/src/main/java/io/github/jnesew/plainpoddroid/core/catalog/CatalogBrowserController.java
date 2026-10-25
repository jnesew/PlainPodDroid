package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Coordinates bounded read-only catalog queries without retaining an Android object. */
public final class CatalogBrowserController implements AutoCloseable {
    public enum Phase {
        IDLE,
        LOADING_SUBSCRIPTIONS,
        EMPTY,
        SUBSCRIPTIONS_READY,
        LOADING_EPISODES,
        EPISODES_READY,
        LOADING_MORE,
        ERROR
    }

    public enum Failure {
        NONE,
        LOAD_FAILED,
        INVALID_SELECTION,
        BACKGROUND_WORK_UNAVAILABLE
    }

    public interface Listener {
        void onCatalogBrowserStateChanged(State state);
    }

    public static final class State {
        private final Phase phase;
        private final Failure failure;
        private final List<CatalogSubscriptionSummary> subscriptions;
        private final List<CatalogEpisodeSummary> episodes;
        private final long selectedSubscriptionId;
        private final boolean hasMore;

        private State(
                Phase phase,
                Failure failure,
                List<CatalogSubscriptionSummary> subscriptions,
                List<CatalogEpisodeSummary> episodes,
                long selectedSubscriptionId,
                boolean hasMore) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.subscriptions = immutableCopy(subscriptions);
            this.episodes = immutableCopy(episodes);
            this.selectedSubscriptionId = selectedSubscriptionId;
            this.hasMore = hasMore;
        }

        public Phase getPhase() {
            return phase;
        }

        public Failure getFailure() {
            return failure;
        }

        public List<CatalogSubscriptionSummary> getSubscriptions() {
            return subscriptions;
        }

        public List<CatalogEpisodeSummary> getEpisodes() {
            return episodes;
        }

        public long getSelectedSubscriptionId() {
            return selectedSubscriptionId;
        }

        public boolean hasMore() {
            return hasMore;
        }

        private static <T> List<T> immutableCopy(List<T> values) {
            Objects.requireNonNull(values, "values");
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    private final CatalogBrowserStore store;
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Object lock = new Object();

    private Listener listener;
    private State state = state(Phase.IDLE, Failure.NONE);
    private CatalogBrowseQuery query = CatalogBrowseQuery.DEFAULT;
    private long generation;
    private boolean closed;
    private boolean queryInvalidated;
    private long displayedSnapshotGeneration;
    private long acknowledgedSubscriptionId;
    private long acknowledgedSnapshotGeneration;

    public CatalogBrowserController(
            CatalogBrowserStore store,
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

    public CatalogBrowseQuery getQuery() {
        synchronized (lock) {
            return query;
        }
    }

    /** The UI calls only after successfully displaying this podcast's episode list. */
    public boolean acknowledgeViewed() {
        final long subscriptionId, snapshot, ticket;
        synchronized (lock) {
            if (closed || state.phase != Phase.EPISODES_READY || displayedSnapshotGeneration < 1L) return false;
            subscriptionId = state.selectedSubscriptionId;
            snapshot = displayedSnapshotGeneration;
            if (acknowledgedSubscriptionId == subscriptionId && acknowledgedSnapshotGeneration >= snapshot) return true;
            acknowledgedSubscriptionId = subscriptionId; acknowledgedSnapshotGeneration = snapshot;
            ticket = generation;
        }
        try {
            workerExecutor.execute(() -> {
                try {
                    store.markSubscriptionViewed(subscriptionId, snapshot);
                    List<CatalogSubscriptionSummary> subscriptions = store.listSubscriptions();
                    callbackExecutor.execute(() -> {
                        State next;
                        synchronized (lock) {
                            if (closed || generation != ticket) return;
                            next = new State(state.phase, state.failure, subscriptions, state.episodes,
                                    state.selectedSubscriptionId, state.hasMore);
                            state = next;
                        }
                        notifyListener(next);
                    });
                } catch (IOException | RejectedExecutionException e) { retryAcknowledgement(subscriptionId, snapshot); }
            });
            return true;
        } catch (RejectedExecutionException e) { retryAcknowledgement(subscriptionId, snapshot); return false; }
    }

    private void retryAcknowledgement(long subscriptionId, long snapshot) {
        synchronized (lock) {
            if (acknowledgedSubscriptionId == subscriptionId && acknowledgedSnapshotGeneration == snapshot)
                acknowledgedSnapshotGeneration = 0L;
        }
    }

    /** Replaces the page set so a filter change cannot append an old page. */
    public boolean applyQuery(CatalogBrowseQuery requested) {
        Objects.requireNonNull(requested, "requested");
        final State nextState;
        final long requestGeneration;
        synchronized (lock) {
            if (closed) return false;
            if (query.equals(requested) && !queryInvalidated && state.phase != Phase.ERROR) return true;
            queryInvalidated=false;
            query = requested;
            if (state.selectedSubscriptionId < 1L) return true;
            requestGeneration = ++generation;
            nextState = new State(Phase.LOADING_EPISODES, Failure.NONE,
                    state.subscriptions, state.episodes,
                    state.selectedSubscriptionId, false);
            state = nextState;
        }
        dispatch(nextState);
        submitEpisodes(requestGeneration, nextState.selectedSubscriptionId, 0, false,
                requested);
        return true;
    }

    /** A typed edit invalidates late results even during the debounce interval. */
    public void invalidateEpisodeRead() {
        synchronized (lock) {
            if (closed || (state.phase != Phase.LOADING_EPISODES && state.phase != Phase.LOADING_MORE)) return;
            generation++;
            queryInvalidated=true;
        }
    }

    public boolean loadSubscriptions() {
        final long requestGeneration;
        final State nextState;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            generation++;
            queryInvalidated=false;
            requestGeneration = generation;
            nextState = state(Phase.LOADING_SUBSCRIPTIONS, Failure.NONE);
            state = nextState;
        }
        dispatch(nextState);
        try {
            workerExecutor.execute(() -> runSubscriptions(requestGeneration));
        } catch (RejectedExecutionException exception) {
            postFailure(requestGeneration, Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
        return true;
    }

    public boolean selectSubscription(long subscriptionId) {
        final long requestGeneration;
        final CatalogBrowseQuery requested;
        final List<CatalogSubscriptionSummary> subscriptions;
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase == Phase.LOADING_SUBSCRIPTIONS) {
                return false;
            }
            queryInvalidated=false;
            subscriptions = state.subscriptions;
            displayedSnapshotGeneration = 0L;
            if (!contains(subscriptions, subscriptionId)) {
                generation++;
                nextState = new State(
                        Phase.ERROR,
                        Failure.INVALID_SELECTION,
                        subscriptions,
                        Collections.emptyList(),
                        -1L,
                        false);
                state = nextState;
                dispatch(nextState);
                return false;
            }
            generation++;
            requestGeneration = generation;
            requested = query;
            nextState = new State(
                    Phase.LOADING_EPISODES,
                    Failure.NONE,
                    subscriptions,
                    Collections.emptyList(),
                    subscriptionId,
                    false);
            state = nextState;
        }
        dispatch(nextState);
        submitEpisodes(requestGeneration, subscriptionId, 0, false, requested);
        return true;
    }

    public boolean loadMore() {
        final long requestGeneration;
        final long subscriptionId;
        final int offset;
        final CatalogBrowseQuery requested;
        final State nextState;
        synchronized (lock) {
            if (closed || state.phase != Phase.EPISODES_READY || !state.hasMore) {
                return false;
            }
            generation++;
            requestGeneration = generation;
            subscriptionId = state.selectedSubscriptionId;
            offset = state.episodes.size();
            requested = query;
            nextState = new State(
                    Phase.LOADING_MORE,
                    Failure.NONE,
                    state.subscriptions,
                    state.episodes,
                    subscriptionId,
                    true);
            state = nextState;
        }
        dispatch(nextState);
        submitEpisodes(requestGeneration, subscriptionId, offset, true, requested);
        return true;
    }

    private void submitEpisodes(long requestGeneration, long subscriptionId,
            int offset, boolean append, CatalogBrowseQuery requested) {
        try {
            workerExecutor.execute(() -> runEpisodes(
                    requestGeneration,
                    subscriptionId,
                    offset,
                    append,
                    requested));
        } catch (RejectedExecutionException exception) {
            postFailure(requestGeneration, Failure.BACKGROUND_WORK_UNAVAILABLE);
        }
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

    private void runSubscriptions(long requestGeneration) {
        synchronized (lock) {
            if (closed || generation != requestGeneration) return;
        }
        try {
            List<CatalogSubscriptionSummary> subscriptions = store.listSubscriptions();
            callbackExecutor.execute(
                    () -> completeSubscriptions(requestGeneration, subscriptions));
        } catch (IOException exception) {
            postFailure(requestGeneration, Failure.LOAD_FAILED);
        }
    }

    private void runEpisodes(
            long requestGeneration,
            long subscriptionId,
            int offset,
            boolean append,
            CatalogBrowseQuery requested) {
        synchronized (lock) {
            if (closed || generation != requestGeneration) return;
        }
        try {
            CatalogEpisodePage page = store.listEpisodes(
                    subscriptionId,
                    offset,
                    SecurityLimits.CATALOG_BROWSE_PAGE_SIZE,
                    requested);
            callbackExecutor.execute(
                    () -> completeEpisodes(requestGeneration, page, append));
        } catch (IOException exception) {
            postFailure(requestGeneration, Failure.LOAD_FAILED);
        }
    }

    private void completeSubscriptions(
            long requestGeneration,
            List<CatalogSubscriptionSummary> subscriptions) {
        final State nextState;
        synchronized (lock) {
            if (closed
                    || generation != requestGeneration
                    || state.phase != Phase.LOADING_SUBSCRIPTIONS) {
                return;
            }
            nextState = new State(
                    subscriptions.isEmpty() ? Phase.EMPTY : Phase.SUBSCRIPTIONS_READY,
                    Failure.NONE,
                    subscriptions,
                    Collections.emptyList(),
                    -1L,
                    false);
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void completeEpisodes(
            long requestGeneration,
            CatalogEpisodePage page,
            boolean append) {
        final State nextState;
        synchronized (lock) {
            Phase expected = append ? Phase.LOADING_MORE : Phase.LOADING_EPISODES;
            if (closed
                    || generation != requestGeneration
                    || state.phase != expected
                    || state.selectedSubscriptionId != page.getSubscriptionId()
                    || (append && page.getOffset() != state.episodes.size())
                    || (!append && page.getOffset() != 0)) {
                return;
            }
            List<CatalogEpisodeSummary> episodes = append
                    ? concatenate(state.episodes, page.getEpisodes())
                    : page.getEpisodes();
            if (!append) displayedSnapshotGeneration = page.getSnapshotGeneration();
            nextState = new State(
                    Phase.EPISODES_READY,
                    Failure.NONE,
                    state.subscriptions,
                    episodes,
                    page.getSubscriptionId(),
                    page.hasMore());
            state = nextState;
        }
        notifyListener(nextState);
    }

    private void postFailure(long requestGeneration, Failure failure) {
        callbackExecutor.execute(() -> completeFailure(requestGeneration, failure));
    }

    private void completeFailure(long requestGeneration, Failure failure) {
        final State nextState;
        synchronized (lock) {
            if (closed || generation != requestGeneration || !isLoading(state.phase)) {
                return;
            }
            nextState = new State(
                    Phase.ERROR,
                    failure,
                    state.subscriptions,
                    state.episodes,
                    state.selectedSubscriptionId,
                    false);
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
            target.onCatalogBrowserStateChanged(expectedState);
        }
    }

    private static boolean contains(
            List<CatalogSubscriptionSummary> subscriptions,
            long subscriptionId) {
        for (CatalogSubscriptionSummary subscription : subscriptions) {
            if (subscription.getSubscriptionId() == subscriptionId) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoading(Phase phase) {
        return phase == Phase.LOADING_SUBSCRIPTIONS
                || phase == Phase.LOADING_EPISODES
                || phase == Phase.LOADING_MORE;
    }

    private static List<CatalogEpisodeSummary> concatenate(
            List<CatalogEpisodeSummary> first,
            List<CatalogEpisodeSummary> second) {
        if (first.size() + second.size() > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION) {
            throw new IllegalArgumentException("Catalog browse result exceeded its bound.");
        }
        List<CatalogEpisodeSummary> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static State state(Phase phase, Failure failure) {
        return new State(
                phase,
                failure,
                Collections.emptyList(),
                Collections.emptyList(),
                -1L,
                false);
    }
}
