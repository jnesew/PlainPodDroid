package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BoundedChapterAcquirerTest {
    private static final String JSON = "{\"version\":\"1.2.0\",\"chapters\":[{\"startTime\":0,\"title\":\"Intro\"}]}";
    private static final String URL = "https://publisher.example/chapters?token=private";

    @Test public void acceptsIdentityAndUtf8MimeAndCloses() throws Exception {
        Fake response = ok(JSON.getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "Application/JSON+Chapters; charset=\"UTF-8\""));
        assertEquals("Intro", acquire(response).getChapters().get(0).getTitle());
        assertTrue(response.closed);
    }

    @Test public void acceptsGzipAndValidatesTrailer() throws Exception {
        byte[] zipped = gzip(JSON.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, acquire(ok(zipped, Map.of("Content-Encoding", "gzip"))).getChapters().size());
        zipped[zipped.length - 8] ^= 1;
        rejects(ChapterAcquisitionException.Reason.INVALID_COMPRESSION,
                ok(zipped, Map.of("Content-Encoding", "gzip")));
    }

    @Test public void rejectsTruncatedAndInvalidCompression() throws Exception {
        byte[] zipped = gzip(JSON.getBytes(StandardCharsets.UTF_8));
        rejects(ChapterAcquisitionException.Reason.INVALID_COMPRESSION,
                ok(Arrays.copyOf(zipped, zipped.length - 5), Map.of("Content-Encoding", "gzip")));
        rejects(ChapterAcquisitionException.Reason.INVALID_COMPRESSION,
                ok(new byte[] {1,2,3}, Map.of("Content-Encoding", "gzip")));
    }

    @Test public void refusesMissingAndAmbiguousMime() throws Exception {
        for (String mime : List.of("", "application/json", "text/html",
                "application/json+chapters; charset=latin1", "application/json+chapters;",
                "application/json+chapters,application/json+chapters",
                "application/json+chapters; charset=utf-8; other=x")) {
            rejects(ChapterAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE,
                    new Fake(200, Map.of("Content-Type",mime), bytes(JSON)));
        }
        rejects(ChapterAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE,
                new Fake(200,Map.of(),bytes(JSON)));
    }

    @Test public void refusesUnsupportedEncodings() throws Exception {
        for (String encoding : List.of("", "br", "gzip,gzip", "deflate")) {
            rejects(ChapterAcquisitionException.Reason.UNSUPPORTED_ENCODING,
                    ok(bytes(JSON),Map.of("Content-Encoding",encoding)));
        }
    }

    @Test public void validatesDeclaredLengthButAlsoActualBytes() throws Exception {
        for (String length : List.of("", "-1", "1,1", "999999999999999999999999")) {
            rejects(ChapterAcquisitionException.Reason.INVALID_CONTENT_LENGTH,
                    ok(bytes(JSON),Map.of("Content-Length",length)));
        }
        rejects(ChapterAcquisitionException.Reason.RAW_BODY_LIMIT,
                ok(bytes(JSON),Map.of("Content-Length", Long.toString(SecurityLimits.MAX_CHAPTER_BYTES+1))));
        byte[] exact = Arrays.copyOf(bytes(JSON),(int)SecurityLimits.MAX_CHAPTER_BYTES);
        Arrays.fill(exact,bytes(JSON).length,exact.length,(byte)' ');
        assertEquals(1,acquire(ok(exact,Map.of("Content-Length","1"))).getChapters().size());
        byte[] excess=Arrays.copyOf(exact,exact.length+1); excess[excess.length-1]=' ';
        rejects(ChapterAcquisitionException.Reason.RAW_BODY_LIMIT,ok(excess,Map.of()));
    }

    @Test public void wireLimitIncludesTrailingNonGzipBytes() throws Exception {
        byte[] zipped = gzip(bytes(JSON));
        byte[] withTail = Arrays.copyOf(zipped, (int)SecurityLimits.MAX_CHAPTER_BYTES + 1);
        rejects(ChapterAcquisitionException.Reason.RAW_BODY_LIMIT,
                ok(withTail, Map.of("Content-Encoding", "gzip")));
    }

    @Test public void refusesSmallCompressionBombAfterExpandedGrace() throws Exception {
        byte[] bomb = bytes(JSON + " ".repeat(100_000));
        rejects(ChapterAcquisitionException.Reason.EXPANSION_RATIO,
                ok(gzip(bomb),Map.of("Content-Encoding","gzip")));
    }

    @Test public void expandedByteLimitIsIndependent() throws Exception {
        // Random whitespace keeps the compression ratio under 20:1.
        StringBuilder tail = new StringBuilder(JSON);
        java.util.Random random = new java.util.Random(42);
        char[] whitespace = {' ', '\n', '\r', '\t'};
        for(int i=0;i<SecurityLimits.MAX_CHAPTER_BYTES;i++) {
            tail.append(whitespace[random.nextInt(whitespace.length)]);
        }
        rejects(ChapterAcquisitionException.Reason.EXPANDED_BODY_LIMIT,
                ok(gzip(bytes(tail.toString())),Map.of("Content-Encoding","gzip")));
    }

    @Test public void followsRelativeRedirectAndClosesEveryResponse() throws Exception {
        Fake redirect=new Fake(302,Map.of("Location","/next"),null);
        Fake success=ok(bytes(JSON),Map.of());
        QueueTransport transport=new QueueTransport(redirect,success);
        assertEquals(1,new BoundedChapterAcquirer(transport).acquire(CanonicalHttpsUrl.parse(URL))
                .getChapters().size());
        assertEquals("https://publisher.example/next",transport.urls.get(1));
        assertTrue(redirect.closed); assertTrue(success.closed);
    }

    @Test public void rejectsUnsafeRedirectsBeforeRequestingThem() throws Exception {
        for(String location:List.of("", "http://publisher.example/a", "https://127.0.0.1/a",
                "https://user:password@publisher.example/a", "https://publisher.example/a\r\n")) {
            Fake response=new Fake(307,Map.of("Location",location),null);
            QueueTransport transport=new QueueTransport(response);
            ChapterAcquisitionException failure=assertThrows(ChapterAcquisitionException.class,
                    ()->new BoundedChapterAcquirer(transport).acquire(CanonicalHttpsUrl.parse(URL)));
            assertEquals(ChapterAcquisitionException.Reason.INVALID_REDIRECT,failure.getReason());
            assertEquals(1,transport.urls.size()); assertTrue(response.closed);
        }
    }

    @Test public void rejectsLoopsAndSixthRedirect() throws Exception {
        rejects(ChapterAcquisitionException.Reason.REDIRECT_LOOP,
                new Fake(301,Map.of("Location",URL),null));
        List<Fake> responses=new ArrayList<>();
        for(int i=0;i<6;i++) { responses.add(new Fake(308,Map.of("Location","/step"+i),null)); }
        QueueTransport transport=new QueueTransport(responses.toArray(new Fake[0]));
        ChapterAcquisitionException failure=assertThrows(ChapterAcquisitionException.class,
                ()->new BoundedChapterAcquirer(transport).acquire(CanonicalHttpsUrl.parse(URL)));
        assertEquals(ChapterAcquisitionException.Reason.TOO_MANY_REDIRECTS,failure.getReason());
        assertTrue(responses.stream().allMatch(x->x.closed));
    }

    @Test public void rejectsStatusMissingBodyAndSchemaWithoutLeakingUrl() throws Exception {
        rejects(ChapterAcquisitionException.Reason.HTTP_STATUS,new Fake(304,Map.of(),null));
        rejects(ChapterAcquisitionException.Reason.MISSING_BODY,ok(null,Map.of()));
        Fake malformed=ok(bytes("{}"),Map.of());
        assertThrows(ChapterParseException.class,()->acquire(malformed));
        assertTrue(malformed.closed);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(GZIPOutputStream zip=new GZIPOutputStream(out)) { zip.write(bytes); }
        return out.toByteArray();
    }
    private static Fake ok(byte[] body, Map<String,String> overrides) {
        java.util.HashMap<String,String> headers=new java.util.HashMap<>(overrides);
        headers.putIfAbsent("Content-Type","application/json+chapters");
        return new Fake(200,headers,body);
    }
    private static PodcastChapterDocument acquire(Fake response) throws Exception {
        return new BoundedChapterAcquirer(new QueueTransport(response)).acquire(CanonicalHttpsUrl.parse(URL));
    }
    private static void rejects(ChapterAcquisitionException.Reason reason, Fake response) {
        ChapterAcquisitionException exception=assertThrows(ChapterAcquisitionException.class,
                ()->acquire(response));
        assertEquals(reason,exception.getReason()); assertTrue(response.closed);
        assertFalse(exception.getMessage().contains("private")); assertNull(exception.getCause());
    }
    private static final class QueueTransport implements ChapterTransport {
        private final ArrayDeque<Fake> responses=new ArrayDeque<>();
        private final List<String> urls=new ArrayList<>();
        QueueTransport(Fake... responses) { this.responses.addAll(Arrays.asList(responses)); }
        @Override public Exchange execute(CanonicalHttpsUrl url) {
            urls.add(url.toString()); return responses.removeFirst();
        }
    }
    private static final class Fake implements ChapterTransport.Exchange {
        final int status; final Map<String,String> headers; final byte[] body; boolean closed;
        Fake(int status,Map<String,String> headers,byte[] body) {
            this.status=status; this.headers=headers; this.body=body;
        }
        @Override public int getStatusCode() { return status; }
        @Override public String getHeader(String name) { return headers.get(name); }
        @Override public InputStream getBody() { return body==null?null:new ByteArrayInputStream(body); }
        @Override public void close() { closed=true; }
    }
}
