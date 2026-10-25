package io.github.jnesew.plainpoddroid.core.artwork;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BoundedArtworkAcquirerTest {
    private static final String URL = "https://publisher.example/art?token=private";
    private static final byte[] PNG = {
            (byte)137,80,78,71,13,10,26,10,0,0,0,0
    };

    @Test public void acceptsBoundedPngAndDefensivelyCopiesBytes() throws Exception {
        Fake response = ok(PNG, Map.of());
        ArtworkPayload payload = acquire(response);
        assertEquals(ArtworkPayload.Format.PNG, payload.getFormat());
        byte[] copy = payload.getBytes();
        copy[0] = 0;
        assertEquals(137, payload.getBytes()[0] & 255);
        assertTrue(response.closed);
    }

    @Test public void acceptsArtworkAboveFormerLimitButWithinTwentyMib() throws Exception {
        byte[] large = Arrays.copyOf(PNG, 8 * 1024 * 1024);
        ArtworkPayload payload = acquire(ok(large, Map.of("Content-Length", Integer.toString(large.length))));
        assertEquals(large.length, payload.size());
        assertEquals(137, payload.openStream().read());
        assertEquals(20L * 1024 * 1024, SecurityLimits.MAX_ARTWORK_BYTES);
    }

    @Test public void rejectsWrongTypeAndAnimatedWebp() throws Exception {
        for (String mime : List.of("image/svg+xml", "image/gif", "text/html",
                "image/png, image/jpeg", "image/png; charset=utf-8")) {
            rejects(ArtworkAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE,
                    ok(PNG, Map.of("Content-Type", mime)));
        }
        rejects(ArtworkAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE,
                new Fake(200, Map.of(), PNG));
        rejects(ArtworkAcquisitionException.Reason.INVALID_IMAGE,
                ok(new byte[] {'G','I','F','8'}, Map.of()));
        byte[] animated = new byte[21];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, animated, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, animated, 8, 8);
        animated[20] = 2;
        rejects(ArtworkAcquisitionException.Reason.INVALID_IMAGE,
                ok(animated, Map.of("Content-Type", "image/webp")));
    }

    @Test public void boundsBothDeclaredAndActualBytesAndRejectsCompressionBombs()
            throws Exception {
        rejects(ArtworkAcquisitionException.Reason.INVALID_CONTENT_LENGTH,
                ok(PNG, Map.of("Content-Length", "9999999999999999999999")));
        rejects(ArtworkAcquisitionException.Reason.INVALID_CONTENT_LENGTH,
                ok(PNG, Map.of("Content-Length", Long.toString(
                        SecurityLimits.MAX_ARTWORK_BYTES + 1))));
        rejects(ArtworkAcquisitionException.Reason.RAW_BODY_LIMIT,
                ok(new byte[(int)SecurityLimits.MAX_ARTWORK_BYTES + 1], Map.of()));
        rejects(ArtworkAcquisitionException.Reason.EXPANSION_RATIO,
                ok(gzip(new byte[100_000]), Map.of("Content-Encoding", "gzip")));
        rejects(ArtworkAcquisitionException.Reason.UNSUPPORTED_ENCODING,
                ok(PNG, Map.of("Content-Encoding", "gzip,gzip")));
        byte[] truncated = gzip(PNG);
        rejects(ArtworkAcquisitionException.Reason.INVALID_COMPRESSION,
                ok(Arrays.copyOf(truncated, truncated.length - 5),
                        Map.of("Content-Encoding", "gzip")));
    }

    @Test public void redirectsAreBoundedRevalidatedAndCloseResponses() throws Exception {
        for (String location : List.of("http://publisher.example/a",
                "https://127.0.0.1/a", "https://u:p@publisher.example/a", "")) {
            Fake response = new Fake(302, Map.of("Location", location), null);
            QueueTransport transport = new QueueTransport(response);
            ArtworkAcquisitionException error = assertThrows(ArtworkAcquisitionException.class,
                    () -> new BoundedArtworkAcquirer(transport).acquire(
                            CanonicalHttpsUrl.parse(URL)));
            assertEquals(ArtworkAcquisitionException.Reason.INVALID_REDIRECT,
                    error.getReason());
            assertEquals(1, transport.urls.size());
            assertTrue(response.closed);
            assertFalse(error.getMessage().contains("private"));
        }
        Fake moved = new Fake(307, Map.of("Location", "/safe"), null);
        Fake target = ok(PNG, Map.of());
        QueueTransport transport = new QueueTransport(moved, target);
        assertEquals(ArtworkPayload.Format.PNG,
                new BoundedArtworkAcquirer(transport).acquire(CanonicalHttpsUrl.parse(URL))
                        .getFormat());
        assertEquals("https://publisher.example/safe", transport.urls.get(1));
        assertTrue(moved.closed && target.closed);
        rejects(ArtworkAcquisitionException.Reason.REDIRECT_LOOP,
                new Fake(301, Map.of("Location", URL), null));
        List<Fake> redirects = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            redirects.add(new Fake(308, Map.of("Location", "/step" + i), null));
        }
        QueueTransport six = new QueueTransport(redirects.toArray(new Fake[0]));
        ArtworkAcquisitionException error = assertThrows(ArtworkAcquisitionException.class,
                () -> new BoundedArtworkAcquirer(six).acquire(CanonicalHttpsUrl.parse(URL)));
        assertEquals(ArtworkAcquisitionException.Reason.TOO_MANY_REDIRECTS,
                error.getReason());
        assertTrue(redirects.stream().allMatch(item -> item.closed));
    }

    @Test public void missingBodyOrUnexpectedStatusFailsClosed() throws Exception {
        rejects(ArtworkAcquisitionException.Reason.MISSING_BODY, ok(null, Map.of()));
        rejects(ArtworkAcquisitionException.Reason.HTTP_STATUS,
                new Fake(304, Map.of(), null));
    }

    private static Fake ok(byte[] body, Map<String,String> overrides) {
        java.util.HashMap<String,String> headers = new java.util.HashMap<>(overrides);
        headers.putIfAbsent("Content-Type", "image/png");
        return new Fake(200, headers, body);
    }

    private static ArtworkPayload acquire(Fake response) throws Exception {
        return new BoundedArtworkAcquirer(new QueueTransport(response))
                .acquire(CanonicalHttpsUrl.parse(URL));
    }

    private static void rejects(ArtworkAcquisitionException.Reason reason, Fake response) {
        ArtworkAcquisitionException error = assertThrows(ArtworkAcquisitionException.class,
                () -> acquire(response));
        assertEquals(reason, error.getReason());
        assertTrue(response.closed);
        assertFalse(error.getMessage().contains("private"));
    }

    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(input); }
        return output.toByteArray();
    }

    private static final class QueueTransport implements ArtworkTransport {
        final ArrayDeque<Fake> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        QueueTransport(Fake... values) { responses.addAll(Arrays.asList(values)); }
        @Override public Exchange execute(CanonicalHttpsUrl url) {
            urls.add(url.toString());
            return responses.removeFirst();
        }
    }

    private static final class Fake implements ArtworkTransport.Exchange {
        final int status;
        final Map<String,String> headers;
        final byte[] body;
        boolean closed;
        Fake(int status, Map<String,String> headers, byte[] body) {
            this.status = status; this.headers = headers; this.body = body;
        }
        @Override public int getStatusCode() { return status; }
        @Override public String getHeader(String name) { return headers.get(name); }
        @Override public InputStream getBody() {
            return body == null ? null : new ByteArrayInputStream(body);
        }
        @Override public void close() { closed = true; }
    }
}
