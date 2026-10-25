package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedRequestHeaders;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class OkHttpFeedTransportTest {
    private static final byte[] BODY = "<rss/>".getBytes(StandardCharsets.UTF_8);

    @Test
    public void emitsOnlyStaticFeedHeadersAndReleasesOnClose() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        ResponseBody body = ResponseBody.create(
                BODY,
                MediaType.get("application/rss+xml"));
        Interceptor response = chain -> {
            captured.set(chain.request());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "application/rss+xml")
                    .addHeader("Content-Encoding", "gzip")
                    .addHeader("Content-Encoding", "identity")
                    .body(body)
                    .build();
        };
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(response)
                .build();
        NetworkCallGate gate = new NetworkCallGate(1, 1);
        OkHttpFeedTransport transport = new OkHttpFeedTransport(client, gate, "0.1.0-dev");

        FeedTransport.Exchange exchange = transport.execute(
                CanonicalHttpsUrl.parse("https://feeds.example/private?token=secret"),
                FeedRequestHeaders.none());

        Request request = captured.get();
        assertEquals("GET", request.method());
        assertEquals("PlainPodDroid/0.1.0-dev", request.header("User-Agent"));
        assertEquals("gzip", request.header("Accept-Encoding"));
        assertEquals(
                "application/rss+xml, application/atom+xml, "
                        + "application/xml;q=0.9, text/xml;q=0.9",
                request.header("Accept"));
        assertNull(request.header("Authorization"));
        assertNull(request.header("Cookie"));
        assertNull(request.header("Referer"));
        assertEquals(200, exchange.getStatusCode());
        assertEquals("application/rss+xml", exchange.getHeader("Content-Type"));
        assertEquals("gzip,identity", exchange.getHeader("Content-Encoding"));
        assertEquals(BODY[0], exchange.getBody().read());
        NetworkTransportException busy = assertThrows(
                NetworkTransportException.class,
                () -> gate.acquireFeed(0L, TimeUnit.MILLISECONDS));
        assertEquals(NetworkTransportException.Reason.CONCURRENCY_LIMIT, busy.getReason());
        exchange.close();
        NetworkCallGate.Lease afterClose = gate.acquireFeed(0L, TimeUnit.MILLISECONDS);
        afterClose.close();
    }

    @Test
    public void sanitizesExecutionFailuresAndReleasesTheGate() throws Exception {
        Interceptor failure = chain -> {
            throw new IOException("https://feeds.example/private?token=secret");
        };
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(failure)
                .build();
        NetworkCallGate gate = new NetworkCallGate(1, 1);
        OkHttpFeedTransport transport = new OkHttpFeedTransport(client, gate, "test");

        NetworkTransportException exception = assertThrows(
                NetworkTransportException.class,
                () -> transport.execute(
                        CanonicalHttpsUrl.parse(
                                "https://feeds.example/private?token=secret"),
                        FeedRequestHeaders.none()));

        assertEquals(NetworkTransportException.Reason.REQUEST_FAILED, exception.getReason());
        assertFalse(exception.getMessage().contains("private"));
        assertFalse(exception.getMessage().contains("secret"));
        assertNull(exception.getCause());
        NetworkCallGate.Lease afterFailure = gate.acquireFeed(0L, TimeUnit.MILLISECONDS);
        afterFailure.close();
    }

    @Test
    public void emitsOnlyExplicitBoundedConditionalHeaders() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        Interceptor response = chain -> {
            captured.set(chain.request());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(304)
                    .message("Not Modified")
                    .build();
        };
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(response)
                .build();
        OkHttpFeedTransport transport = new OkHttpFeedTransport(
                client,
                new NetworkCallGate(1, 1),
                "test");
        CanonicalHttpsUrl url = CanonicalHttpsUrl.parse("https://feeds.example/feed");
        FeedRequestHeaders conditional = FeedValidators.of(
                url,
                "W/\"known\"",
                "Wed, 21 Oct 2015 07:28:00 GMT").headersFor(url);

        FeedTransport.Exchange exchange = transport.execute(url, conditional);

        assertEquals("W/\"known\"", captured.get().header("If-None-Match"));
        assertEquals(
                "Wed, 21 Oct 2015 07:28:00 GMT",
                captured.get().header("If-Modified-Since"));
        assertNull(captured.get().header("Authorization"));
        assertNull(captured.get().header("Cookie"));
        exchange.close();
    }

    @Test
    public void rejectsFingerprintingOrHeaderInjectionInVersionNames() {
        OkHttpClient client = NetworkClientFactory.create();
        NetworkCallGate gate = new NetworkCallGate();

        assertThrows(
                IllegalArgumentException.class,
                () -> new OkHttpFeedTransport(client, gate, "device/model"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OkHttpFeedTransport(client, gate, "1.0\r\nCookie: value"));
    }
}
