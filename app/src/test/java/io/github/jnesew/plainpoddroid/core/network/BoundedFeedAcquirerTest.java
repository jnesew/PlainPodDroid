package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class BoundedFeedAcquirerTest {
    private static final byte[] RSS = ("<?xml version=\"1.0\"?>"
            + "<rss version=\"2.0\"><channel><title>Example</title>"
            + "<item><guid>one</guid><title>Episode</title>"
            + "<enclosure url=\"https://media.example/one.mp3\" type=\"audio/mpeg\"/>"
            + "</item></channel></rss>").getBytes(StandardCharsets.UTF_8);

    @Test
    public void acquiresCanonicalFeedAndClosesExchange() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeExchange response = response(200, RSS);
        response.headers.put("Content-Type", "application/rss+xml; charset=utf-8");
        response.headers.put("Content-Length", Integer.toString(RSS.length));
        transport.add("https://feeds.example/feed", response);

        FeedAcquisition result = new BoundedFeedAcquirer(transport)
                .acquire("HTTPS://FEEDS.EXAMPLE:443/feed#fragment");

        assertEquals("https://feeds.example/feed", result.getOriginalUrl().toString());
        assertEquals(result.getOriginalUrl(), result.getFinalUrl());
        assertEquals(List.of("https://feeds.example"), result.getRedirectOriginsForDisplay());
        assertEquals("Example", result.getDocument().getTitle());
        assertEquals(1, result.getDocument().getEpisodes().size());
        assertTrue(response.closed);
    }

    @Test
    public void resolvesAndRevalidatesRelativeRedirects() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeExchange redirect = response(302, new byte[0]);
        redirect.headers.put("Location", "../new/feed.xml#publisher-fragment");
        transport.add("https://one.example/old/feed.xml?token=secret", redirect);
        transport.add("https://one.example/new/feed.xml", response(200, RSS));

        FeedAcquisition result = new BoundedFeedAcquirer(transport)
                .acquire("https://one.example/old/feed.xml?token=secret");

        assertEquals("https://one.example/new/feed.xml", result.getFinalUrl().toString());
        assertEquals(2, transport.requested.size());
        assertFalse(transport.requested.get(1).contains("fragment"));
        assertTrue(redirect.closed);
    }

    @Test
    public void rejectsDowngradeLoopAndSixthRedirect() throws Exception {
        FakeTransport downgrade = new FakeTransport();
        FakeExchange downgradeResponse = response(301, new byte[0]);
        downgradeResponse.headers.put("Location", "http://private.example/feed");
        downgrade.add("https://start.example/feed", downgradeResponse);
        assertNetworkReason(
                downgrade,
                "https://start.example/feed",
                NetworkPolicyException.Reason.HTTPS_REQUIRED);

        FakeTransport loop = new FakeTransport();
        FakeExchange loopResponse = response(302, new byte[0]);
        loopResponse.headers.put("Location", "/feed");
        loop.add("https://feeds.example/feed", loopResponse);
        assertReason(loop, FeedAcquisitionException.Reason.REDIRECT_LOOP);

        FakeTransport excessive = new FakeTransport();
        for (int index = 0; index <= SecurityLimits.MAX_REDIRECTS; index++) {
            FakeExchange hop = response(302, new byte[0]);
            hop.headers.put("Location", "/" + (index + 1));
            excessive.add(
                    index == 0 ? "https://feeds.example/feed" : "https://feeds.example/" + index,
                    hop);
        }
        assertReason(excessive, FeedAcquisitionException.Reason.TOO_MANY_REDIRECTS);
    }

    @Test
    public void rejectsInvalidRedirectWithoutLeakingLocation() {
        FakeTransport transport = new FakeTransport();
        FakeExchange response = response(302, new byte[0]);
        response.headers.put("Location", "https://user:super-secret@next.example/feed");
        transport.add("https://start.example/feed?token=hidden", response);

        NetworkPolicyException exception = assertThrows(
                NetworkPolicyException.class,
                () -> new BoundedFeedAcquirer(transport)
                        .acquire("https://start.example/feed?token=hidden"));

        assertEquals(NetworkPolicyException.Reason.USER_INFO_FORBIDDEN, exception.getReason());
        assertFalse(exception.getMessage().contains("secret"));
        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    public void rejectsUnsupportedResponseMetadataBeforeReadingBody() {
        FakeTransport typeTransport = new FakeTransport();
        FakeExchange type = response(200, RSS);
        type.headers.put("Content-Type", "text/html");
        typeTransport.add("https://feeds.example/feed", type);
        assertReason(typeTransport, FeedAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE);

        FakeTransport encodingTransport = new FakeTransport();
        FakeExchange encoding = response(200, RSS);
        encoding.headers.put("Content-Encoding", "br");
        encodingTransport.add("https://feeds.example/feed", encoding);
        assertReason(encodingTransport, FeedAcquisitionException.Reason.UNSUPPORTED_ENCODING);

        FakeTransport lengthTransport = new FakeTransport();
        FailOnReadInputStream unread = new FailOnReadInputStream();
        FakeExchange length = new FakeExchange(200, unread);
        length.headers.put(
                "Content-Length",
                Long.toString(SecurityLimits.MAX_FEED_COMPRESSED_BYTES + 1));
        lengthTransport.add("https://feeds.example/feed", length);
        assertReason(lengthTransport, FeedAcquisitionException.Reason.RAW_BODY_LIMIT);
        assertFalse(unread.readAttempted);
    }

    @Test
    public void enforcesRawBodyLimitWhenLengthIsUnknown() {
        byte[] oversized = new byte[(int) SecurityLimits.MAX_FEED_COMPRESSED_BYTES + 1];
        for (int index = 0; index < oversized.length; index++) {
            oversized[index] = ' ';
        }
        FakeTransport transport = new FakeTransport();
        transport.add("https://feeds.example/feed", response(200, oversized));

        assertReason(transport, FeedAcquisitionException.Reason.RAW_BODY_LIMIT);
    }

    @Test
    public void acceptsBoundedGzipAndRejectsExpansionBomb() throws Exception {
        FakeTransport accepted = new FakeTransport();
        FakeExchange good = response(200, gzip(RSS));
        good.headers.put("Content-Encoding", "gzip");
        accepted.add("https://feeds.example/feed", good);
        assertEquals("Example", new BoundedFeedAcquirer(accepted)
                .acquire("https://feeds.example/feed")
                .getDocument()
                .getTitle());

        String block = randomAscii(8 * 1024);
        String notes = block.repeat(40);
        byte[] bombXml = ("<rss><channel><title>Bomb</title><description>"
                + notes
                + "</description></channel></rss>").getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(bombXml);
        assertTrue(compressed.length > SecurityLimits.FEED_RATIO_GRACE_BYTES);
        assertTrue(bombXml.length > compressed.length * SecurityLimits.MAX_FEED_EXPANSION_RATIO);

        FakeTransport rejected = new FakeTransport();
        FakeExchange bomb = response(200, compressed);
        bomb.headers.put("Content-Encoding", "gzip");
        rejected.add("https://feeds.example/feed", bomb);
        assertReason(rejected, FeedAcquisitionException.Reason.EXPANSION_RATIO);
    }

    @Test
    public void rejectsInvalidGzipAndRedactsHttpErrors() {
        FakeTransport invalid = new FakeTransport();
        FakeExchange broken = response(200, "not-gzip".getBytes(StandardCharsets.UTF_8));
        broken.headers.put("Content-Encoding", "gzip");
        invalid.add("https://feeds.example/feed", broken);
        assertReason(invalid, FeedAcquisitionException.Reason.INVALID_COMPRESSION);

        FakeTransport status = new FakeTransport();
        status.add("https://feeds.example/private?token=secret", response(503, RSS));
        FeedAcquisitionException exception = assertThrows(
                FeedAcquisitionException.class,
                () -> new BoundedFeedAcquirer(status)
                        .acquire("https://feeds.example/private?token=secret"));
        assertEquals(FeedAcquisitionException.Reason.HTTP_STATUS, exception.getReason());
        assertFalse(exception.getMessage().contains("private"));
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test
    public void sendsValidatorsOnlyOnTheirExactResourceAndAcceptsConditional304()
            throws Exception {
        String original = "https://start.example/feed?token=secret";
        String finalUrl = "https://final.example/feed";
        FakeTransport transport = new FakeTransport();
        FakeExchange redirect = response(302, new byte[0]);
        redirect.headers.put("Location", finalUrl);
        transport.add(original, redirect);
        FakeExchange notModified = response(304, new byte[0]);
        notModified.headers.put("ETag", "\"updated\"");
        transport.add(finalUrl, notModified);
        FeedValidators validators = FeedValidators.of(
                CanonicalHttpsUrl.parse(finalUrl),
                "\"prior\"",
                "Wed, 21 Oct 2015 07:28:00 GMT");

        FeedAcquisition result = new BoundedFeedAcquirer(transport)
                .acquire(original, validators);

        assertTrue(result.isNotModified());
        assertThrows(IllegalStateException.class, result::getDocument);
        assertEquals("\"updated\"", result.getValidators().getEtag());
        assertEquals(
                "Wed, 21 Oct 2015 07:28:00 GMT",
                result.getValidators().getLastModified());
        assertFalse(transport.requestHeaders.get(0).isConditional());
        assertEquals("\"prior\"", transport.requestHeaders.get(1).getIfNoneMatch());
    }

    @Test
    public void clearsConditionalHeadersWhenRedirectLeavesValidatorResource()
            throws Exception {
        String original = "https://start.example/feed";
        String priorFinal = "https://prior.example/feed";
        String newFinal = "https://new.example/feed";
        FakeTransport transport = new FakeTransport();
        FakeExchange first = response(302, new byte[0]);
        first.headers.put("Location", priorFinal);
        transport.add(original, first);
        FakeExchange second = response(302, new byte[0]);
        second.headers.put("Location", newFinal);
        transport.add(priorFinal, second);
        FakeExchange success = response(200, RSS);
        success.headers.put("ETag", "\"new\"");
        transport.add(newFinal, success);

        FeedAcquisition result = new BoundedFeedAcquirer(transport).acquire(
                original,
                FeedValidators.of(
                        CanonicalHttpsUrl.parse(priorFinal),
                        "\"secret-validator\"",
                        ""));

        assertFalse(result.isNotModified());
        assertFalse(transport.requestHeaders.get(0).isConditional());
        assertEquals(
                "\"secret-validator\"",
                transport.requestHeaders.get(1).getIfNoneMatch());
        assertFalse(transport.requestHeaders.get(2).isConditional());
        assertEquals(newFinal, result.getValidators().getResourceUrl().toString());
        assertEquals("\"new\"", result.getValidators().getEtag());
    }

    @Test
    public void unsolicited304FailsAndUnsafeResponseValidatorsAreDiscarded()
            throws Exception {
        FakeTransport unsolicited = new FakeTransport();
        unsolicited.add("https://feeds.example/feed", response(304, new byte[0]));
        assertReason(unsolicited, FeedAcquisitionException.Reason.HTTP_STATUS);

        FakeTransport unsafe = new FakeTransport();
        FakeExchange success = response(200, RSS);
        success.headers.put("ETag", "not-quoted\r\nInjected: value");
        success.headers.put("Last-Modified", "x".repeat(
                SecurityLimits.MAX_LAST_MODIFIED_CHARS + 1));
        unsafe.add("https://feeds.example/feed", success);

        FeedAcquisition result = new BoundedFeedAcquirer(unsafe)
                .acquire("https://feeds.example/feed");

        assertTrue(result.getValidators().isEmpty());
    }

    private static void assertReason(
            FakeTransport transport,
            FeedAcquisitionException.Reason reason) {
        FeedAcquisitionException exception = assertThrows(
                FeedAcquisitionException.class,
                () -> new BoundedFeedAcquirer(transport)
                        .acquire("https://feeds.example/feed"));
        assertEquals(reason, exception.getReason());
    }

    private static void assertNetworkReason(
            FakeTransport transport,
            String url,
            NetworkPolicyException.Reason reason) {
        NetworkPolicyException exception = assertThrows(
                NetworkPolicyException.class,
                () -> new BoundedFeedAcquirer(transport).acquire(url));
        assertEquals(reason, exception.getReason());
    }

    private static FakeExchange response(int status, byte[] body) {
        return new FakeExchange(status, new ByteArrayInputStream(body));
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(input);
        }
        return output.toByteArray();
    }

    private static String randomAscii(int length) {
        Random random = new Random(1234567L);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
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
        private boolean closed;

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
            closed = true;
            body.close();
        }
    }

    private static final class FailOnReadInputStream extends InputStream {
        private boolean readAttempted;

        @Override
        public int read() {
            readAttempted = true;
            throw new AssertionError("body should not be read");
        }
    }
}
