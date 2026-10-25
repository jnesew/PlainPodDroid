package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;

/** Rechecks user-owned retention state while excluding concurrent catalog writes. */
public interface CompletedDownloadRetention {
    interface FileRemover {
        void delete(String mediaId) throws IOException;
    }

    boolean deleteCompletedDownloadIfEligible(String mediaId, FileRemover remover)
            throws IOException;
}
