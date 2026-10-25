package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ApplePodcastResolverTest {
    static final String APPLE = "https://podcasts.apple.com/fi/podcast/example/id123456789";
    static final String FEED = "https://feeds.example/show";
    static final String JSON = "{\"resultCount\":1,\"results\":[{\"kind\":\"podcast\","
            + "\"collectionId\":123456789,\"trackId\":123456789,\"feedUrl\":\"" + FEED + "\"}]}";

    @Test public void recognizesShowAndEpisodeLinksAndDropsTracking() throws Exception {
        ApplePodcastLink link = ApplePodcastLink.from(CanonicalHttpsUrl.parse(APPLE + "?i=987&at=private"));
        assertTrue(link.isEpisode());
        assertEquals("https://itunes.apple.com/lookup?id=123456789&entity=podcast&country=fi",
                link.getLookupUrl().toString());
        assertFalse(ApplePodcastLink.from(CanonicalHttpsUrl.parse(APPLE)).isEpisode());
        assertEquals("https://itunes.apple.com/lookup?id=123456789&entity=podcast&country=us",
                ApplePodcastLink.from(CanonicalHttpsUrl.parse(
                        "https://podcasts.apple.com/podcast/id123456789")).getLookupUrl().toString());
        assertEquals(link.getLookupUrl(), ApplePodcastLink.from(CanonicalHttpsUrl.parse(
                "https://PODCASTS.APPLE.COM/FI/podcast/id123456789/")).getLookupUrl());
        assertNull(ApplePodcastLink.from(CanonicalHttpsUrl.parse("https://podcasts.apple.com.example/fi/podcast/id123456789")));
        for (String path : new String[]{"/fi/podcast/example", "/fi/podcast/id0", "/fi/podcast/id123/extra", "/fi/podcast/id123?i=bad", "/fi/podcast/id123?i=1&i=2"}) {
            assertEquals(ApplePodcastLookupException.Reason.INVALID_LINK, assertThrows(
                    ApplePodcastLookupException.class, () -> ApplePodcastLink.from(
                            CanonicalHttpsUrl.parse("https://podcasts.apple.com" + path))).getReason());
        }
    }

    @Test public void resolvesOnlyTheMatchingPodcastAndClosesResponse() throws Exception {
        Exchange response = new Exchange(JSON);
        String[] requested = {null};
        CanonicalHttpsUrl result = new ApplePodcastResolver(url -> {
            requested[0] = url.toString(); return response;
        }).resolve(ApplePodcastLink.from(CanonicalHttpsUrl.parse(APPLE)));
        assertEquals(FEED, result.toString());
        assertEquals("https://itunes.apple.com/lookup?id=123456789&entity=podcast&country=fi", requested[0]);
        assertTrue(response.closed);
        assertEquals(FEED, parse(JSON.replace("https://feeds.example/show", "https:\\/\\/feeds.example\\/show")).toString());
    }

    @Test public void missingFeedAndZeroResultsHaveDistinctFailure() throws Exception {
        for (String json : new String[]{"{\"results\":[],\"resultCount\":0}",
                JSON.replace(",\"feedUrl\":\"" + FEED + "\"", ""),
                JSON.replace("\"" + FEED + "\"", "null"), JSON.replace(FEED, "")}) {
            assertEquals(ApplePodcastLookupException.Reason.NO_PUBLIC_FEED,
                    assertThrows(ApplePodcastLookupException.class, () -> parse(json)).getReason());
        }
    }

    @Test public void rejectsMismatchedAmbiguousAndUnsafeResults() {
        for (String json : new String[]{JSON.replace("\"collectionId\":123456789", "\"collectionId\":987"),
                JSON.replace("\"trackId\":123456789", "\"trackId\":987"),
                JSON.replace("\"podcast\"", "\"song\""),
                JSON.replace("\"resultCount\":1", "\"resultCount\":2"),
                JSON.replace("\"collectionId\":123456789", "\"collectionId\":1.23456789e8"),
                JSON.replace(FEED, "http://feeds.example/show"),
                JSON.replace(FEED, "https://127.0.0.1/show"),
                JSON.replace(FEED, "https://user:password@feeds.example/show"),
                JSON.replace(FEED, "https://feeds.example:8443/show"),
                JSON.replace("\"feedUrl\":", "\"feedUrl\":null,\"feedUrl\":"),
                JSON.replace("\"results\":", "\"results\":[],\"results\":"),
                JSON.replace("\"resultCount\":1", "\"resultCount\":01"),
                JSON + "{}", JSON + "false"}) {
            assertEquals(ApplePodcastLookupException.Reason.INVALID_RESPONSE,
                    assertThrows(ApplePodcastLookupException.class, () -> parse(json)).getReason());
        }
    }

    @Test public void strictJsonAndUnicodeHaveBoundedDepthTokensAndFields() {
        for (String json : new String[]{JSON.substring(0, JSON.length() - 1),
                JSON.replace("}]}", "},]}"), JSON.replace(FEED, "\\uD800"),
                JSON.replace(FEED, "\\uDC00"), JSON.replace(FEED, "\\x42"),
                JSON.replace(FEED, "x".repeat(SecurityLimits.MAX_URL_CHARS + 1)),
                "{\"extra\":" + "[".repeat(17) + "0" + "]".repeat(17) + "," + JSON.substring(1),
                "{\"extra\":[" + "null,".repeat(5000) + "null]," + JSON.substring(1)}) {
            assertThrows(ApplePodcastLookupException.class, () -> parse(json));
        }
        assertThrows(ApplePodcastLookupException.class, () -> ApplePodcastLookupParser.parse(
                new ByteArrayInputStream(new byte[]{(byte) 0xc0, (byte) 0xaf}), "123456789"));
    }

    @Test public void rejectsBodyAndHeaderLimitsWithoutReadingOversizedDeclaredBody() throws Exception {
        assertThrows(ApplePodcastLookupException.class,
                () -> parse(" ".repeat(SecurityLimits.MAX_APPLE_LOOKUP_BYTES + 1)));
        Exchange exchange = new Exchange(JSON);
        exchange.headers.put("Content-Length", Long.toString(SecurityLimits.MAX_APPLE_LOOKUP_BYTES + 1L));
        exchange.body = new InputStream() {
            @Override public int read() { throw new AssertionError("Should not read body"); }
        };
        assertThrows(ApplePodcastLookupException.class,
                () -> new ApplePodcastResolver(url -> exchange).resolve(link()));
        assertTrue(exchange.closed);
    }

    @Test public void refusesRedirectEncodingAndUnexpectedMimeAndDoesNotRetry() throws Exception {
        for (String[] header : new String[][]{{"Content-Encoding", "gzip"}, {"Content-Encoding", "identity,gzip"},
                {"Content-Type", "text/html"}, {"Content-Length", "-1"}, {"Content-Length", "999999999999999999999999999"}}) {
            Exchange exchange = new Exchange(JSON); exchange.headers.put(header[0], header[1]);
            assertThrows(ApplePodcastLookupException.class,
                    () -> new ApplePodcastResolver(url -> exchange).resolve(link()));
            assertTrue(exchange.closed);
        }
        for (int status : new int[]{302, 401, 404, 429, 503}) {
            Exchange exchange = new Exchange(JSON); exchange.status = status;
            int[] requests = {0};
            assertEquals(ApplePodcastLookupException.Reason.UNAVAILABLE, assertThrows(
                    ApplePodcastLookupException.class, () -> new ApplePodcastResolver(url -> {
                        requests[0]++; return exchange;
                    }).resolve(link())).getReason());
            assertEquals(1, requests[0]); assertTrue(exchange.closed);
        }
    }

    @Test public void networkFailuresExposeNoProviderDetails() {
        ApplePodcastLookupException failure = assertThrows(ApplePodcastLookupException.class,
                () -> new ApplePodcastResolver(url -> { throw new IOException("private?token=secret"); }).resolve(link()));
        assertFalse(failure.getMessage().contains("secret")); assertNull(failure.getCause());
    }

    private static CanonicalHttpsUrl parse(String json) throws IOException {
        return ApplePodcastLookupParser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "123456789");
    }
    private static ApplePodcastLink link() throws IOException {
        return ApplePodcastLink.from(CanonicalHttpsUrl.parse(APPLE));
    }
    static final class Exchange implements FeedTransport.Exchange {
        final Map<String, String> headers = new HashMap<>();
        InputStream body;
        int status = 200;
        boolean closed;
        Exchange(String body) {
            this.body = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            headers.put("Content-Type", "text/javascript; charset=utf-8");
        }
        @Override public int getStatusCode() { return status; }
        @Override public String getHeader(String name) { return headers.get(name); }
        @Override public InputStream getBody() { return body; }
        @Override public void close() { closed = true; }
    }
}
