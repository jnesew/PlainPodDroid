package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.Objects;

public final class FeedValidators {
    private static final FeedValidators NONE = new FeedValidators(null, "", "");

    private final CanonicalHttpsUrl resourceUrl;
    private final String etag;
    private final String lastModified;

    public static FeedValidators none() {
        return NONE;
    }

    public static FeedValidators of(
            CanonicalHttpsUrl resourceUrl,
            String etag,
            String lastModified) {
        Objects.requireNonNull(resourceUrl, "resourceUrl");
        Objects.requireNonNull(etag, "etag");
        Objects.requireNonNull(lastModified, "lastModified");
        if (!etag.isEmpty() && !isSafeEtag(etag)) {
            throw new IllegalArgumentException("Invalid stored ETag.");
        }
        if (!lastModified.isEmpty() && !isSafeLastModified(lastModified)) {
            throw new IllegalArgumentException("Invalid stored Last-Modified value.");
        }
        if (etag.isEmpty() && lastModified.isEmpty()) {
            return NONE;
        }
        return new FeedValidators(resourceUrl, etag, lastModified);
    }

    static FeedValidators fromModifiedResponse(
            CanonicalHttpsUrl resourceUrl,
            String etag,
            String lastModified) {
        String acceptedEtag = isSafeEtag(etag) ? etag : "";
        String acceptedLastModified = isSafeLastModified(lastModified) ? lastModified : "";
        return of(resourceUrl, acceptedEtag, acceptedLastModified);
    }

    FeedValidators mergedFromNotModifiedResponse(
            CanonicalHttpsUrl resourceUrl,
            String etag,
            String lastModified) {
        if (!appliesTo(resourceUrl)) {
            throw new IllegalArgumentException("Validator resource does not match.");
        }
        String acceptedEtag = isSafeEtag(etag) ? etag : this.etag;
        String acceptedLastModified = isSafeLastModified(lastModified)
                ? lastModified
                : this.lastModified;
        return of(resourceUrl, acceptedEtag, acceptedLastModified);
    }

    public FeedRequestHeaders headersFor(CanonicalHttpsUrl url) {
        Objects.requireNonNull(url, "url");
        if (!appliesTo(url)) {
            return FeedRequestHeaders.none();
        }
        return FeedRequestHeaders.of(etag, lastModified);
    }

    public CanonicalHttpsUrl getResourceUrl() {
        return resourceUrl;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public boolean isEmpty() {
        return resourceUrl == null;
    }

    private boolean appliesTo(CanonicalHttpsUrl url) {
        return resourceUrl != null && resourceUrl.equals(url);
    }

    private static boolean isSafeEtag(String value) {
        if (value == null || value.isEmpty()
                || value.length() > SecurityLimits.MAX_ETAG_CHARS) {
            return false;
        }
        int quoteIndex = value.startsWith("W/\"") ? 2 : value.startsWith("\"") ? 0 : -1;
        if (quoteIndex < 0 || value.length() < quoteIndex + 2
                || value.charAt(value.length() - 1) != '\"') {
            return false;
        }
        for (int index = quoteIndex + 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e || character == '\"') {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeLastModified(String value) {
        if (value == null || value.isEmpty()
                || value.length() > SecurityLimits.MAX_LAST_MODIFIED_CHARS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private FeedValidators(
            CanonicalHttpsUrl resourceUrl,
            String etag,
            String lastModified) {
        this.resourceUrl = resourceUrl;
        this.etag = etag;
        this.lastModified = lastModified;
    }
}
