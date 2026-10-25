package io.github.jnesew.plainpoddroid.core.catalog;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CatalogBrowserControllerTest {
    @Test
    public void subscriptionAndEpisodeQueriesRunOnlyOnWorker() {
        Fixture fixture = fixture(3);

        assertTrue(fixture.controller.loadSubscriptions());
        assertEquals(0, fixture.store.subscriptionCalls);
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.LOADING_SUBSCRIPTIONS,
                fixture.last().getPhase());
        fixture.work.runNext();
        assertEquals(1, fixture.store.subscriptionCalls);
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.SUBSCRIPTIONS_READY,
                fixture.last().getPhase());

        assertTrue(fixture.controller.selectSubscription(1L));
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.LOADING_EPISODES,
                fixture.last().getPhase());
        assertEquals(0, fixture.store.episodeCalls);
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.EPISODES_READY,
                fixture.last().getPhase());
        assertEquals(3, fixture.last().getEpisodes().size());
        assertFalse(fixture.last().hasMore());
    }

    @Test public void supersededOrClosedSubscriptionReadsSkipDatabaseWork(){
        Fixture f=fixture(3);f.controller.loadSubscriptions();f.controller.loadSubscriptions();
        f.work.runNext();assertEquals(0,f.store.subscriptionCalls);
        f.work.runNext();assertEquals(1,f.store.subscriptionCalls);
        f.controller.loadSubscriptions();f.controller.close();f.work.runNext();assertEquals(1,f.store.subscriptionCalls);
    }

    @Test
    public void pagingAppendsInBoundedWindows() {
        Fixture fixture = fixture(75);
        readySubscriptions(fixture);
        fixture.controller.selectSubscription(1L);
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();

        assertEquals(50, fixture.last().getEpisodes().size());
        assertTrue(fixture.last().hasMore());
        assertTrue(fixture.controller.loadMore());
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.LOADING_MORE,
                fixture.last().getPhase());
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(75, fixture.last().getEpisodes().size());
        assertFalse(fixture.last().hasMore());
        assertEquals(List.of(0, 50), fixture.store.offsets);
        assertEquals(List.of(50, 50), fixture.store.limits);
    }

    @Test
    public void applyingQueryResetsPageAndKeepsItForNextPage() {
        Fixture fixture = fixture(75);
        readySubscriptions(fixture);
        fixture.controller.selectSubscription(1L);
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();
        fixture.controller.loadMore();
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();
        CatalogBrowseQuery query = new CatalogBrowseQuery(
                CatalogBrowseQuery.Filter.UNPLAYED, "100%_ literal");

        assertTrue(fixture.controller.applyQuery(query));
        fixture.callbacks.runNext();
        assertEquals(75, fixture.last().getEpisodes().size()); // Keep rows stable while reading.
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(50, fixture.last().getEpisodes().size());
        assertEquals(0, (int) fixture.store.offsets.get(2));
        assertEquals(query, fixture.store.queries.get(2));
        fixture.controller.loadMore();
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(query, fixture.store.queries.get(3));
        assertEquals(50, (int) fixture.store.offsets.get(3));
    }

    @Test public void newerSearchDropsLateCompletionAndSkipsUnstartedSearches() {
        Fixture fixture = fixture(3);
        readySubscriptions(fixture);
        fixture.controller.selectSubscription(1L);
        fixture.callbacks.runNext();fixture.work.runNext();fixture.callbacks.runNext();
        fixture.controller.applyQuery(new CatalogBrowseQuery(CatalogBrowseQuery.Filter.ALL, "old"));
        fixture.callbacks.runNext();fixture.work.runNext(); // Old result is waiting on main.
        fixture.controller.applyQuery(new CatalogBrowseQuery(CatalogBrowseQuery.Filter.ALL, "older"));
        var latest = new CatalogBrowseQuery(CatalogBrowseQuery.Filter.ALL, "latest");
        fixture.controller.applyQuery(latest);
        int notifications=fixture.states.size();
        fixture.callbacks.runNext(); // Stale result never reaches the list.
        assertEquals(notifications,fixture.states.size());
        while(fixture.work.size()>0)fixture.work.runNext();
        while(fixture.callbacks.size()>0)fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.EPISODES_READY,fixture.last().getPhase());
        assertEquals(latest,fixture.controller.getQuery());
        assertEquals(List.of(CatalogBrowseQuery.DEFAULT,
                new CatalogBrowseQuery(CatalogBrowseQuery.Filter.ALL,"old"),latest),fixture.store.queries);
    }

    @Test public void clearingSearchSupersedesAnOutstandingPageAppend() {
        Fixture fixture=fixture(75);readySubscriptions(fixture);
        fixture.controller.selectSubscription(1L);
        fixture.callbacks.runNext();fixture.work.runNext();fixture.callbacks.runNext();
        var searched=new CatalogBrowseQuery(CatalogBrowseQuery.Filter.UNPLAYED,"title");
        fixture.controller.applyQuery(searched);
        fixture.callbacks.runNext();fixture.work.runNext();fixture.callbacks.runNext();
        fixture.controller.loadMore();fixture.callbacks.runNext();fixture.work.runNext();
        assertTrue(fixture.controller.applyQuery(CatalogBrowseQuery.DEFAULT));
        while(fixture.callbacks.size()>0)fixture.callbacks.runNext();
        fixture.work.runNext();fixture.callbacks.runNext();
        assertEquals(50,fixture.last().getEpisodes().size());
        assertEquals(CatalogBrowseQuery.DEFAULT,fixture.controller.getQuery());
        assertEquals(0,(int)fixture.store.offsets.get(fixture.store.offsets.size()-1));
    }

    @Test public void typingInvalidatesResultsBeforeDebounceAndSameQueryCanRestart() {
        Fixture fixture=fixture(3);readySubscriptions(fixture);
        fixture.controller.selectSubscription(1L);fixture.callbacks.runNext();fixture.work.runNext();
        int notifications=fixture.states.size();
        fixture.controller.invalidateEpisodeRead();fixture.callbacks.runNext();
        assertEquals(notifications,fixture.states.size());
        assertTrue(fixture.controller.applyQuery(CatalogBrowseQuery.DEFAULT));
        fixture.callbacks.runNext();fixture.work.runNext();fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.EPISODES_READY,fixture.last().getPhase());
        assertEquals(2,fixture.store.episodeCalls);
    }

    @Test public void selectingAnotherPodcastSupersedesAnOutstandingEpisodeRead() {
        Fixture fixture=fixture(1);
        fixture.store.subscriptions.add(new CatalogSubscriptionSummary(2L,"Second","",1,0,1L));
        fixture.store.episodes.put(2L,List.of(CatalogBrowseModelTest.episode(5)));
        readySubscriptions(fixture);
        fixture.controller.selectSubscription(1L);fixture.callbacks.runNext();fixture.work.runNext();
        assertTrue(fixture.controller.selectSubscription(2L));
        while(fixture.callbacks.size()>0)fixture.callbacks.runNext();
        fixture.work.runNext();fixture.callbacks.runNext();
        assertEquals(2L,fixture.last().getSelectedSubscriptionId());
        assertEquals(fixture.store.episodes.get(2L),fixture.last().getEpisodes());
    }

    @Test
    public void invalidSelectionDoesNotReachStore() {
        Fixture fixture = fixture(1);
        readySubscriptions(fixture);

        assertFalse(fixture.controller.selectSubscription(99L));
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.ERROR, fixture.last().getPhase());
        assertEquals(CatalogBrowserController.Failure.INVALID_SELECTION,
                fixture.last().getFailure());
        assertEquals(0, fixture.store.episodeCalls);
        assertEquals(0, fixture.work.size());
    }

    @Test
    public void newerReloadSuppressesOlderCompletion() {
        Fixture fixture = fixture(1);
        fixture.controller.loadSubscriptions();
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.controller.loadSubscriptions();
        int notifications = fixture.states.size();
        fixture.callbacks.runNext();
        assertEquals(notifications, fixture.states.size());
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Phase.LOADING_SUBSCRIPTIONS,
                fixture.last().getPhase());
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(2, fixture.store.subscriptionCalls);
        assertEquals(CatalogBrowserController.Phase.SUBSCRIPTIONS_READY,
                fixture.last().getPhase());
    }

    @Test
    public void failureIsTypedAndCloseDropsLateResult() {
        Fixture fixture = fixture(1);
        fixture.store.fail = true;
        fixture.controller.loadSubscriptions();
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(CatalogBrowserController.Failure.LOAD_FAILED,
                fixture.last().getFailure());

        fixture.store.fail = false;
        fixture.controller.loadSubscriptions();
        fixture.callbacks.runNext();
        fixture.work.runNext();
        int notifications = fixture.states.size();
        fixture.controller.close();
        fixture.callbacks.runNext();
        assertEquals(notifications, fixture.states.size());
    }

    @Test public void episodeReadsNeverAcknowledgeUntilExplicitSuccessfulDisplay() {
        Fixture f = fixture(3); f.store.snapshotGeneration = 7; readySubscriptions(f);
        assertFalse(f.controller.acknowledgeViewed());
        f.controller.selectSubscription(1); f.callbacks.runNext(); f.work.runNext();
        assertFalse(f.controller.acknowledgeViewed()); // Not delivered to UI yet.
        f.callbacks.runNext(); assertEquals(0, f.store.acknowledgements.size());
        assertTrue(f.controller.acknowledgeViewed());
        assertEquals(0, f.store.acknowledgements.size());
        f.work.runNext(); f.callbacks.runNext();
        assertEquals(List.of(7L), f.store.acknowledgements);
        f.controller.acknowledgeViewed(); assertEquals(0, f.work.size());
    }

    @Test public void aLaterAppendCannotAcknowledgeArrivalsBeyondTheFirstSnapshot() {
        Fixture f = fixture(75); f.store.snapshotGeneration = 7; readySubscriptions(f);
        f.controller.selectSubscription(1); f.callbacks.runNext(); f.work.runNext(); f.callbacks.runNext();
        f.store.snapshotGeneration = 8;
        f.controller.loadMore(); f.callbacks.runNext(); f.work.runNext(); f.callbacks.runNext();
        f.controller.acknowledgeViewed(); f.work.runNext(); f.callbacks.runNext();
        assertEquals(List.of(7L), f.store.acknowledgements);
    }

    @Test public void staleOrFailedPagesCannotBeAcknowledged() {
        Fixture f = fixture(3); f.store.snapshotGeneration = 7; readySubscriptions(f);
        f.controller.selectSubscription(1); f.callbacks.runNext(); f.work.runNext();
        f.store.fail = true; f.controller.loadSubscriptions();
        f.callbacks.runNext(); f.callbacks.runNext(); f.work.runNext(); f.callbacks.runNext();
        assertFalse(f.controller.acknowledgeViewed());
        assertTrue(f.store.acknowledgements.isEmpty());
    }

    private static void readySubscriptions(Fixture fixture) {
        fixture.controller.loadSubscriptions();
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();
    }

    private static Fixture fixture(int episodeCount) {
        FakeStore store = new FakeStore();
        store.subscriptions.add(new CatalogSubscriptionSummary(
                1L, "Publisher", "Author", episodeCount, 0, 1L));
        List<CatalogEpisodeSummary> episodes = new ArrayList<>();
        for (int index = 0; index < episodeCount; index++) {
            episodes.add(CatalogBrowseModelTest.episode(index));
        }
        store.episodes.put(1L, episodes);
        return new Fixture(store);
    }

    private static final class Fixture {
        private final FakeStore store;
        private final ManualExecutor work = new ManualExecutor();
        private final ManualExecutor callbacks = new ManualExecutor();
        private final List<CatalogBrowserController.State> states = new ArrayList<>();
        private final CatalogBrowserController controller;

        private Fixture(FakeStore store) {
            this.store = store;
            controller = new CatalogBrowserController(store, work, callbacks, states::add);
        }

        private CatalogBrowserController.State last() {
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

    private static final class FakeStore implements CatalogBrowserStore {
        private final List<CatalogSubscriptionSummary> subscriptions = new ArrayList<>();
        private final Map<Long, List<CatalogEpisodeSummary>> episodes = new HashMap<>();
        private final List<Integer> offsets = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();
        private int subscriptionCalls;
        private int episodeCalls;
        private final List<CatalogBrowseQuery> queries = new ArrayList<>();
        private boolean fail;
        private long snapshotGeneration;
        private final List<Long> acknowledgements = new ArrayList<>();
        @Override public void markSubscriptionViewed(long id, long snapshot) { acknowledgements.add(snapshot); }

        @Override
        public List<CatalogSubscriptionSummary> listSubscriptions() throws IOException {
            subscriptionCalls++;
            if (fail) {
                throw new IOException("private database detail");
            }
            return Collections.unmodifiableList(new ArrayList<>(subscriptions));
        }

        @Override
        public CatalogEpisodePage listEpisodes(long subscriptionId, int offset, int limit,
                CatalogBrowseQuery query)
                throws IOException {
            episodeCalls++;
            queries.add(query);
            offsets.add(offset);
            limits.add(limit);
            if (fail) {
                throw new IOException("private database detail");
            }
            List<CatalogEpisodeSummary> values = episodes.get(subscriptionId);
            if (values == null) {
                throw new IOException("unknown internal id");
            }
            int end = Math.min(values.size(), offset + limit);
            return new CatalogEpisodePage(
                    subscriptionId,
                    offset,
                    values.subList(offset, end),
                    end < values.size(), snapshotGeneration);
        }
    }
}
