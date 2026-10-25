package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One serial lane; request/attempt ownership prevents late completions changing a retry. */
public final class ManualDownloadRunner {
    private final ManualDownloadQueue queue;
    private final EpisodeDownloadActions downloads;
    private final BooleanSupplier offline;
    private final Runnable changed;
    public ManualDownloadRunner(ManualDownloadQueue queue, EpisodeDownloadActions downloads,
            BooleanSupplier offline, Runnable changed) {
        this.queue=Objects.requireNonNull(queue);this.downloads=Objects.requireNonNull(downloads);
        this.offline=Objects.requireNonNull(offline);this.changed=Objects.requireNonNull(changed);
    }
    /** Interrupted work waits for a later job; ordinary failures require explicit retry. */
    public synchronized boolean run(BooleanSupplier stopped) throws IOException {
        Objects.requireNonNull(stopped);
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) return true;
        queue.recoverInterruptedManualDownloads(); notifyChanged();
        for (int i=0;i<SecurityLimits.MAX_PENDING_MANUAL_DOWNLOADS;i++) {
            if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) return true;
            if (offline.getAsBoolean()) {queue.clearPendingManualDownloads();notifyChanged();return false;}
            var request=queue.claimManualDownload();
            if (request==null) return false;
            notifyChanged();
            Eligibility check=new Eligibility(request,stopped);
            EpisodeDownloadException.Reason failure=null;
            try { downloads.download(request.mediaId(),check); }
            catch (EpisodeDownloadException e) {
                if (e.getReason()!=EpisodeDownloadException.Reason.ALREADY_DOWNLOADED) failure=e.getReason();
            } catch (RuntimeException e) { failure=EpisodeDownloadException.Reason.STORAGE_FAILED; }
            if (check.storageFailure!=null) throw check.storageFailure;
            if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                queue.deferManualDownload(request);notifyChanged();return true;
            }
            if (offline.getAsBoolean() || failure==EpisodeDownloadException.Reason.OFFLINE_MODE) {
                queue.clearPendingManualDownloads();notifyChanged();return false;
            }
            if (failure==EpisodeDownloadException.Reason.CANCELLED)
                failure=EpisodeDownloadException.Reason.TARGET_CHANGED;
            queue.finishManualDownload(request,failure);notifyChanged();
        }
        return queue.hasWaitingManualDownloads();
    }
    private void notifyChanged() { try { changed.run(); } catch (RuntimeException ignored) { } }
    private final class Eligibility implements BooleanSupplier {
        private final ManualDownloadQueue.Request request;
        private final BooleanSupplier stopped;
        private IOException storageFailure;
        Eligibility(ManualDownloadQueue.Request request,BooleanSupplier stopped) {this.request=request;this.stopped=stopped;}
        @Override public boolean getAsBoolean() {
            if(stopped.getAsBoolean()||Thread.currentThread().isInterrupted()||offline.getAsBoolean())return true;
            try{return !queue.canRunManualDownload(request);}
            catch(IOException e){storageFailure=e;return true;}
        }
    }
}
