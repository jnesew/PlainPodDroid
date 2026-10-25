package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OkHttpApplePodcastLookupTransportTest {
    private static final String URL = "https://itunes.apple.com/lookup?id=123456789&entity=podcast&country=fi";

    @Test public void lookupHasOnlyStaticHeadersNoLoginRetryAndReleasesLease() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        OkHttpClient client = NetworkClientFactory.create().newBuilder().addInterceptor(chain -> {
            assertNull(captured.getAndSet(chain.request()));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(401).message("Unauthorized").header("WWW-Authenticate", "Basic realm=\"feed\"").build();
        }).build();
        NetworkCallGate gate = new NetworkCallGate(1, 1);
        var transport = new OkHttpApplePodcastLookupTransport(client, gate, "test");
        try (var exchange = transport.execute(CanonicalHttpsUrl.parse(URL))) {
            assertEquals(401, exchange.getStatusCode());
            assertThrows(NetworkTransportException.class, () -> gate.acquireFeed(0, TimeUnit.MILLISECONDS));
        }
        Request request = captured.get();
        assertEquals(3, request.headers().size());
        assertEquals("identity", request.header("Accept-Encoding"));
        assertEquals("application/json", request.header("Accept"));
        assertEquals("PlainPodDroid/test", request.header("User-Agent"));
        assertNull(request.header("Authorization")); assertNull(request.header("Cookie")); assertNull(request.header("Referer"));
        gate.acquireFeed(0, TimeUnit.MILLISECONDS).close();
        assertFalse(client.followRedirects()); assertFalse(client.followSslRedirects());
    }

    @Test public void rejectsArbitraryLookupTargetsAndSanitizesNetworkFailures() throws Exception {
        NetworkCallGate gate = new NetworkCallGate(1, 1);
        OkHttpClient client = NetworkClientFactory.create().newBuilder().addInterceptor(chain -> {
            throw new IOException("secret provider details");
        }).build();
        var transport = new OkHttpApplePodcastLookupTransport(client, gate, "test");
        for (String url : new String[]{URL.replace("itunes.apple.com", "other.example"),
                URL + "&callback=execute", URL.replace("/lookup?", "/search?")}) {
            assertThrows(IOException.class, () -> transport.execute(CanonicalHttpsUrl.parse(url)));
        }
        IOException failure = assertThrows(IOException.class, () -> transport.execute(CanonicalHttpsUrl.parse(URL)));
        assertFalse(failure.getMessage().contains("secret")); assertNull(failure.getCause());
        gate.acquireFeed(0, TimeUnit.MILLISECONDS).close();
    }
}
