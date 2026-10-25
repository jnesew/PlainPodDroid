package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;

public final class ApplePodcastResolver {
    private final ApplePodcastLookupTransport transport;

    public ApplePodcastResolver(ApplePodcastLookupTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    public CanonicalHttpsUrl resolve(ApplePodcastLink link) throws IOException {
        // One fixed ID lookup. No search, automatic retry, cross-country fallback or redirects.
        try (FeedTransport.Exchange exchange = transport.execute(link.getLookupUrl())) {
            if (exchange.getStatusCode() != 200) throw new ApplePodcastLookupException(
                    ApplePodcastLookupException.Reason.UNAVAILABLE);
            String encoding = exchange.getHeader("Content-Encoding");
            if (encoding != null && !encoding.trim().isEmpty() && !encoding.trim().equalsIgnoreCase("identity")) {
                throw invalid();
            }
            String type = exchange.getHeader("Content-Type");
            // Apple's endpoint commonly returns text/javascript despite its JSON body.
            String mime = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!mime.equals("application/json") && !mime.equals("text/javascript")
                    && !mime.equals("application/javascript")) {
                throw invalid();
            }
            String length = exchange.getHeader("Content-Length");
            if (length != null) {
                try {
                    if (!length.matches("[0-9]+") || Long.parseLong(length) > SecurityLimits.MAX_APPLE_LOOKUP_BYTES) {
                        throw invalid();
                    }
                } catch (NumberFormatException exception) { throw invalid(); }
            }
            InputStream body = exchange.getBody();
            if (body == null) throw invalid();
            return ApplePodcastLookupParser.parse(body, link.getId());
        } catch (ApplePodcastLookupException exception) { throw exception; }
        catch (IOException exception) {
            throw new ApplePodcastLookupException(ApplePodcastLookupException.Reason.UNAVAILABLE);
        }
    }

    private static ApplePodcastLookupException invalid() {
        return new ApplePodcastLookupException(ApplePodcastLookupException.Reason.INVALID_RESPONSE);
    }
}
