package io.github.jnesew.plainpoddroid.core.download;

import java.io.Closeable;
import java.io.IOException;

public interface PlaybackDownloadStore extends VerifiedDownloadStore {
    /** Atomically verifies a local source and protects it until the reader closes. */
    Closeable acquireReadLease(String uri) throws IOException;
}
