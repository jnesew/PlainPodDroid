package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Durable requests contain local identities and typed state, never network targets. */
public interface ManualDownloadQueue {
    enum State { WAITING, ACTIVE, FAILED }
    enum Enqueued { ADDED, EXISTING, COMPLETE, UNAVAILABLE, FULL }
    record Request(long id, String mediaId, long attempt) {
        public Request {
            requireMediaId(mediaId);
            if (id < 1 || attempt < 0) throw new IllegalArgumentException("Invalid download request.");
        }
    }
    record Entry(Request request, State state, EpisodeDownloadException.Reason failure,
            String title, String podcastTitle) {
        public Entry {
            Objects.requireNonNull(request); Objects.requireNonNull(state);
            Objects.requireNonNull(title); Objects.requireNonNull(podcastTitle);
            if ((state == State.FAILED) != (failure != null)) throw new IllegalArgumentException("Invalid download state.");
        }
    }
    static void requireMediaId(String id) {
        if (id == null || !id.matches("episode-[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid episode selection.");
    }
    Enqueued enqueueManualDownload(String mediaId) throws IOException;
    List<Entry> listManualDownloads() throws IOException;
    void recoverInterruptedManualDownloads() throws IOException;
    Request claimManualDownload() throws IOException;
    boolean canRunManualDownload(Request request) throws IOException;
    void finishManualDownload(Request request, EpisodeDownloadException.Reason failure) throws IOException;
    void deferManualDownload(Request request) throws IOException;
    void cancelManualDownload(String mediaId) throws IOException;
    void clearCompletedManualDownloads() throws IOException;
    void clearPendingManualDownloads() throws IOException;
    void failPendingManualDownloads(EpisodeDownloadException.Reason reason) throws IOException;
    boolean hasWaitingManualDownloads() throws IOException;
}
