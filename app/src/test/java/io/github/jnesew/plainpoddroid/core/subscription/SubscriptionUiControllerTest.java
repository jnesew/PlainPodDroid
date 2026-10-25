package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.catalog.EpisodeCatalogMapper;
import io.github.jnesew.plainpoddroid.core.network.BoundedFeedAcquirer;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedRequestHeaders;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;





public final class SubscriptionUiControllerTest {
    private static final byte[] RSS = ("<rss><channel><title>Publisher title</title>"
            + "<item><guid>one</guid><title>Episode</title>"
            + "<enclosure url=\"https://media.example/one.mp3\" type=\"audio/mpeg\"/>"
            + "</item></channel></rss>").getBytes(StandardCharsets.UTF_8);

    @Test
    public void previewAndConfirmationUseWorkerAndRemainSeparateActions() {
        Fixture fixture = fixture(8);
        fixture.transport.add("https://feeds.example/show", response(RSS));

        assertTrue(fixture.controller.startPreview("https://feeds.example/show"));
        assertEquals(0, fixture.transport.requested.size());
        fixture.callbacks.runNext();
        assertEquals(SubscriptionUiController.Phase.PREVIEWING, fixture.last().getPhase());

        fixture.work.runNext();
        assertEquals(1, fixture.transport.requested.size());
        assertEquals(0, fixture.store.added.size());
        fixture.callbacks.runNext();
        assertEquals(
                SubscriptionUiController.Phase.READY_TO_CONFIRM,
                fixture.last().getPhase());
        assertEquals("Publisher title", fixture.last().getPreview().getTitle());

        assertTrue(fixture.controller.confirm());
        fixture.callbacks.runNext();
        assertEquals(SubscriptionUiController.Phase.CONFIRMING, fixture.last().getPhase());
        assertEquals(0, fixture.store.added.size());
        fixture.work.runNext();
        assertEquals(1, fixture.store.added.size());
        fixture.callbacks.runNext();
        assertEquals(SubscriptionUiController.Phase.SUBSCRIBED, fixture.last().getPhase());
        assertEquals(42L, fixture.last().getSubscriptionId());
    }

    @Test
    public void staleCompletionIsDiscardedAndReleasesPendingCapacity() {
        Fixture fixture = fixture(1);
        fixture.transport.add("https://feeds.example/first", response(RSS));
        fixture.transport.add("https://feeds.example/second", response(RSS));

        fixture.controller.startPreview("https://feeds.example/first");
        fixture.callbacks.runNext();
        fixture.work.runNext();
        assertTrue(fixture.controller.cancel());
        fixture.callbacks.runNext();
        fixture.callbacks.runNext();
        assertEquals(SubscriptionUiController.Phase.IDLE, fixture.last().getPhase());

        fixture.controller.startPreview("https://feeds.example/second");
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(
                SubscriptionUiController.Phase.READY_TO_CONFIRM,
                fixture.last().getPhase());
        assertEquals(2, fixture.transport.requested.size());
    }

    @Test
    public void closeDropsListenerAndDiscardsLatePreview() {
        Fixture fixture = fixture(1);
        fixture.transport.add("https://feeds.example/first", response(RSS));
        fixture.transport.add("https://feeds.example/second", response(RSS));

        fixture.controller.startPreview("https://feeds.example/first");
        fixture.callbacks.runNext();
        fixture.work.runNext();
        int notificationCount = fixture.states.size();
        fixture.controller.close();
        fixture.callbacks.runNext();
        assertEquals(notificationCount, fixture.states.size());

        ManualExecutor secondWork = new ManualExecutor();
        ManualExecutor secondCallbacks = new ManualExecutor();
        List<SubscriptionUiController.State> secondStates = new ArrayList<>();
        SubscriptionUiController second = new SubscriptionUiController(
                fixture.coordinator,
                secondWork,
                secondCallbacks,
                secondStates::add);
        second.startPreview("https://feeds.example/second");
        secondCallbacks.runNext();
        secondWork.runNext();
        secondCallbacks.runNext();
        assertEquals(
                SubscriptionUiController.Phase.READY_TO_CONFIRM,
                secondStates.get(secondStates.size() - 1).getPhase());
    }

    @Test
    public void invalidInputFailsBeforeWorkerOrNetwork() {
        Fixture fixture = fixture(8);

        fixture.controller.startPreview("  \n ");
        fixture.callbacks.runNext();
        assertEquals(
                SubscriptionUiController.Failure.INPUT_REQUIRED,
                fixture.last().getFailure());
        fixture.controller.startPreview("x".repeat(SecurityLimits.MAX_URL_CHARS + 1));
        fixture.callbacks.runNext();
        assertEquals(
                SubscriptionUiController.Failure.INPUT_TOO_LONG,
                fixture.last().getFailure());
        assertEquals(0, fixture.work.size());
        assertEquals(0, fixture.transport.requested.size());
    }

    @Test
    public void invalidUrlUsesTypedFailureWithoutEchoingInput() {
        Fixture fixture = fixture(8);

        fixture.controller.startPreview("http://feeds.example/private?token=secret");
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();

        assertEquals(SubscriptionUiController.Phase.ERROR, fixture.last().getPhase());
        assertEquals(
                SubscriptionUiController.Failure.INVALID_URL,
                fixture.last().getFailure());
        assertEquals(null, fixture.last().getPreview());
    }

