package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedRequestHeaders;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OfflineNetworkGuardTest {
    @Test public void downloadPreferenceBlocksTheStreamingFactoryIndependentlyOfOfflineMode() {
        PolicyNetworkStack stack = PolicyNetworkStack.create();
        assertThrows(IOException.class, () -> stack.newMediaStreamingCallFactory("test", () -> true)
                .newCall(new Request.Builder().url(URL).build()).execute());
    }
    private static final String URL = "https://feeds.example/private?token=secret";

    @Test public void blocksEveryProductionTransportIncludingMediaClones() throws Exception {
        PolicyNetworkStack stack = PolicyNetworkStack.create(FeedLoginProvider.NONE, () -> true);
        CanonicalHttpsUrl url = CanonicalHttpsUrl.parse(URL);
        assertThrows(IOException.class, () -> stack.newFeedTransport("test").execute(url, FeedRequestHeaders.none()));
        assertThrows(IOException.class, () -> stack.newArtworkTransport("test").execute(url));
        assertThrows(IOException.class, () -> stack.newChapterTransport("test").execute(url));
        assertThrows(IOException.class, () -> stack.newMediaDownloadTransport("test").execute(url));
        assertThrows(IOException.class, () -> stack.newApplePodcastLookupTransport("test").execute(
                CanonicalHttpsUrl.parse("https://itunes.apple.com/lookup?id=123&entity=podcast&country=fi")));
        assertThrows(IOException.class, () -> stack.newMediaStreamingCallFactory("test")
                .newCall(new Request.Builder().url(URL).build()).execute());
    }

    @Test public void blocksBeforeDnsAndBeforeAnyLaterInterceptor() {
        AtomicInteger dns = new AtomicInteger();
        AtomicInteger downstream = new AtomicInteger();
        OfflineNetworkGuard guard = new OfflineNetworkGuard(() -> true);
        OkHttpClient client = NetworkClientFactory.create(host -> {
            dns.incrementAndGet(); throw new UnknownHostException();
        }, guard).newBuilder().addInterceptor(chain -> {
            downstream.incrementAndGet(); return response(chain.request());
        }).build();
        IOException failure = assertThrows(IOException.class,
                () -> client.newCall(new Request.Builder().url(URL).build()).execute());
        assertEquals(0, dns.get()); assertEquals(0, downstream.get());
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test public void dnsNeverConsultsResolverOfflineAndRejectsARevokedAnswer() throws Exception {
        AtomicBoolean offline = new AtomicBoolean(true);
        AtomicInteger requests = new AtomicInteger();
        PolicyDns dns = new PolicyDns(host -> {
            requests.incrementAndGet(); offline.set(true);
            return List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}));
        }, offline::get);
        assertThrows(UnknownHostException.class, () -> dns.lookup("feeds.example"));
        assertEquals(0, requests.get());
        offline.set(false);
        assertThrows(UnknownHostException.class, () -> dns.lookup("feeds.example"));
        assertEquals(1, requests.get());
    }

    @Test public void cancelsResponseBodiesEvenAfterHeadersAndCannotReviveOldCalls() throws Exception {
        AtomicBoolean offline = new AtomicBoolean();
        OfflineNetworkGuard guard = new OfflineNetworkGuard(offline::get);
        OkHttpClient client = NetworkClientFactory.create(host -> { throw new UnknownHostException(); }, guard)
                .newBuilder().addInterceptor(chain -> response(chain.request())).build();
        var call = client.newCall(new Request.Builder().url(URL).build());
        try (Response response = call.execute()) {
            offline.set(true); guard.policyChanged();
            assertTrue(call.isCanceled());
            offline.set(false);
            assertThrows(IOException.class, () -> response.body().string());
        }
        try (Response response = client.newCall(new Request.Builder().url(URL).build()).execute()) {
            assertEquals("body", response.body().string());
        }
    }

    @Test public void enablingWhileAwaitingHeadersClosesAndRejectsLateResponse() {
        AtomicBoolean offline = new AtomicBoolean();
        OfflineNetworkGuard guard = new OfflineNetworkGuard(offline::get);
        OkHttpClient client = NetworkClientFactory.create(host -> { throw new UnknownHostException(); }, guard)
                .newBuilder().addInterceptor(chain -> {
                    offline.set(true); guard.policyChanged(); return response(chain.request());
                }).build();
        var call = client.newCall(new Request.Builder().url(URL).build());
        assertThrows(IOException.class, call::execute);
        assertTrue(call.isCanceled());
    }

    private static Response response(Request request) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(ResponseBody.create("body", MediaType.get("text/plain"))).build();
    }
}
