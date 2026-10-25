package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.feed.FeedDocument;
import io.github.jnesew.plainpoddroid.core.feed.FeedParseException;
import io.github.jnesew.plainpoddroid.core.feed.FeedParser;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;



public final class BoundedFeedAcquirer {
    private final FeedTransport transport;
    private final FeedParser parser;

    public BoundedFeedAcquirer(FeedTransport transport) {
        this(transport, new FeedParser());
    }

    BoundedFeedAcquirer(FeedTransport transport, FeedParser parser) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public FeedAcquisition acquire(String input)
            throws IOException, FeedParseException {
        return acquire(input, FeedValidators.none());
    }

    public FeedAcquisition acquire(String input, FeedValidators validators)
            throws IOException, FeedParseException {
        return acquire(input, validators, null);
    }

    public FeedAcquisition acquire(String input, FeedValidators validators,
            BasicFeedLogin login)
            throws IOException, FeedParseException {
        Objects.requireNonNull(validators, "validators");
        CanonicalHttpsUrl original = CanonicalHttpsUrl.parse(input);
        if (login != null && !login.belongsTo(original)) throw new FeedLoginException();
        CanonicalHttpsUrl current = original;
        Set<String> visited = new HashSet<>();
        visited.add(current.toString());
        List<String> origins = new ArrayList<>();
        origins.add(current.getOriginForDisplay());

        for (int redirectCount = 0; ; redirectCount++) {
            FeedRequestHeaders requestHeaders = validators.headersFor(current).withLoginContext(original, login);
            try (FeedTransport.Exchange exchange = transport.execute(current, requestHeaders)) {
                int status = exchange.getStatusCode();
                if (isRedirect(status)) {
                    if (redirectCount >= SecurityLimits.MAX_REDIRECTS) {
                        throw failure(
                                FeedAcquisitionException.Reason.TOO_MANY_REDIRECTS,
                                "Feed exceeded the redirect limit.");
                    }
                    CanonicalHttpsUrl destination = redirectDestination(
                            current,
                            exchange.getHeader("Location"));
                    if (!visited.add(destination.toString())) {
                        throw failure(
                                FeedAcquisitionException.Reason.REDIRECT_LOOP,
                                "Feed redirect loop was rejected.");
                    }
                    current = destination;
                    origins.add(current.getOriginForDisplay());
                    continue;
                }
                if (status == 304 && requestHeaders.isConditional()) {
                    FeedValidators updated = validators.mergedFromNotModifiedResponse(
                            current,
                            exchange.getHeader("ETag"),
                            exchange.getHeader("Last-Modified"));
                    return new FeedAcquisition(
                            original,
                            current,
                            origins,
                            null,
                            updated,
                            true);
                }
                if (status == 401 || status == 403) throw failure(status == 401
                        ? FeedAcquisitionException.Reason.LOGIN_REQUIRED : FeedAcquisitionException.Reason.ACCESS_DENIED,
                        "Check the podcast login or private feed link.");
                if (status != 200) {
                    throw failure(
                            FeedAcquisitionException.Reason.HTTP_STATUS,
                            "Feed server returned HTTP status " + status + ".");
                }
                validateContentType(exchange.getHeader("Content-Type"));
                validateContentLength(exchange.getHeader("Content-Length"));
                InputStream body = exchange.getBody();
                if (body == null) {
                    throw failure(
                            FeedAcquisitionException.Reason.MISSING_BODY,
                            "Feed response had no body.");
                }
                InputStream decoded = decodedBody(body, exchange.getHeader("Content-Encoding"));
                final FeedDocument document;
                try {
                    document = parser.parse(decoded);
                    drain(decoded);
                } catch (ZipException | EOFException exception) {
                    throw new FeedAcquisitionException(
                            FeedAcquisitionException.Reason.INVALID_COMPRESSION,
                            "Feed compression was invalid.",
                            exception);
                }
                FeedValidators updated = FeedValidators.fromModifiedResponse(
                        current,
                        exchange.getHeader("ETag"),
                        exchange.getHeader("Last-Modified"));
                return new FeedAcquisition(
                        original,
                        current,
                        origins,
                        document,
                        updated,
                        false);
            }
        }
    }

    private static CanonicalHttpsUrl redirectDestination(
            CanonicalHttpsUrl source,
            String location) throws FeedAcquisitionException, NetworkPolicyException {
        if (location == null || location.isEmpty()
                || location.length() > SecurityLimits.MAX_URL_CHARS) {
            throw failure(
                    FeedAcquisitionException.Reason.INVALID_REDIRECT,
                    "Feed redirect location was invalid.");
        }
        final URI resolved;
        try {
            resolved = source.toUri().resolve(new URI(location));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new FeedAcquisitionException(
                    FeedAcquisitionException.Reason.INVALID_REDIRECT,
                    "Feed redirect location was invalid.",
                    exception);
        }
        return CanonicalHttpsUrl.parse(resolved.toString());
    }

