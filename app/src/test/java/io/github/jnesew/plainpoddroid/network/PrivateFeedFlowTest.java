package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisode;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.download.*;
import io.github.jnesew.plainpoddroid.core.network.*;
import io.github.jnesew.plainpoddroid.core.subscription.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import okhttp3.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider;


/** Uses production HTTP adapters and coordinators with a token-enforcing simulated publisher.
 * No DNS/TLS socket is opened; SQLite and Android decoding are separate device gates. */
public final class PrivateFeedFlowTest {
    private static final String FEED = "https://feeds.example/member%2Fsecret/rss?token=a%2Fb&part=2";
    private static final byte[] AUDIO = "test audio".getBytes(StandardCharsets.UTF_8);
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void privateLinkSurvivesPreviewConfirmationRefreshDownloadAndRangeRequests() throws Exception {
        Fixture f = fixture();
        SubscriptionPreview preview = f.subscriptions.preview(FEED);
        assertNull(f.catalog.snapshot);
        assertEquals("https://feeds.example", preview.getOriginalOriginForDisplay());
        assertEquals(1L, f.subscriptions.confirm(preview));
        String id = f.catalog.episode().getMediaId();
        // A new coordinator must use the persisted original URL, including its escaped token.
        new SubscriptionCoordinator(f.feedTransport, f.catalog).refresh(1L);
        assertEquals(1, f.catalog.unmodified);
        assertEquals(FEED, f.catalog.snapshot.getSubscriptionUrl().toString());
        assertEquals(AUDIO.length, f.downloads.download(id, () -> false).getDownloadedBytes());
        assertArrayEquals(AUDIO, Files.readAllBytes(f.files.lookup(id).getCompleteFile().toPath()));
        Request range = new Request.Builder().url(f.catalog.episode().getAudioUrl().toString())
                .header("Range", "bytes=2-").build();
        try (Response response = f.streaming.newCall(range).execute()) {
            assertEquals(206, response.code());
            assertArrayEquals(java.util.Arrays.copyOfRange(AUDIO, 2, AUDIO.length), response.body().bytes());
        }
        assertTrue(f.publisher.requests.stream().anyMatch(r -> FEED.equals(r.url().toString())));
        assertTrue(f.publisher.requests.stream().anyMatch(r -> f.publisher.cdnUrl().equals(r.url().toString())
                && "bytes=2-".equals(r.header("Range"))));
        for (Request request : f.publisher.requests) {
            assertNull(request.header("Authorization"));
            assertNull(request.header("Cookie"));
            assertNull(request.header("Referer"));
            if (!request.url().host().equals("feeds.example"))
                assertFalse(request.url().toString().contains("member%2Fsecret"));
        }
    }

    @Test public void expiredAudioIsReplacedByRefreshWithoutChangingEpisodeIdentity() throws Exception {
        Fixture f = fixture();
        f.subscriptions.confirm(f.subscriptions.preview(FEED));
        String id = f.catalog.episode().getMediaId();
        f.publisher.version = 2;
        EpisodeDownloadException failure = assertThrows(EpisodeDownloadException.class,
                () -> f.downloads.download(id, () -> false));
        assertEquals(EpisodeDownloadException.Reason.ACCESS_DENIED, failure.getReason());
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(f.catalog.downloaded);
        assertEquals(DownloadLookup.State.ABSENT, f.files.lookup(id).getState());
        f.subscriptions.refresh(1L);
        assertEquals(id, f.catalog.episode().getMediaId());
        assertEquals(f.publisher.mediaUrl(), f.catalog.episode().getAudioUrl().toString());
        f.downloads.download(id, () -> false);
        assertTrue(f.catalog.downloaded);
    }

    @Test public void missingOrRevokedTokensCannotPreviewOrReplaceSavedEpisodes() throws Exception {
        for (int status : new int[] {401, 403}) {
            Fixture f = fixture();
            assertThrows(FeedAcquisitionException.class, () -> f.subscriptions.preview(
                    "https://feeds.example/member%2Fsecret/rss?token=wrong&part=2"));
            assertNull(f.catalog.snapshot);
            f.subscriptions.confirm(f.subscriptions.preview(FEED));
            CatalogSnapshot previous = f.catalog.snapshot;
            f.publisher.revokedStatus = status;
            SubscriptionRefreshException failure = assertThrows(SubscriptionRefreshException.class,
                    () -> f.subscriptions.refresh(1L));
            assertEquals(status == 401 ? SubscriptionRefreshException.Reason.LOGIN_REQUIRED
                    : SubscriptionRefreshException.Reason.ACCESS_DENIED, failure.getReason());
            assertSame(previous, f.catalog.snapshot);
            assertFalse(failure.getMessage().contains("secret"));
            assertFalse(failure.getMessage().contains("a%2Fb"));
            assertThrows(FeedAcquisitionException.class, () -> f.subscriptions.preview(FEED));
        }
    }

