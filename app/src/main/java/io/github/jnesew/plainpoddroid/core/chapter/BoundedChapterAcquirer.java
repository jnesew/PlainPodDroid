package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/** Only invoked by the explicit chapter-loading flow. No cache or automatic enrichment. */
public final class BoundedChapterAcquirer {
    private final ChapterTransport transport;
    private final PodcastChapterParser parser = new PodcastChapterParser();

    public BoundedChapterAcquirer(ChapterTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public PodcastChapterDocument acquire(CanonicalHttpsUrl input)
            throws IOException, ChapterParseException {
        CanonicalHttpsUrl current = Objects.requireNonNull(input, "input");
        Set<String> visited = new HashSet<>();
        visited.add(current.toString());
        for (int redirects = 0; ; redirects++) {
            try (ChapterTransport.Exchange exchange = transport.execute(current)) {
                int status = exchange.getStatusCode();
                if (isRedirect(status)) {
                    if (redirects >= SecurityLimits.MAX_REDIRECTS) {
                        throw failure(ChapterAcquisitionException.Reason.TOO_MANY_REDIRECTS);
                    }
                    current = redirect(current, exchange.getHeader("Location"));
                    if (!visited.add(current.toString())) {
                        throw failure(ChapterAcquisitionException.Reason.REDIRECT_LOOP);
                    }
                    continue;
                }
                if (status != 200) {
                    throw failure(ChapterAcquisitionException.Reason.HTTP_STATUS);
                }
                validateType(exchange.getHeader("Content-Type"));
                validateLength(exchange.getHeader("Content-Length"));
                InputStream body = exchange.getBody();
                if (body == null) {
                    throw failure(ChapterAcquisitionException.Reason.MISSING_BODY);
                }
                try {
                    BodyStreams streams = decode(body, exchange.getHeader("Content-Encoding"));
                    PodcastChapterDocument document = parser.parse(streams.decoded);
                    byte[] tail = new byte[8 * 1024];
                    while (streams.decoded.read(tail) != -1) {
                        // Validate the bounded tail and gzip trailer even if parsing changes.
                    }
                    while (streams.raw.read(tail) != -1) {
                        // Gzip may stop at trailing non-gzip bytes; they still count on the wire.
                    }
                    return document;
                } catch (ZipException | EOFException exception) {
                    throw failure(ChapterAcquisitionException.Reason.INVALID_COMPRESSION);
                }
            }
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static CanonicalHttpsUrl redirect(CanonicalHttpsUrl source, String location)
            throws ChapterAcquisitionException {
        if (location == null || location.isEmpty()
                || location.length() > SecurityLimits.MAX_URL_CHARS) {
            throw failure(ChapterAcquisitionException.Reason.INVALID_REDIRECT);
        }
        try {
            URI destination = source.toUri().resolve(new URI(location));
            return CanonicalHttpsUrl.parse(destination.toString());
        } catch (URISyntaxException | IllegalArgumentException | NetworkPolicyException exception) {
            throw failure(ChapterAcquisitionException.Reason.INVALID_REDIRECT);
        }
    }

    private static void validateType(String header) throws ChapterAcquisitionException {
        if (header == null || header.length() > SecurityLimits.MAX_MIME_TYPE_CHARS) {
            throw failure(ChapterAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE);
        }
        String[] parts = header.split(";", -1);
        if (!parts[0].trim().equalsIgnoreCase("application/json+chapters")
                || parts.length > 2
                || (parts.length == 2 && !parts[1].trim().matches(
                        "(?i)charset\\s*=\\s*(utf-8|\"utf-8\")"))) {
            throw failure(ChapterAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE);
        }
    }

    private static void validateLength(String value) throws ChapterAcquisitionException {
        if (value == null) { return; }
        final long length;
        try {
            if (!value.matches("[0-9]{1,19}")) { throw new NumberFormatException(); }
            length = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw failure(ChapterAcquisitionException.Reason.INVALID_CONTENT_LENGTH);
        }
        if (length > SecurityLimits.MAX_CHAPTER_BYTES) {
            throw failure(ChapterAcquisitionException.Reason.RAW_BODY_LIMIT);
        }
    }

    private static BodyStreams decode(InputStream body, String header) throws IOException {
        String encoding = header == null ? "identity" : header.trim().toLowerCase(Locale.ROOT);
        if (!encoding.equals("identity") && !encoding.equals("gzip")) {
            throw failure(ChapterAcquisitionException.Reason.UNSUPPORTED_ENCODING);
        }
        LimitedInput raw = new LimitedInput(body, null);
        return new BodyStreams(raw, encoding.equals("gzip")
                ? new LimitedInput(new GZIPInputStream(raw), raw) : raw);
    }

    private static final class BodyStreams {
        private final LimitedInput raw;
        private final InputStream decoded;
        private BodyStreams(LimitedInput raw, InputStream decoded) {
            this.raw = raw; this.decoded = decoded;
        }
    }

    private static ChapterAcquisitionException failure(ChapterAcquisitionException.Reason reason) {
        return new ChapterAcquisitionException(reason);
    }

    private static final class LimitedInput extends FilterInputStream {
        private final LimitedInput compressed;
        private long count;

        private LimitedInput(InputStream input, LimitedInput compressed) {
            super(input);
            this.compressed = compressed;
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1) { increment(1); }
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int amount = in.read(buffer, offset, length);
            if (amount > 0) { increment(amount); }
            return amount;
        }

        private void increment(int amount) throws ChapterAcquisitionException {
            count += amount;
            if (count > SecurityLimits.MAX_CHAPTER_BYTES) {
                throw failure(compressed == null ? ChapterAcquisitionException.Reason.RAW_BODY_LIMIT
                        : ChapterAcquisitionException.Reason.EXPANDED_BODY_LIMIT);
            }
            if (compressed != null && count > SecurityLimits.FEED_RATIO_GRACE_BYTES
                    && count > compressed.count * SecurityLimits.MAX_FEED_EXPANSION_RATIO) {
                throw failure(ChapterAcquisitionException.Reason.EXPANSION_RATIO);
            }
        }
    }
}
