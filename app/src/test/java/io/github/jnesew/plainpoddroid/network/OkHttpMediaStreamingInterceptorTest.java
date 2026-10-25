package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class OkHttpMediaStreamingInterceptorTest {
    @Test
    public void followsBoundedHttpsRedirectAndHoldsGlobalLeaseUntilClose()
            throws Exception {
        List<Request> requests = new ArrayList<>();
        Interceptor terminal = chain -> {
            Request request = chain.request();
            requests.add(request);
            if (requests.size() == 1) {
                return response(request, 302, "Redirect", "text/plain")
                        .newBuilder()
                        .header("Location", "https://cdn.example/final.mp3")
                        .build();
            }
            return response(request, 200, "OK", "audio/mpeg");
        };
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "0.1.0-dev"))
                .addInterceptor(terminal)
                .build();

        Response result = client.newCall(new Request.Builder()
                .url("https://media.example/private.mp3?token=secret")
                .header("Authorization", "secret")
                .header("Cookie", "secret=value")
                .build()).execute();

        assertEquals(2, requests.size());
        Request finalRequest = requests.get(1);
        assertEquals("https://cdn.example/final.mp3", finalRequest.url().toString());
        assertEquals("PlainPodDroid/0.1.0-dev", finalRequest.header("User-Agent"));
        assertEquals("identity", finalRequest.header("Accept-Encoding"));
        assertEquals("audio/*, application/octet-stream;q=0.5",
                finalRequest.header("Accept"));
        assertNull(finalRequest.header("Authorization"));
        assertNull(finalRequest.header("Cookie"));
        assertThrows(NetworkTransportException.class,
                () -> gate.acquirePlayback(0L, TimeUnit.MILLISECONDS));

        result.close();
        NetworkCallGate.Lease afterClose =
                gate.acquirePlayback(0L, TimeUnit.MILLISECONDS);
        afterClose.close();
    }

    @Test
    public void acceptsMaximumMediaRedirectsAndRejectsOneMoreWithoutLeakingHeadersOrLease()
            throws Exception {
        for (int redirects : new int[] {SecurityLimits.MAX_MEDIA_REDIRECTS,
                SecurityLimits.MAX_MEDIA_REDIRECTS + 1}) {
            List<Request> requests = new ArrayList<>();
            Interceptor terminal = chain -> {
                Request request = chain.request();
                int index = requests.size();
                requests.add(request);
                return index < redirects
                        ? response(request, 302, "Redirect", "text/plain").newBuilder()
                                .header("Location", "https://cdn" + index + ".example/audio.mp3")
                                .build()
                        : response(request, 200, "OK", "audio/mpeg");
            };
            NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
            OkHttpClient client = NetworkClientFactory.create().newBuilder()
                    .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "test"))
                    .addInterceptor(terminal).build();
            Request request = new Request.Builder().url("https://media.example/audio.mp3")
                    .header("Range", "bytes=0-4").header("Authorization", "secret")
                    .header("Cookie", "secret=value").header("Proxy-Authorization", "secret")
                    .header("Referer", "https://private.example/").build();
            if (redirects == SecurityLimits.MAX_MEDIA_REDIRECTS) {
                try (Response result = client.newCall(request).execute()) {
                    assertEquals(200, result.code());
                    assertThrows(NetworkTransportException.class,
                            () -> gate.acquirePlayback(0L, TimeUnit.MILLISECONDS));
                    assertEquals("audio", result.body().string());
                }
            } else {
                IOException failure = assertThrows(IOException.class,
                        () -> client.newCall(request).execute());
                assertEquals("Media streaming request failed.", failure.getMessage());
            }
            assertEquals(SecurityLimits.MAX_MEDIA_REDIRECTS + 1, requests.size());
            for (Request hop : requests) {
                assertEquals("bytes=0-4", hop.header("Range"));
                assertEquals("identity", hop.header("Accept-Encoding"));
                assertNull(hop.header("Authorization"));
                assertNull(hop.header("Cookie"));
                assertNull(hop.header("Proxy-Authorization"));
                assertNull(hop.header("Referer"));
            }
            gate.acquirePlayback(0L, TimeUnit.MILLISECONDS).close();
        }
    }

    @Test
    public void longTrackingChainStillRejectsPrivateHostAndReleasesLease() throws Exception {
        List<Request> requests = new ArrayList<>();
        Interceptor terminal = chain -> {
            requests.add(chain.request());
            return response(chain.request(), 302, "Redirect", "text/plain").newBuilder()
                    .header("Location", requests.size() == 8
                            ? "https://127.0.0.1/private.mp3?secret=value"
                            : "https://cdn.example/r" + requests.size() + ".mp3")
                    .build();
        };
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "test"))
                .addInterceptor(terminal).build();
        IOException failure = assertThrows(IOException.class, () -> client.newCall(
                new Request.Builder().url("https://media.example/audio.mp3").build()).execute());
        assertEquals("Media streaming request failed.", failure.getMessage());
        assertEquals(8, requests.size());
        gate.acquirePlayback(0L, TimeUnit.MILLISECONDS).close();
    }

    @Test
    public void rejectsUnsafeRedirectWithoutLeakingTheTokenOrLease() throws Exception {
        Interceptor terminal = chain -> response(
                chain.request(),
                302,
                "Redirect",
                "text/plain").newBuilder()
                .header("Location", "http://private.example/secret.mp3?token=value")
                .build();
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "test"))
                .addInterceptor(terminal)
                .build();

        IOException failure = assertThrows(IOException.class, () ->
                client.newCall(new Request.Builder()
                        .url("https://media.example/private.mp3?token=secret")
                        .build()).execute());

        assertEquals("Media streaming request failed.", failure.getMessage());
        assertFalse(failure.getMessage().contains("token"));
        NetworkCallGate.Lease afterFailure =
                gate.acquirePlayback(0L, TimeUnit.MILLISECONDS);
        afterFailure.close();
    }

    @Test
    public void rejectsRedirectLoopAndReleasesTheLease() throws Exception {
        Interceptor terminal = chain -> response(
                chain.request(),
                302,
                "Redirect",
                "text/plain").newBuilder()
                .header("Location", "https://media.example/loop.mp3")
                .build();
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "test"))
                .addInterceptor(terminal)
                .build();

        IOException failure = assertThrows(IOException.class, () ->
                client.newCall(new Request.Builder()
                        .url("https://media.example/loop.mp3")
                        .build()).execute());

        assertEquals("Media streaming request failed.", failure.getMessage());
        NetworkCallGate.Lease afterFailure =
                gate.acquirePlayback(0L, TimeUnit.MILLISECONDS);
        afterFailure.close();
    }

    @Test
    public void rejectsPlaylistAndEncodedResponses() throws Exception {
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        Interceptor terminal = chain -> response(
                chain.request(),
                200,
                "OK",
                "audio/mpeg").newBuilder()
                .header("Content-Encoding", "gzip")
                .build();
        OkHttpClient client = NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "test"))
                .addInterceptor(terminal)
                .build();

        assertThrows(IOException.class, () -> client.newCall(new Request.Builder()
                .url("https://media.example/episode.m3u8")
                .build()).execute());
        assertThrows(IOException.class, () -> client.newCall(new Request.Builder()
                .url("https://media.example/episode.mp3")
                .build()).execute());
    }

    @Test
    public void boundsUnknownLengthStreamingBytesAndReleasesFailedBody() throws Exception {
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        boolean[] closed = {false};
        OkHttpClient client = unknownLengthClient(gate, SecurityLimits.MAX_DOWNLOAD_BYTES + 1, closed);
        try (Response result = client.newCall(new Request.Builder()
                .url("https://media.example/unknown.mp3?token=secret").build()).execute()) {
            // Generate/discard lazily: no large fixture, allocation or network request.
            result.body().source().skip(SecurityLimits.MAX_DOWNLOAD_BYTES);
            IOException failure = assertThrows(IOException.class,
                    () -> result.body().source().readByte());
            assertEquals("Media streaming request failed.", failure.getMessage());
            org.junit.Assert.assertTrue(closed[0]);
            gate.acquirePlayback(0L, TimeUnit.MILLISECONDS).close();
        }
    }

    @Test
    public void permitsUnknownLengthEofExactlyAtTheStreamingByteLimit() throws Exception {
        NetworkCallGate gate = new NetworkCallGate(1, 1, 1);
        boolean[] closed = {false};
        OkHttpClient client = unknownLengthClient(gate, SecurityLimits.MAX_DOWNLOAD_BYTES, closed);
        try (Response result = client.newCall(new Request.Builder()
                .url("https://media.example/exact.mp3").build()).execute()) {
            result.body().source().skip(SecurityLimits.MAX_DOWNLOAD_BYTES);
            org.junit.Assert.assertTrue(result.body().source().exhausted());
            assertThrows(NetworkTransportException.class,
                    () -> gate.acquirePlayback(0L, TimeUnit.MILLISECONDS));
        }
        org.junit.Assert.assertTrue(closed[0]);
        gate.acquirePlayback(0L, TimeUnit.MILLISECONDS).close();
    }

    private static OkHttpClient unknownLengthClient(NetworkCallGate gate, long bytes, boolean[] closed) {
        okio.BufferedSource source = okio.Okio.buffer(new okio.Source() {
            private final byte[] block = new byte[8192];
            private long remaining = bytes;
            @Override public long read(okio.Buffer sink, long byteCount) {
                if (byteCount == 0) return 0;
                if (remaining == 0) return -1;
                int count = (int) Math.min(Math.min(byteCount, block.length), remaining);
                sink.write(block, 0, count);
                remaining -= count;
                return count;
            }
            @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
            @Override public void close() { closed[0] = true; }
        });
        ResponseBody body = new ResponseBody() {
            @Override public MediaType contentType() { return MediaType.get("audio/mpeg"); }
            @Override public long contentLength() { return -1; }
            @Override public okio.BufferedSource source() { return source; }
        };
        return NetworkClientFactory.create().newBuilder()
                .addInterceptor(new OkHttpMediaStreamingInterceptor(gate, "test"))
                .addInterceptor(chain -> response(chain.request(), 200, "OK", "audio/mpeg")
                        .newBuilder().removeHeader("Content-Length").body(body).build())
                .build();
    }

    private static Response response(
            Request request,
            int code,
            String message,
            String contentType) {
        byte[] bytes = "audio".getBytes(StandardCharsets.UTF_8);
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .header("Content-Type", contentType)
                .header("Content-Length", Integer.toString(bytes.length))
                .body(ResponseBody.create(bytes, MediaType.get(contentType)))
                .build();
    }
}
