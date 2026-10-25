package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;
import java.util.List;

/** Local IDs only; feed and enclosure URLs never enter a scheduled job. */
public interface AutomaticDownloadQueue {
    List<String> listAutomaticDownloadCandidates(int limit) throws IOException;

    boolean isAutomaticDownloadCandidate(String mediaId) throws IOException;

    void clearAutomaticDownloadCandidate(String mediaId) throws IOException;

    void recordAutomaticDownloadFailure(String mediaId) throws IOException;
}
