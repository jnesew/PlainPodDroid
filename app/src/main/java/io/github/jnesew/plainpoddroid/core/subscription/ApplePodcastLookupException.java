package io.github.jnesew.plainpoddroid.core.subscription;

import java.io.IOException;

/** Typed, source-free failures: never retain provider response data or URLs. */
public final class ApplePodcastLookupException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason { INVALID_LINK, UNAVAILABLE, NO_PUBLIC_FEED, INVALID_RESPONSE, LOGIN_UNSUPPORTED }
    private final Reason reason;

    public ApplePodcastLookupException(Reason reason) {
        super("Apple Podcasts link could not be resolved.");
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
