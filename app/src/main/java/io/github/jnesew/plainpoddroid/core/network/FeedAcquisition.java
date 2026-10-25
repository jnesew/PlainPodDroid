package io.github.jnesew.plainpoddroid.core.network;

import io.github.jnesew.plainpoddroid.core.feed.FeedDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FeedAcquisition {
    private final CanonicalHttpsUrl originalUrl;
    private final CanonicalHttpsUrl finalUrl;
    private final List<String> redirectOriginsForDisplay;
    private final FeedDocument document;
    private final FeedValidators validators;
    private final boolean notModified;

    FeedAcquisition(
            CanonicalHttpsUrl originalUrl,
            CanonicalHttpsUrl finalUrl,
            List<String> redirectOriginsForDisplay,
            FeedDocument document,
            FeedValidators validators,
            boolean notModified) {
        this.originalUrl = Objects.requireNonNull(originalUrl, "originalUrl");
        this.finalUrl = Objects.requireNonNull(finalUrl, "finalUrl");
        this.redirectOriginsForDisplay = Collections.unmodifiableList(
                new ArrayList<>(redirectOriginsForDisplay));
        this.document = document;
        this.validators = Objects.requireNonNull(validators, "validators");
        this.notModified = notModified;
        if (notModified == (document != null)) {
            throw new IllegalArgumentException("Acquisition result is inconsistent.");
        }
    }

    public CanonicalHttpsUrl getOriginalUrl() {
        return originalUrl;
    }

    public CanonicalHttpsUrl getFinalUrl() {
        return finalUrl;
    }

    public List<String> getRedirectOriginsForDisplay() {
        return redirectOriginsForDisplay;
    }

    public FeedDocument getDocument() {
        if (document == null) {
            throw new IllegalStateException("Unmodified acquisition has no feed document.");
        }
        return document;
    }

    public FeedValidators getValidators() {
        return validators;
    }

    public boolean isNotModified() {
        return notModified;
    }
}
