package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedTransport;
import java.io.IOException;

/** A policy-checked, credential-free JSON request; no redirects are followed. */
public interface ApplePodcastLookupTransport {
    FeedTransport.Exchange execute(CanonicalHttpsUrl url) throws IOException;
}
