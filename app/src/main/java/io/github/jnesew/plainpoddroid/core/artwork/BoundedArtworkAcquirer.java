package io.github.jnesew.plainpoddroid.core.artwork;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.ByteArrayOutputStream;
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

/** Retrieves publisher artwork only through a policy transport; no image decoder runs here. */
public final class BoundedArtworkAcquirer {
    private final ArtworkTransport transport;

    public BoundedArtworkAcquirer(ArtworkTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public ArtworkPayload acquire(CanonicalHttpsUrl original) throws IOException {
        CanonicalHttpsUrl current = Objects.requireNonNull(original, "original");
        Set<String> visited = new HashSet<>();
        visited.add(current.toString());
        for (int redirects = 0; ; redirects++) {
            try (ArtworkTransport.Exchange exchange = transport.execute(current)) {
                int status = exchange.getStatusCode();
                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    if (redirects >= SecurityLimits.MAX_REDIRECTS) {
                        throw fail(ArtworkAcquisitionException.Reason.TOO_MANY_REDIRECTS);
                    }
                    current = redirect(current, exchange.getHeader("Location"));
                    if (!visited.add(current.toString())) {
                        throw fail(ArtworkAcquisitionException.Reason.REDIRECT_LOOP);
                    }
                    continue;
                }
                if (status != 200) throw fail(ArtworkAcquisitionException.Reason.HTTP_STATUS);
                ArtworkPayload.Format format = contentType(exchange.getHeader("Content-Type"));
                contentLength(exchange.getHeader("Content-Length"));
                InputStream body = exchange.getBody();
                if (body == null) throw fail(ArtworkAcquisitionException.Reason.MISSING_BODY);
                String encoding = exchange.getHeader("Content-Encoding");
                String normalized = encoding == null ? "identity"
                        : encoding.trim().toLowerCase(Locale.ROOT);
                if (!normalized.equals("identity") && !normalized.equals("gzip")) {
                    throw fail(ArtworkAcquisitionException.Reason.UNSUPPORTED_ENCODING);
                }
                LimitedInput raw = new LimitedInput(body, null);
                try {
                    InputStream decoded = normalized.equals("gzip")
                            ? new LimitedInput(new GZIPInputStream(raw), raw) : raw;
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8 * 1024];
                    int count;
                    while ((count = decoded.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    // Validate any bytes after a gzip member, including trailing junk.
                    while (raw.read(buffer) != -1) { /* bounded drain */ }
                    byte[] bytes = output.toByteArray();
                    if (!hasSignature(format, bytes)) {
                        throw fail(ArtworkAcquisitionException.Reason.INVALID_IMAGE);
                    }
                    return ArtworkPayload.owned(format, bytes);
                } catch (ZipException | EOFException exception) {
                    throw fail(ArtworkAcquisitionException.Reason.INVALID_COMPRESSION);
                }
            }
        }
    }

    private static CanonicalHttpsUrl redirect(CanonicalHttpsUrl source, String location)
            throws ArtworkAcquisitionException {
        if (location == null || location.isEmpty()
                || location.length() > SecurityLimits.MAX_URL_CHARS) {
            throw fail(ArtworkAcquisitionException.Reason.INVALID_REDIRECT);
        }
        try {
            URI destination = source.toUri().resolve(new URI(location));
            return CanonicalHttpsUrl.parse(destination.toString());
        } catch (URISyntaxException | IllegalArgumentException | NetworkPolicyException exception) {
            throw fail(ArtworkAcquisitionException.Reason.INVALID_REDIRECT);
        }
    }

    private static ArtworkPayload.Format contentType(String header)
            throws ArtworkAcquisitionException {
        if (header == null || header.length() > SecurityLimits.MAX_MIME_TYPE_CHARS) {
            throw fail(ArtworkAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE);
        }
        return switch (header.trim().toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ArtworkPayload.Format.JPEG;
            case "image/png" -> ArtworkPayload.Format.PNG;
            case "image/webp" -> ArtworkPayload.Format.WEBP;
            default -> throw fail(ArtworkAcquisitionException.Reason.UNSUPPORTED_CONTENT_TYPE);
        };
    }

    private static void contentLength(String header) throws ArtworkAcquisitionException {
        if (header == null) return;
        try {
            if (!header.matches("[0-9]{1,19}")
                    || Long.parseLong(header) > SecurityLimits.MAX_ARTWORK_BYTES) {
                throw fail(ArtworkAcquisitionException.Reason.INVALID_CONTENT_LENGTH);
            }
        } catch (NumberFormatException exception) {
            throw fail(ArtworkAcquisitionException.Reason.INVALID_CONTENT_LENGTH);
        }
    }

    private static boolean hasSignature(ArtworkPayload.Format format, byte[] data) {
        return switch (format) {
            case JPEG -> data.length >= 4 && (data[0] & 255) == 255
                    && (data[1] & 255) == 216 && (data[2] & 255) == 255;
            case PNG -> data.length >= 8 && (data[0] & 255) == 137
                    && data[1] == 80 && data[2] == 78 && data[3] == 71
                    && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10;
            case WEBP -> data.length >= 21 && ascii(data, 0, "RIFF")
                    && ascii(data, 8, "WEBP")
                    && (ascii(data, 12, "VP8 ") || ascii(data, 12, "VP8L")
                        || (ascii(data, 12, "VP8X") && (data[20] & 2) == 0));
        };
    }

    private static boolean ascii(byte[] data, int offset, String value) {
        if (data.length < offset + value.length()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (data[offset + i] != value.charAt(i)) return false;
        }
        return true;
    }

    private static ArtworkAcquisitionException fail(ArtworkAcquisitionException.Reason reason) {
        return new ArtworkAcquisitionException(reason);
    }

    private static final class LimitedInput extends FilterInputStream {
        private final LimitedInput raw;
        private long count;

        LimitedInput(InputStream input, LimitedInput raw) {
            super(input);
            this.raw = raw;
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1) increment(1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int amount = in.read(bytes, offset, length);
            if (amount > 0) increment(amount);
            return amount;
        }

        private void increment(int amount) throws ArtworkAcquisitionException {
            count += amount;
            if (count > SecurityLimits.MAX_ARTWORK_BYTES) {
                throw fail(raw == null ? ArtworkAcquisitionException.Reason.RAW_BODY_LIMIT
                        : ArtworkAcquisitionException.Reason.EXPANDED_BODY_LIMIT);
            }
            if (raw != null && count > SecurityLimits.FEED_RATIO_GRACE_BYTES
                    && count > raw.count * SecurityLimits.MAX_FEED_EXPANSION_RATIO) {
                throw fail(ArtworkAcquisitionException.Reason.EXPANSION_RATIO);
            }
        }
    }
}