    private static InputStream decodedBody(InputStream body, String contentEncoding)
            throws IOException {
        String encoding = contentEncoding == null
                ? "identity"
                : contentEncoding.trim().toLowerCase(Locale.ROOT);
        if (encoding.isEmpty()) {
            encoding = "identity";
        }
        if (encoding.indexOf(',') >= 0
                || !("identity".equals(encoding) || "gzip".equals(encoding))) {
            throw failure(
                    FeedAcquisitionException.Reason.UNSUPPORTED_ENCODING,
                    "Feed response encoding is unsupported.");
        }

        CountingLimitInputStream raw = new CountingLimitInputStream(
                body,
                SecurityLimits.MAX_FEED_COMPRESSED_BYTES,
                FeedAcquisitionException.Reason.RAW_BODY_LIMIT,
                "Feed response exceeded the compressed-byte limit.");
        InputStream expanded;
        if ("gzip".equals(encoding)) {
            try {
                expanded = new GZIPInputStream(raw);
            } catch (ZipException | EOFException exception) {
                throw new FeedAcquisitionException(
                        FeedAcquisitionException.Reason.INVALID_COMPRESSION,
                        "Feed compression was invalid.",
                        exception);
            }
        } else {
            expanded = raw;
        }
        return new ExpandedLimitInputStream(expanded, raw, "gzip".equals(encoding));
    }

    private static void validateContentLength(String contentLength)
            throws FeedAcquisitionException {
        if (contentLength == null || contentLength.isEmpty()) {
            return;
        }
        final long value;
        try {
            if (!contentLength.matches("[0-9]+")) {
                throw new NumberFormatException("not decimal");
            }
            value = Long.parseLong(contentLength);
        } catch (NumberFormatException exception) {
            throw new FeedAcquisitionException(
                    FeedAcquisitionException.Reason.INVALID_CONTENT_LENGTH,
                    "Feed Content-Length was invalid.",
                    exception);
        }
        if (value > SecurityLimits.MAX_FEED_COMPRESSED_BYTES) {
            throw failure(
                    FeedAcquisitionException.Reason.RAW_BODY_LIMIT,
                    "Feed response exceeded the compressed-byte limit.");
        }
    }

    private static void validateContentType(String contentType)
            throws FeedAcquisitionException {
        if (contentType == null || contentType.trim().isEmpty()) {
            return;
        }
        String value = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/rss+xml".equals(value)
                && !"application/atom+xml".equals(value)
                && !"application/xml".equals(value)
                && !"text/xml".equals(value)) {
            throw failure(
                    FeedAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE,
                    "Feed response type is unsupported.");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        while (input.read(buffer) != -1) {
            // Consume the bounded tail so parser early-exit cannot bypass response limits.
        }
    }

    private static FeedAcquisitionException failure(
            FeedAcquisitionException.Reason reason,
            String message) {
        return new FeedAcquisitionException(reason, message);
    }

    private static final class CountingLimitInputStream extends FilterInputStream {
        private final long limit;
        private final FeedAcquisitionException.Reason reason;
        private final String message;
        private long count;

        private CountingLimitInputStream(
                InputStream input,
                long limit,
                FeedAcquisitionException.Reason reason,
                String message) {
            super(input);
            this.limit = limit;
            this.reason = reason;
            this.message = message;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                checkedIncrement(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int countRead = super.read(buffer, offset, length);
            if (countRead > 0) {
                checkedIncrement(countRead);
            }
            return countRead;
        }

        private void checkedIncrement(int amount) throws FeedAcquisitionException {
            count += amount;
            if (count > limit) {
                throw failure(reason, message);
            }
        }

        private long getCount() {
            return count;
        }
    }

    private static final class ExpandedLimitInputStream extends FilterInputStream {
        private final CountingLimitInputStream raw;
        private final boolean ratioApplies;
        private long expanded;

        private ExpandedLimitInputStream(
                InputStream input,
                CountingLimitInputStream raw,
                boolean ratioApplies) {
            super(input);
            this.raw = raw;
            this.ratioApplies = ratioApplies;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                checkedIncrement(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int countRead = super.read(buffer, offset, length);
            if (countRead > 0) {
                checkedIncrement(countRead);
            }
            return countRead;
        }

        private void checkedIncrement(int amount) throws FeedAcquisitionException {
            expanded += amount;
            if (expanded > SecurityLimits.MAX_FEED_EXPANDED_BYTES) {
                throw failure(
                        FeedAcquisitionException.Reason.EXPANDED_BODY_LIMIT,
                        "Feed response exceeded the expanded-byte limit.");
            }
            long compressed = raw.getCount();
            if (ratioApplies
                    && compressed > SecurityLimits.FEED_RATIO_GRACE_BYTES
                    && expanded > compressed * SecurityLimits.MAX_FEED_EXPANSION_RATIO) {
                throw failure(
                        FeedAcquisitionException.Reason.EXPANSION_RATIO,
                        "Feed response exceeded the expansion-ratio limit.");
            }
        }
    }
}
