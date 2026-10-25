package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.catalog.EpisodeCatalogMapper;
import io.github.jnesew.plainpoddroid.core.network.BoundedFeedAcquirer;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedRequestHeaders;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SubscriptionCoordinatorTest {
    private static final byte[] RSS = """
            <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Publisher title</title>
                <itunes:author>Publisher author</itunes:author>
                <description>Publisher notes</description>
                <item>
                  <guid>one</guid><title>Episode</title>
                  <enclosure url="https://media.example/one.mp3" type="audio/mpeg" />
                </item>
              </channel>
            </rss>
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    public void previewUsesValidatedOptionalArtworkWithoutSubscribing() throws Exception {
        String feed = new String(RSS, StandardCharsets.UTF_8);
        FakeTransport transport = new FakeTransport();
        transport.add("https://feeds.example/artwork", response(200,
                feed.replace("<title>Publisher title</title>",
                        "<title>Publisher title</title><itunes:image href=\"https://images.example/cover?token=secret\" />")
                        .getBytes(StandardCharsets.UTF_8)));
        transport.add("https://feeds.example/unsafe-artwork", response(200,
                feed.replace("<title>Publisher title</title>",
                        "<title>Publisher title</title><itunes:image href=\"http://images.example/cover\" />")
                        .getBytes(StandardCharsets.UTF_8)));
        FakeStore store = new FakeStore();
        SubscriptionCoordinator coordinator = coordinator(
                transport, store, new AtomicLong(), 8, 10L);
        SubscriptionPreview safe = coordinator.preview("https://feeds.example/artwork");
        assertEquals("https://images.example/cover?token=secret", safe.getArtworkUrl().toString());
        assertFalse(safe.getOriginalOriginForDisplay().contains("secret"));
        assertEquals(0, store.added.size());
        coordinator.discard(safe);
        SubscriptionPreview unsafe = coordinator.preview("https://feeds.example/unsafe-artwork");
        assertEquals(null, unsafe.getArtworkUrl());
        assertEquals(1, unsafe.getAcceptedEpisodeCount());
        assertEquals(0, store.added.size());
    }

    @Test
    public void previewIsInMemoryRedactedAndConfirmationIsOneShot() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(
                "https://feeds.example/private?token=secret",
                response(200, RSS));
        FakeStore store = new FakeStore();
        SubscriptionCoordinator coordinator = coordinator(transport, store, new AtomicLong(), 8, 10L);

        SubscriptionPreview preview = coordinator.preview(
                "https://feeds.example/private?token=secret");

        assertEquals(0, store.added.size());
        assertEquals("https://feeds.example", preview.getOriginalOriginForDisplay());
        assertEquals("https://feeds.example", preview.getFinalOriginForDisplay());
        assertEquals(List.of("https://feeds.example"), preview.getRedirectOriginsForDisplay());
        assertFalse(preview.getOriginalOriginForDisplay().contains("private"));
        assertFalse(preview.getOriginalOriginForDisplay().contains("secret"));
        assertEquals("Publisher title", preview.getTitle());
        assertEquals("Publisher author", preview.getAuthor());
        assertEquals(1, preview.getAcceptedEpisodeCount());

        assertEquals(42L, coordinator.confirm(preview));
        assertEquals(1, store.added.size());
        assertEquals(
                "https://feeds.example/private?token=secret",
                store.added.get(0).getSubscriptionUrl().toString());
        SubscriptionWorkflowException reused = assertThrows(
                SubscriptionWorkflowException.class,
                () -> coordinator.confirm(preview));
        assertEquals(
                SubscriptionWorkflowException.Reason.INVALID_OR_EXPIRED_CONFIRMATION,
                reused.getReason());
        assertFalse(reused.getMessage().contains("secret"));
        assertEquals(1, store.added.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> preview.getRedirectOriginsForDisplay().add("https://other.example"));
    }

    @Test
    public void previewShowsRedirectOriginsButStoresTheOriginalUrlOnlyAfterConfirm()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeExchange redirect = response(302, new byte[0]);
        redirect.headers.put(
                "Location",
                "https://final.example/feed?redirect_token=hidden");
        transport.add("https://start.example/feed?token=secret", redirect);
        transport.add(
                "https://final.example/feed?redirect_token=hidden",
                response(200, RSS));
        FakeStore store = new FakeStore();
        SubscriptionCoordinator coordinator = coordinator(transport, store, new AtomicLong(), 8, 10L);

        SubscriptionPreview preview = coordinator.preview(
                "https://start.example/feed?token=secret");

        assertEquals(
                List.of("https://start.example", "https://final.example"),
                preview.getRedirectOriginsForDisplay());
        assertEquals("https://final.example", preview.getFinalOriginForDisplay());
        assertEquals(0, store.added.size());
        coordinator.confirm(preview);
        assertEquals(
                "https://start.example/feed?token=secret",
                store.added.get(0).getSubscriptionUrl().toString());
        assertEquals(
                "https://final.example/feed?redirect_token=hidden",
                store.added.get(0).getFinalFeedUrl().toString());
    }

    @Test
    public void expiryDiscardAndForeignCoordinatorAllInvalidateConfirmation()
            throws Exception {
        FakeTransport firstTransport = new FakeTransport();
        firstTransport.add("https://feeds.example/one", response(200, RSS));
        firstTransport.add("https://feeds.example/two", response(200, RSS));
        firstTransport.add("https://feeds.example/three", response(200, RSS));
        FakeStore firstStore = new FakeStore();
        AtomicLong clock = new AtomicLong();
        SubscriptionCoordinator first = coordinator(firstTransport, firstStore, clock, 8, 10L);

        SubscriptionPreview expired = first.preview("https://feeds.example/one");
        clock.set(10L);
        assertInvalid(first, expired);

        SubscriptionPreview discarded = first.preview("https://feeds.example/two");
        first.discard(discarded);
        assertInvalid(first, discarded);

        SubscriptionPreview foreign = first.preview("https://feeds.example/three");
        SubscriptionCoordinator second = coordinator(
                new FakeTransport(),
                new FakeStore(),
                clock,
                8,
                10L);
        assertInvalid(second, foreign);
        assertEquals(0, firstStore.added.size());
    }

    @Test
    public void pendingLimitFailsClosedUntilTheCandidateIsDiscarded() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add("https://feeds.example/one", response(200, RSS));
        transport.add("https://feeds.example/two", response(200, RSS));
        transport.add("https://feeds.example/three", response(200, RSS));
        SubscriptionCoordinator coordinator = coordinator(
                transport,
                new FakeStore(),
                new AtomicLong(),
                1,
                100L);

        SubscriptionPreview first = coordinator.preview("https://feeds.example/one");
        SubscriptionWorkflowException full = assertThrows(
                SubscriptionWorkflowException.class,
                () -> coordinator.preview("https://feeds.example/two"));
        assertEquals(SubscriptionWorkflowException.Reason.PENDING_PREVIEW_LIMIT, full.getReason());

        coordinator.discard(first);
        SubscriptionPreview replacement = coordinator.preview("https://feeds.example/three");
        assertEquals("Publisher title", replacement.getTitle());
    }

    @Test
    public void unknownRefreshFailsBeforeAnyNetworkCall() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeStore store = new FakeStore();
        SubscriptionCoordinator coordinator = coordinator(transport, store, new AtomicLong(), 8, 10L);

        SubscriptionRefreshException missing = assertThrows(
                SubscriptionRefreshException.class,
                () -> coordinator.refresh(99L));

        assertEquals(SubscriptionRefreshException.Reason.SUBSCRIPTION_NOT_FOUND,
                missing.getReason());
        assertEquals(1, store.lookupCalls);
        assertEquals(0, transport.requested.size());
        assertFalse(missing.getMessage().contains("99"));
    }

    @Test
    public void refreshChecksMembershipThenPreservesOriginalAcrossRedirect() throws Exception {
        String original = "https://feeds.example/stored?token=secret";
        FakeTransport transport = new FakeTransport();
        FakeExchange redirect = response(301, new byte[0]);
        redirect.headers.put("Location", "https://new.example/feed");
        transport.add(original, redirect);
        transport.add("https://new.example/feed", response(200, RSS));
        FakeStore store = new FakeStore();
        store.refreshTargets.put(7L, CanonicalHttpsUrl.parse(original));
        SubscriptionCoordinator coordinator = coordinator(transport, store, new AtomicLong(), 8, 10L);

        coordinator.refresh(7L);

        assertEquals(1, store.lookupCalls);
        assertEquals(0, store.added.size());
        assertEquals(1, store.refreshed.size());
        assertEquals(original, store.refreshed.get(0).getSubscriptionUrl().toString());
        assertEquals(
                "https://new.example/feed",
                store.refreshed.get(0).getFinalFeedUrl().toString());
        assertEquals(List.of(original, "https://new.example/feed"), transport.requested);
    }

    @Test
    public void sameSubscriptionCannotRefreshConcurrently() throws Exception {
        String original = "https://feeds.example/stored?token=secret";
        BlockingTransport transport = new BlockingTransport(original);
        FakeStore store = new FakeStore();
        store.refreshTargets.put(7L, CanonicalHttpsUrl.parse(original));
        SubscriptionCoordinator coordinator = coordinator(
                transport,
                store,
                new AtomicLong(),
                8,
                10L);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                coordinator.refresh(7L);
            } catch (Throwable throwable) {
                firstFailure.set(throwable);
            }
        });

        first.start();
        assertTrue(transport.entered.await(5L, TimeUnit.SECONDS));
        SubscriptionRefreshException duplicate = assertThrows(
                SubscriptionRefreshException.class,
                () -> coordinator.refresh(7L));
        assertEquals(
                SubscriptionRefreshException.Reason.ALREADY_RUNNING,
                duplicate.getReason());
        assertFalse(duplicate.getMessage().contains("secret"));

        transport.release.countDown();
        first.join(5_000L);
        assertFalse(first.isAlive());
        assertEquals(null, firstFailure.get());
        assertEquals(1L, transport.requestCount.get());
        assertEquals(1, store.refreshed.size());
    }

    @Test public void jobBoundRefresherSharesOwnershipButUsesItsOwnTransportAndCompletionHook() throws Exception {
        String original = "https://feeds.example/stored?token=secret";
        FakeStore store = new FakeStore(); store.refreshTargets.put(7L, CanonicalHttpsUrl.parse(original));
        FakeTransport ordinary = new FakeTransport(); ordinary.add(original, response(200, RSS));
        AtomicLong ordinaryCompletions = new AtomicLong(); AtomicLong jobCompletions = new AtomicLong();
        SubscriptionCoordinator coordinator = new SubscriptionCoordinator(ordinary, store, ordinaryCompletions::incrementAndGet);
        BlockingTransport bound = new BlockingTransport(original);
        SubscriptionRefresher scoped = coordinator.refresherFor(bound, jobCompletions::incrementAndGet);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { scoped.refresh(7); } catch (Throwable rejected) { failure.set(rejected); } });
        worker.start();
        try {
            assertTrue(bound.entered.await(5, TimeUnit.SECONDS));
            assertEquals(SubscriptionRefreshException.Reason.ALREADY_RUNNING,
                    assertThrows(SubscriptionRefreshException.class, () -> coordinator.refresh(7)).getReason());
            assertEquals(SubscriptionRefreshException.Reason.ALREADY_RUNNING,
                    assertThrows(SubscriptionRefreshException.class, () -> scoped.refresh(7)).getReason());
            assertTrue(ordinary.requested.isEmpty());
        } finally { bound.release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertNull(failure.get());
        assertEquals(0, ordinaryCompletions.get()); assertEquals(1, jobCompletions.get());
        coordinator.refresh(7);
        assertEquals(1, ordinaryCompletions.get()); assertEquals(1, jobCompletions.get());
    }

    @Test
    public void refreshLookupFailureIsTypedAndMakesNoNetworkRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeStore store = new FakeStore();
        store.failLookup = true;
        SubscriptionCoordinator coordinator = coordinator(
                transport,
                store,
                new AtomicLong(),
                8,
                10L);

        SubscriptionRefreshException failure = assertThrows(
                SubscriptionRefreshException.class,
                () -> coordinator.refresh(7L));

        assertEquals(SubscriptionRefreshException.Reason.STORAGE_FAILED,
                failure.getReason());
        assertEquals(0, transport.requested.size());
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    public void refreshFetchParseAndSaveFailuresAreTypedAndRedacted() throws Exception {
        String original = "https://feeds.example/stored?token=secret";

        FakeTransport missingTransport = new FakeTransport();
        FakeStore missingStore = new FakeStore();
        missingStore.refreshTargets.put(7L, CanonicalHttpsUrl.parse(original));
        assertRefreshFailure(
                coordinator(missingTransport, missingStore, new AtomicLong(), 8, 10L),
                SubscriptionRefreshException.Reason.FETCH_FAILED);

        FakeTransport malformedTransport = new FakeTransport();
        malformedTransport.add(
                original,
                response(200, "<rss><channel>".getBytes(StandardCharsets.UTF_8)));
        FakeStore malformedStore = new FakeStore();
        malformedStore.refreshTargets.put(7L, CanonicalHttpsUrl.parse(original));
        assertRefreshFailure(
                coordinator(malformedTransport, malformedStore, new AtomicLong(), 8, 10L),
                SubscriptionRefreshException.Reason.INVALID_FEED);

        FakeTransport validTransport = new FakeTransport();
        validTransport.add(original, response(200, RSS));
        FakeStore failingStore = new FakeStore();
        failingStore.refreshTargets.put(7L, CanonicalHttpsUrl.parse(original));
        failingStore.failRefresh = true;
        assertRefreshFailure(
                coordinator(validTransport, failingStore, new AtomicLong(), 8, 10L),
                SubscriptionRefreshException.Reason.STORAGE_FAILED);
    }

    @Test
    public void conditional304RecordsSuccessWithoutRewritingCatalog() throws Exception {
        String original = "https://feeds.example/stored?token=secret";
        String finalUrl = "https://final.example/feed";
        FakeTransport transport = new FakeTransport();
        FakeExchange redirect = response(302, new byte[0]);
        redirect.headers.put("Location", finalUrl);
        transport.add(original, redirect);
        FakeExchange notModified = response(304, new byte[0]);
        notModified.headers.put("ETag", "\"new-value\"");
        transport.add(finalUrl, notModified);
        FakeStore store = new FakeStore();
        store.refreshTargets.put(7L, CanonicalHttpsUrl.parse(original));
        store.refreshValidators.put(
                7L,
                FeedValidators.of(
                        CanonicalHttpsUrl.parse(finalUrl),
                        "\"old-value\"",
                        "Wed, 21 Oct 2015 07:28:00 GMT"));
        SubscriptionCoordinator coordinator = coordinator(
                transport,
                store,
                new AtomicLong(),
                8,
                10L);

        coordinator.refresh(7L);

        assertEquals(0, store.refreshed.size());
        assertEquals(List.of(7L), store.unmodifiedIds);
        assertEquals("\"new-value\"", store.unmodifiedValidators.get(0).getEtag());
        assertFalse(transport.requestHeaders.get(0).isConditional());
        assertEquals(
                "\"old-value\"",
                transport.requestHeaders.get(1).getIfNoneMatch());
    }

    @Test
    public void confirmationPersistsValidatorsFromThePreviewResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeExchange response = response(200, RSS);
        response.headers.put("ETag", "W/\"preview\"");
        response.headers.put("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT");
        transport.add("https://feeds.example/feed", response);
        FakeStore store = new FakeStore();
        SubscriptionCoordinator coordinator = coordinator(
                transport,
                store,
                new AtomicLong(),
                8,
                10L);

        coordinator.confirm(coordinator.preview("https://feeds.example/feed"));

        assertEquals(1, store.addedValidators.size());
        assertEquals("W/\"preview\"", store.addedValidators.get(0).getEtag());
        assertEquals(
                "https://feeds.example/feed",
                store.addedValidators.get(0).getResourceUrl().toString());
    }

    private static void assertRefreshFailure(
            SubscriptionCoordinator coordinator,
            SubscriptionRefreshException.Reason expected) {
        SubscriptionRefreshException failure = assertThrows(
                SubscriptionRefreshException.class,
                () -> coordinator.refresh(7L));
        assertEquals(expected, failure.getReason());
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getMessage().contains("feeds.example"));
    }

    private static void assertInvalid(
            SubscriptionCoordinator coordinator,
            SubscriptionPreview preview) {
        SubscriptionWorkflowException invalid = assertThrows(
                SubscriptionWorkflowException.class,
                () -> coordinator.confirm(preview));
        assertEquals(
                SubscriptionWorkflowException.Reason.INVALID_OR_EXPIRED_CONFIRMATION,
                invalid.getReason());
    }

    private static SubscriptionCoordinator coordinator(
            FeedTransport transport,
            FakeStore store,
            AtomicLong clock,
            int pendingLimit,
            long ttlNanos) {
        return new SubscriptionCoordinator(
                new BoundedFeedAcquirer(transport),
                new EpisodeCatalogMapper(),
                store,
                new SecureRandom(new byte[] {1, 2, 3, 4}),
                clock::get,
                pendingLimit,
                ttlNanos);
    }

    private static FakeExchange response(int status, byte[] body) {
        return new FakeExchange(status, new ByteArrayInputStream(body));
    }

    private static final class FakeStore implements SubscriptionStore {
        private final Set<String> existing = new HashSet<>();
        private final Map<Long, CanonicalHttpsUrl> refreshTargets = new HashMap<>();
        private final Map<Long, FeedValidators> refreshValidators = new HashMap<>();
        private final List<CatalogSnapshot> added = new ArrayList<>();
        private final List<FeedValidators> addedValidators = new ArrayList<>();
        private final List<CatalogSnapshot> refreshed = new ArrayList<>();
        private final List<FeedValidators> refreshedValidators = new ArrayList<>();
        private final List<Long> unmodifiedIds = new ArrayList<>();
        private final List<FeedValidators> unmodifiedValidators = new ArrayList<>();
        private int lookupCalls;
        private boolean failLookup;
        private boolean failRefresh;

        @Override
        public SubscriptionRefreshTarget findSubscriptionRefreshTarget(long subscriptionId)
                throws IOException {
            lookupCalls++;
            if (failLookup) {
                throw new IOException("secret lookup detail");
            }
            CanonicalHttpsUrl url = refreshTargets.get(subscriptionId);
            return url == null
                    ? null
                    : new SubscriptionRefreshTarget(
                            subscriptionId,
                            url,
                            refreshValidators.getOrDefault(
                                    subscriptionId,
                                    FeedValidators.none()), 1L);
        }

        @Override
        public long addConfirmedSubscription(
                CatalogSnapshot snapshot,
                FeedValidators validators) {
            added.add(snapshot);
            addedValidators.add(validators);
            existing.add(snapshot.getSubscriptionUrl().toString());
            refreshTargets.put(42L, snapshot.getSubscriptionUrl());
            return 42L;
        }

        @Override
        public void refreshExistingSubscription(
                SubscriptionRefreshTarget target,
                CatalogSnapshot snapshot,
                FeedValidators validators) throws IOException {
            if (failRefresh) {
                throw new IOException("secret save detail");
            }
            boolean known = existing.contains(snapshot.getSubscriptionUrl().toString());
            for (CanonicalHttpsUrl source : refreshTargets.values()) {
                if (source.equals(snapshot.getSubscriptionUrl())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new AssertionError("refresh attempted to add a subscription");
            }
            refreshed.add(snapshot);
            refreshedValidators.add(validators);
        }

        @Override
        public void recordUnmodifiedSubscription(
                SubscriptionRefreshTarget target,
                FeedValidators validators) throws IOException {
            if (failRefresh) {
                throw new IOException("secret save detail");
            }
            unmodifiedIds.add(target.getSubscriptionId());
            unmodifiedValidators.add(validators);
        }
    }

    private static final class BlockingTransport implements FeedTransport {
        private final String expectedUrl;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicLong requestCount = new AtomicLong();

        private BlockingTransport(String expectedUrl) {
            this.expectedUrl = expectedUrl;
        }

        @Override
        public Exchange execute(CanonicalHttpsUrl url, FeedRequestHeaders headers)
                throws IOException {
            if (!expectedUrl.equals(url.toString())) {
                throw new AssertionError("Unexpected refresh URL.");
            }
            requestCount.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for test release.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Test transport interrupted.");
            }
            return response(200, RSS);
        }
    }

    private static final class FakeTransport implements FeedTransport {
        private final Map<String, Queue<FakeExchange>> responses = new HashMap<>();
        private final List<String> requested = new ArrayList<>();
        private final List<FeedRequestHeaders> requestHeaders = new ArrayList<>();

        private void add(String url, FakeExchange response) {
            responses.computeIfAbsent(url, ignored -> new ArrayDeque<>()).add(response);
        }

        @Override
        public Exchange execute(CanonicalHttpsUrl url, FeedRequestHeaders headers)
                throws IOException {
            requested.add(url.toString());
            requestHeaders.add(headers);
            Queue<FakeExchange> queue = responses.get(url.toString());
            if (queue == null || queue.isEmpty()) {
                throw new IOException("No fake response for " + url.getOriginForDisplay());
            }
            return queue.remove();
        }
    }

    private static final class FakeExchange implements FeedTransport.Exchange {
        private final int status;
        private final InputStream body;
        private final Map<String, String> headers = new HashMap<>();

        private FakeExchange(int status, InputStream body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public int getStatusCode() {
            return status;
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
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
