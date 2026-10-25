package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ApplePodcastSubscriptionTest {
    private static final String RSS = "<rss><channel><title>Publisher title</title>"
            + "<item><guid>one</guid><title>Episode</title><enclosure "
            + "url=\"https://media.example/one.mp3\" type=\"audio/mpeg\"/>"
            + "</item></channel></rss>";

    @Test public void savesResolvedRssOnlyAfterConfirmationAndRefreshBypassesApple() throws Exception {
        Fixture fixture = new Fixture();
        SubscriptionPreview preview = fixture.coordinator.preview(ApplePodcastResolverTest.APPLE + "?i=987");
        assertEquals(1, fixture.lookups);
        assertEquals(List.of(ApplePodcastResolverTest.FEED), fixture.feedRequests);
        assertNull(fixture.store.saved);
        assertTrue(preview.isFromAppleLink()); assertTrue(preview.isFromAppleEpisodeLink());
        assertEquals("Publisher title", preview.getTitle());
        assertEquals(ApplePodcastResolverTest.FEED, preview.getFeedUrl().toString());
        assertEquals("https://feeds.example", preview.getOriginalOriginForDisplay());
        assertEquals(42L, fixture.coordinator.confirm(preview));
        assertEquals(ApplePodcastResolverTest.FEED, fixture.store.saved.getSubscriptionUrl().toString());
        fixture.coordinator.refresh(42L);
        assertEquals(1, fixture.lookups); assertEquals(2, fixture.feedRequests.size());
        assertEquals(42L, fixture.coordinator.confirm(fixture.coordinator.preview(ApplePodcastResolverTest.FEED)));
        assertEquals(1, fixture.lookups); assertEquals(1, fixture.store.additions);
    }

    @Test public void cancellationDuringLookupPreventsFollowOnRssRequest() {
        Fixture fixture = new Fixture();
        fixture.afterLookup = fixture.controller::cancel;
        fixture.controller.startPreview(ApplePodcastResolverTest.APPLE);
        assertEquals(0, fixture.lookups);
        fixture.work.run();
        assertEquals(1, fixture.lookups); assertTrue(fixture.feedRequests.isEmpty());
        assertEquals(SubscriptionUiController.Phase.IDLE, fixture.controller.getState().getPhase());
        assertNull(fixture.store.saved);
    }

    @Test public void supersededAndClosedLookupCannotStartFeedRequest() {
        for (boolean close : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            fixture.afterLookup = close ? fixture.controller::close
                    : () -> fixture.controller.startPreview(ApplePodcastResolverTest.FEED);
            fixture.controller.startPreview(ApplePodcastResolverTest.APPLE);
            fixture.work.run();
            assertTrue(fixture.feedRequests.isEmpty());
            if (!close) {
                fixture.work.run();
                assertEquals(SubscriptionUiController.Phase.READY_TO_CONFIRM,
                        fixture.controller.getState().getPhase());
                assertFalse(fixture.controller.getState().getPreview().isFromAppleLink());
            }
        }
    }

    @Test public void malformedAppleLinkMakesNoRequestsAndFeedLoginNeverReachesLookup() throws Exception {
        Fixture fixture = new Fixture();
        fixture.controller.startPreview("https://podcasts.apple.com/fi/podcast/no-id");
        fixture.work.run();
        assertEquals(SubscriptionUiController.Failure.INVALID_URL, fixture.controller.getState().getFailure());
        assertEquals(0, fixture.lookups); assertTrue(fixture.feedRequests.isEmpty());
        BasicFeedLogin login = new BasicFeedLogin(CanonicalHttpsUrl.parse(ApplePodcastResolverTest.APPLE), "user", "password", null);
        assertEquals(ApplePodcastLookupException.Reason.LOGIN_UNSUPPORTED, assertThrows(
                ApplePodcastLookupException.class, () -> fixture.coordinator.preview(ApplePodcastResolverTest.APPLE, login)).getReason());
        assertFalse(login.isActive()); assertEquals(0, fixture.lookups);
    }

    @Test public void missingPublicFeedAndNetworkFailureHaveSpecificUiStates() {
        for (boolean missing : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            fixture.json = missing ? "{\"resultCount\":0,\"results\":[]}" : "bad json";
            fixture.controller.startPreview(ApplePodcastResolverTest.APPLE); fixture.work.run();
            assertEquals(missing ? SubscriptionUiController.Failure.APPLE_NO_PUBLIC_FEED
                    : SubscriptionUiController.Failure.APPLE_LOOKUP_FAILED,
                    fixture.controller.getState().getFailure());
            assertTrue(fixture.feedRequests.isEmpty()); assertNull(fixture.store.saved);
        }
    }

    @Test public void oversizedFeedGetsLimitStateAndNoConfirmationCapability() {
        Fixture fixture = new Fixture(); fixture.oversizedFeed = true;
        fixture.controller.startPreview(ApplePodcastResolverTest.FEED); fixture.work.run();
        assertEquals(SubscriptionUiController.Failure.FEED_LIMIT, fixture.controller.getState().getFailure());
        assertFalse(fixture.controller.confirm()); assertNull(fixture.store.saved);
    }

    private static final class Fixture {
        final List<String> feedRequests = new ArrayList<>();
        final Store store = new Store();
        final Tasks work = new Tasks();
        final SubscriptionCoordinator coordinator;
        final SubscriptionUiController controller;
        String json = ApplePodcastResolverTest.JSON;
        int lookups;
        boolean oversizedFeed;
        Runnable afterLookup = () -> {};
        Fixture() {
            ApplePodcastResolver resolver = new ApplePodcastResolver(url -> {
                lookups++; afterLookup.run(); return new ApplePodcastResolverTest.Exchange(json);
            });
            FeedTransport feeds = (url, headers) -> {
                feedRequests.add(url.toString());
                assertEquals(ApplePodcastResolverTest.FEED, url.toString());
                assertNull(headers.getLogin());
                var exchange = new ApplePodcastResolverTest.Exchange(RSS);
                exchange.headers.put("Content-Type", "application/rss+xml");
                if (oversizedFeed) exchange.headers.put("Content-Length", "33554433");
                return exchange;
            };
            coordinator = new SubscriptionCoordinator(feeds, store, () -> {}, resolver);
            controller = new SubscriptionUiController(coordinator, work, Runnable::run, state -> {});
        }
    }

    private static final class Tasks implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void run() { tasks.remove().run(); }
    }

    private static final class Store implements SubscriptionStore {
        CatalogSnapshot saved;
        int additions;
        @Override public SubscriptionRefreshTarget findSubscriptionRefreshTarget(long id) {
            return saved == null ? null : new SubscriptionRefreshTarget(id, saved.getSubscriptionUrl(), FeedValidators.none(), 1L);
        }
        @Override public long addConfirmedSubscription(CatalogSnapshot snapshot, FeedValidators validators) {
            if (saved == null) { additions++; saved = snapshot; }
            assertEquals(saved.getSubscriptionUrl(), snapshot.getSubscriptionUrl());
            return 42L;
        }
        @Override public void refreshExistingSubscription(SubscriptionRefreshTarget target, CatalogSnapshot snapshot, FeedValidators validators) { saved = snapshot; }
        @Override public void recordUnmodifiedSubscription(SubscriptionRefreshTarget target, FeedValidators validators) {}
    }
}