    @Test public void basicLoginIsSavedOnlyAfterConfirmationAndWorksForRefreshDownloadAndRange() throws Exception {
        Fixture f = fixture(); f.publisher.basic = true;
        String feed = f.publisher.feedUrl();
        var login = new BasicFeedLogin(CanonicalHttpsUrl.parse(feed), "test-user", "test-password",
                CanonicalHttpsUrl.parse("https://media.example/audio/"));
        var preview = f.subscriptions.preview(feed, login);
        assertNull(f.catalog.login); assertNull(f.catalog.snapshot);
        f.subscriptions.confirm(preview); assertTrue(login.sameLogin(f.catalog.login));
        new SubscriptionCoordinator(f.feedTransport, f.catalog).refresh(1L);
        assertEquals(1, f.catalog.unmodified);
        String id = f.catalog.episode().getMediaId(); f.downloads.download(id, () -> false);
        try (Response response = f.streaming.newCall(new Request.Builder().url(f.publisher.mediaUrl()).header("Range", "bytes=2-").build()).execute()) {
            assertEquals(206, response.code());
            assertArrayEquals(java.util.Arrays.copyOfRange(AUDIO, 2, AUDIO.length), response.body().bytes());
        }
        for (Request r : f.publisher.requests) {
            if (r.url().host().equals("cdn.example")) assertNull(r.header("Authorization"));
            assertNull(r.header("Cookie")); assertNull(r.header("Referer"));
        }
    }

