package io.github.jnesew.plainpoddroid.core.auth;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.IOException;

/** Resolves the owner using the original resource, never a redirect destination. */
public interface FeedLoginProvider {
    BasicFeedLogin findFeedLogin(CanonicalHttpsUrl original) throws IOException;
    BasicFeedLogin findAudioLogin(CanonicalHttpsUrl original) throws IOException;
    void rejectLogin(CanonicalHttpsUrl original, boolean audio, BasicFeedLogin attempted) throws IOException;
    /** A final 401 with a bounded, supported Basic challenge; never inferred from a bare 403. */
    default void noteBasicChallenge(CanonicalHttpsUrl original, boolean audio) { }
    FeedLoginProvider NONE = new FeedLoginProvider() {
        public BasicFeedLogin findFeedLogin(CanonicalHttpsUrl original) { return null; }
        public BasicFeedLogin findAudioLogin(CanonicalHttpsUrl original) { return null; }
        public void rejectLogin(CanonicalHttpsUrl original, boolean audio, BasicFeedLogin attempted) { }
    };
}