    @Test
    public void failedSaveConsumesCandidateAndRequiresAnotherPreview() {
        Fixture fixture = fixture(8);
        fixture.store.failAdd = true;
        fixture.transport.add("https://feeds.example/show", response(RSS));
        fixture.controller.startPreview("https://feeds.example/show");
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();

        fixture.controller.confirm();
        fixture.callbacks.runNext();
        fixture.work.runNext();
        fixture.callbacks.runNext();
        assertEquals(SubscriptionUiController.Phase.ERROR, fixture.last().getPhase());
        assertEquals(
                SubscriptionUiController.Failure.SAVE_FAILED,
                fixture.last().getFailure());
        assertFalse(fixture.controller.confirm());
        assertEquals(0, fixture.work.size());
    }

    @Test public void cancelledOrClosedQueuedLoginPreviewNeverSendsCredentials() throws Exception {
        for (boolean close : new boolean[] {false, true}) {
            Fixture f = fixture(8);
            String url = "https://feeds.example/private";
            var login = new BasicFeedLogin(
                    CanonicalHttpsUrl.parse(url), "user", "test-password", null);
            f.controller.startPreview(url, login);
            if (close) f.controller.close(); else f.controller.cancel();
            f.work.runNext();
            assertEquals(0, f.transport.requested.size()); assertEquals(0, f.store.added.size());
        }
    }

    @Test public void cancellingAnActivePreviewRevokesItsLoginAuthority() throws Exception {
        Fixture f = fixture(8); String url = "https://feeds.example/private";
        var login = new BasicFeedLogin(CanonicalHttpsUrl.parse(url), "user", "test-password", null);
        f.transport.add(url, response(RSS)); f.transport.onRequest = () -> f.controller.cancel();
        f.controller.startPreview(url, login); f.work.runNext();
        assertFalse(login.isActive()); assertEquals(0, f.store.added.size());
        while (f.callbacks.size() > 0) f.callbacks.runNext();
        assertEquals(SubscriptionUiController.Phase.IDLE, f.controller.getState().getPhase());
    }

    private static Fixture fixture(int pendingLimit) {
        FakeTransport transport = new FakeTransport();
        FakeStore store = new FakeStore();
        SubscriptionCoordinator coordinator = new SubscriptionCoordinator(
                new BoundedFeedAcquirer(transport),
                new EpisodeCatalogMapper(),
                store,
                new SecureRandom(new byte[] {1, 2, 3, 4}),
                new AtomicLong()::get,
                pendingLimit,
                1_000_000L);
        return new Fixture(transport, store, coordinator);
    }

    private static FakeExchange response(byte[] body) {
        return new FakeExchange(new ByteArrayInputStream(body));
    }

    private static final class Fixture {
        private final FakeTransport transport;
        private final FakeStore store;
        private final SubscriptionCoordinator coordinator;
        private final ManualExecutor work = new ManualExecutor();
        private final ManualExecutor callbacks = new ManualExecutor();
        private final List<SubscriptionUiController.State> states = new ArrayList<>();
        private final SubscriptionUiController controller;

        private Fixture(
                FakeTransport transport,
                FakeStore store,
                SubscriptionCoordinator coordinator) {
            this.transport = transport;
            this.store = store;
            this.coordinator = coordinator;
            controller = new SubscriptionUiController(
                    coordinator,
                    work,
                    callbacks,
                    states::add);
        }

        private SubscriptionUiController.State last() {
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
            Runnable task = tasks.remove();
            task.run();
        }

        private int size() {
            return tasks.size();
        }
    }

    private static final class FakeStore implements SubscriptionStore {
        private final Set<String> existing = new HashSet<>();
        private final List<CatalogSnapshot> added = new ArrayList<>();
        private boolean failAdd;

        @Override
        public SubscriptionRefreshTarget findSubscriptionRefreshTarget(long subscriptionId) {
            return null;
        }

        @Override
        public long addConfirmedSubscription(
                CatalogSnapshot snapshot,
                FeedValidators validators) throws IOException {
            if (failAdd) {
                throw new IOException("secret storage detail");
            }
            added.add(snapshot);
            existing.add(snapshot.getSubscriptionUrl().toString());
            return 42L;
        }

        @Override
        public void refreshExistingSubscription(
                SubscriptionRefreshTarget target,
                CatalogSnapshot snapshot,
                FeedValidators validators) {
            throw new AssertionError("refresh not expected");
        }

        @Override
        public void recordUnmodifiedSubscription(
                SubscriptionRefreshTarget target,
                FeedValidators validators) {
            throw new AssertionError("refresh not expected");
        }
    }

    private static final class FakeTransport implements FeedTransport {
        private final Map<String, Queue<FakeExchange>> responses = new HashMap<>();
        private final List<String> requested = new ArrayList<>();
        private Runnable onRequest = () -> { };

        private void add(String url, FakeExchange response) {
            responses.computeIfAbsent(url, ignored -> new ArrayDeque<>()).add(response);
        }

        @Override
        public Exchange execute(CanonicalHttpsUrl url, FeedRequestHeaders headers)
                throws IOException {
            requested.add(url.toString());
            onRequest.run();
            Queue<FakeExchange> queue = responses.get(url.toString());
            if (queue == null || queue.isEmpty()) {
                throw new IOException("No fake response for " + url.getOriginForDisplay());
            }
            return queue.remove();
        }
    }

    private static final class FakeExchange implements FeedTransport.Exchange {
        private final InputStream body;

        private FakeExchange(InputStream body) {
            this.body = body;
        }

        @Override
        public int getStatusCode() {
            return 200;
        }

        @Override
        public String getHeader(String name) {
            if ("Content-Type".equalsIgnoreCase(name)) {
                return "application/rss+xml";
            }
            return null;
        }

        @Override
        public InputStream getBody() {
            return body;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