    private Fixture fixture() throws Exception {
        Publisher publisher = new Publisher();
        OkHttpClient client = NetworkClientFactory.create().newBuilder().addInterceptor(publisher).build();
        NetworkCallGate gate = new NetworkCallGate();
        Catalog catalog = new Catalog();
        OkHttpFeedTransport feed = new OkHttpFeedTransport(client, gate, "test", catalog);
        AppPrivateDownloadStore files = new AppPrivateDownloadStore(temporary.newFolder());
        EpisodeDownloadCoordinator downloads = new EpisodeDownloadCoordinator(catalog, files,
                new OkHttpMediaDownloadTransport(client, gate, "test", catalog), () -> 1024L * 1024L * 1024L);
        OkHttpClient streaming = NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "test", catalog))
                .addInterceptor(publisher).build();
        return new Fixture(publisher, catalog, feed, new SubscriptionCoordinator(feed, catalog), files,
                downloads, streaming);
    }

    private record Fixture(Publisher publisher, Catalog catalog, OkHttpFeedTransport feedTransport,
            SubscriptionCoordinator subscriptions, AppPrivateDownloadStore files,
            EpisodeDownloadCoordinator downloads, OkHttpClient streaming) { }

    private static final class Publisher implements Interceptor {
        final List<Request> requests = new ArrayList<>();
        int version = 1, revokedStatus; boolean basic;
        String feedUrl() { return basic ? "https://feeds.example/members/rss?token=a%2Fb&part=2" : FEED; }
        String mediaUrl() { return "https://media.example/" + (basic ? "audio/" : "audio%2Fsecret/") + "episode.mp3?sig=v" + version + "%2Fok&part=2"; }
        String cdnUrl() { return "https://cdn.example/private/episode.mp3?signature=v" + version + "%2Fok"; }
        public Response intercept(Chain chain) {
            Request r = chain.request(); requests.add(r);
            String url = r.url().toString();
            if (r.url().host().equals("feeds.example")) {
                if (!feedUrl().equals(url) || revokedStatus != 0)
                    return response(r, revokedStatus == 0 ? 401 : revokedStatus, "text/plain", new byte[0]);
                if (basic && !authorized(r)) return challenge(r);
                String etag = "\"v" + version + "\"";
                if (etag.equals(r.header("If-None-Match")))
                    return response(r, 304, "application/rss+xml", new byte[0]).newBuilder().header("ETag", etag).build();
                String rss = "<rss><channel><title>Private show</title><item><guid>stable-episode</guid>"
                        + "<title>Episode</title><enclosure type=\"audio/mpeg\" url=\"" + mediaUrl().replace("&", "&amp;")
                        + "\" /></item></channel></rss>";
                return response(r, 200, "application/rss+xml", rss.getBytes(StandardCharsets.UTF_8))
                        .newBuilder().header("ETag", etag).header("Set-Cookie", "ignored=secret").build();
            }
            if (mediaUrl().equals(url) && basic && !authorized(r)) return challenge(r);
            if (mediaUrl().equals(url)) return response(r, 302, "text/plain", new byte[0])
                    .newBuilder().header("Location", cdnUrl()).build();
            if (cdnUrl().equals(url)) {
                if (r.header("Range") != null) {
                    assertEquals("bytes=2-", r.header("Range"));
                    return response(r, 206, "audio/mpeg", java.util.Arrays.copyOfRange(AUDIO, 2, AUDIO.length))
                            .newBuilder().header("Content-Range", "bytes 2-" + (AUDIO.length - 1) + "/" + AUDIO.length).build();
                }
                return response(r, 200, "audio/mpeg", AUDIO);
            }
            return response(r, 403, "text/plain", new byte[0]);
        }
        private static boolean authorized(Request r) {
            return ("Basic " + java.util.Base64.getEncoder().encodeToString("test-user:test-password".getBytes(StandardCharsets.ISO_8859_1)))
                    .equals(r.header("Authorization"));
        }
        private static Response challenge(Request r) { return response(r, 401, "text/plain", new byte[0]).newBuilder()
                .header("WWW-Authenticate", "Basic realm=\"members\"").build(); }
        private static Response response(Request r, int status, String type, byte[] bytes) {
            return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("Test")
                    .header("Content-Type", type).header("Content-Length", Integer.toString(bytes.length))
                    .body(ResponseBody.create(bytes, MediaType.get(type))).build();
        }
    }

    private static final class Catalog implements SubscriptionStore, EpisodeDownloadCatalog, FeedLoginProvider {
        BasicFeedLogin login;
        CatalogSnapshot snapshot; FeedValidators validators = FeedValidators.none();
        boolean downloaded; int unmodified;
        CatalogEpisode episode() { return snapshot.getEpisodes().get(0); }
        public BasicFeedLogin findFeedLogin(CanonicalHttpsUrl original) {
            return snapshot != null && snapshot.getSubscriptionUrl().equals(original) ? login : null;
        }
        public BasicFeedLogin findAudioLogin(CanonicalHttpsUrl original) {
            return snapshot != null && episode().getAudioUrl().equals(original) ? login : null;
        }
        public void rejectLogin(CanonicalHttpsUrl original, boolean audio, BasicFeedLogin attempted) { }
        public long addConfirmedSubscription(CatalogSnapshot value, FeedValidators v, BasicFeedLogin supplied) {
            long id = addConfirmedSubscription(value, v); login = supplied; return id;
        }
        public long addConfirmedSubscription(CatalogSnapshot value, FeedValidators v) {
            snapshot = value; validators = v; return 1L;
        }
        public SubscriptionRefreshTarget findSubscriptionRefreshTarget(long id) {
            return snapshot == null ? null : new SubscriptionRefreshTarget(id, snapshot.getSubscriptionUrl(), validators, 1L);
        }
        public void refreshExistingSubscription(SubscriptionRefreshTarget target, CatalogSnapshot value, FeedValidators v) { snapshot = value; validators = v; }
        public void recordUnmodifiedSubscription(SubscriptionRefreshTarget target, FeedValidators v) { validators = v; unmodified++; }
        public EpisodeDownloadTarget findEpisodeDownloadTarget(String id) {
            CatalogEpisode e = episode();
            return new EpisodeDownloadTarget(id, e.getAudioUrl(), e.getMimeType(), e.getDeclaredLength(), downloaded);
        }
        public boolean markEpisodeDownloadComplete(EpisodeDownloadTarget target, long bytes) { downloaded = true; return true; }
        public void markEpisodeDownloadAbsent(String id) { downloaded = false; }
    }
}
