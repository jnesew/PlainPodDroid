package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;

public interface VerifiedDownloadStore {
    DownloadLookup lookup(String mediaId) throws IOException;

    boolean isVerifiedCompleteUri(String uri) throws IOException;
}
