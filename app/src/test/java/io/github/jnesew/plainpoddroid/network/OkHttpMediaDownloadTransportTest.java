package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.download.MediaDownloadTransport;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

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

public final class OkHttpMediaDownloadTransportTest {
    private static final byte[] BODY = "audio".getBytes(StandardCharsets.UTF_8);

    @Test
    public void sendsStaticIdentityHeadersAndHoldsMediaLeaseUntilClose()
            throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        Interceptor response = chain -> {
            captured.set(chain.request());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "audio/mpeg")
                    .addHeader("Content-Length", Integer.toString(BODY.length))
                    .body(ResponseBody.create(BODY, MediaType.get("audio/mpeg")))
                    .build();
        };
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(response)
                .build();
        NetworkCallGate gate = new NetworkCallGate(2, 1, 1);
        OkHttpMediaDownloadTransport transport = new OkHttpMediaDownloadTransport(
                client,
                gate,
                "0.1.0-dev");

        MediaDownloadTransport.Exchange exchange = transport.execute(
                CanonicalHttpsUrl.parse(
                        "https://media.example/private.mp3?token=secret"));

        Request request = captured.get();
        assertEquals("GET", request.method());
        assertEquals("PlainPodDroid/0.1.0-dev", request.header("User-Agent"));
        assertEquals("identity", request.header("Accept-Encoding"));
        assertEquals("audio/*, application/octet-stream;q=0.5",
                request.header("Accept"));
        assertNull(request.header("Authorization"));
        assertNull(request.header("Cookie"));
        assertNull(request.header("Referer"));
        assertEquals(200, exchange.getStatusCode());
        assertEquals("audio/mpeg", exchange.getHeader("Content-Type"));
        assertEquals(BODY[0], exchange.getBody().read());

        NetworkTransportException mediaBusy = assertThrows(
                NetworkTransportException.class,
                () -> gate.acquireMedia(0L, TimeUnit.MILLISECONDS));
        assertEquals(NetworkTransportException.Reason.CONCURRENCY_LIMIT,
                mediaBusy.getReason());
        NetworkCallGate.Lease simultaneousFeed =
                gate.acquireFeed(0L, TimeUnit.MILLISECONDS);
        simultaneousFeed.close();

        exchange.close();
        NetworkCallGate.Lease afterClose =
                gate.acquireMedia(0L, TimeUnit.MILLISECONDS);
        afterClose.close();
    }

    @Test
    public void sanitizesFailuresAndReleasesMediaGate() throws Exception {
        Interceptor failure = chain -> {
            throw new IOException("https://media.example/private.mp3?token=secret");
        };
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(failure)
                .build();
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        OkHttpMediaDownloadTransport transport = new OkHttpMediaDownloadTransport(
                client,
                gate,
                "test");

        NetworkTransportException exception = assertThrows(
                NetworkTransportException.class,
                () -> transport.execute(CanonicalHttpsUrl.parse(
                        "https://media.example/private.mp3?token=secret")));

        assertEquals(NetworkTransportException.Reason.REQUEST_FAILED,
                exception.getReason());
        assertFalse(exception.getMessage().contains("private"));
        assertFalse(exception.getMessage().contains("secret"));
        assertNull(exception.getCause());
        NetworkCallGate.Lease afterFailure =
                gate.acquireMedia(0L, TimeUnit.MILLISECONDS);
        afterFailure.close();
    }

    @Test
    public void rejectsFingerprintingOrHeaderInjectionInVersionNames() {
        OkHttpClient client = NetworkClientFactory.create();
        NetworkCallGate gate = new NetworkCallGate();

        assertThrows(IllegalArgumentException.class, () ->
                new OkHttpMediaDownloadTransport(client, gate, "device/model"));
        assertThrows(IllegalArgumentException.class, () ->
                new OkHttpMediaDownloadTransport(
                        client,
                        gate,
                        "1.0\r\nCookie: value"));
    }
}
