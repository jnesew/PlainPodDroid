package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Processes a small, durable batch using the same guarded path as a manual download. */
public final class AutomaticDownloadRunner {
    private final AutomaticDownloadQueue queue;
    private final EpisodeDownloadActions downloads;

    public AutomaticDownloadRunner(AutomaticDownloadQueue queue, EpisodeDownloadActions downloads) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
    }

    /** True means some pending work needs a later job; cancellation is rescheduled by JobScheduler. */
    public boolean run(BooleanSupplier stopRequested) throws IOException {
        return run(stopRequested, SecurityLimits.MAX_AUTOMATIC_DOWNLOADS_PER_JOB);
    }

    /** A preparation occurrence may have fewer transfer reservations remaining than one batch. */
    public boolean run(BooleanSupplier stopRequested, int limit) throws IOException {
        Objects.requireNonNull(stopRequested, "stopRequested");
        if (limit < 1 || limit > SecurityLimits.MAX_AUTOMATIC_DOWNLOADS_PER_JOB)
            throw new IllegalArgumentException("Invalid automatic download batch size.");
        List<String> candidates = queue.listAutomaticDownloadCandidates(limit + 1);
        if (candidates.size() > limit + 1) {
            throw new IOException("Automatic download candidate limit was exceeded.");
        }
        boolean deferred = false;
        for (int i = 0; i < Math.min(limit, candidates.size()); i++) {
            if (stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return true;
            }
            String mediaId = candidates.get(i);
            if (!queue.isAutomaticDownloadCandidate(mediaId)) {
                continue;
            }
            EligibilityCheck eligibility = new EligibilityCheck(mediaId, stopRequested);
            try {
                downloads.download(mediaId, eligibility);
            } catch (EpisodeDownloadException exception) {
                if (eligibility.storageFailure != null) {
                    throw eligibility.storageFailure;
                }
                if (stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return true;
                }
                if (exception.getReason() == EpisodeDownloadException.Reason.OFFLINE_MODE) {
                    // User policy is a deferral, not a failed attempt or opt-out.
                    return true;
                }
                // Transient failures get at most three scheduled attempts.
                if (retryable(exception.getReason())) {
                    queue.recordAutomaticDownloadFailure(mediaId);
                    deferred = queue.isAutomaticDownloadCandidate(mediaId) || deferred;
                    continue;
                }
                // Invalid media, exhausted quota, and unsafe responses remain
                // available through the explicit manual Download action.
                queue.clearAutomaticDownloadCandidate(mediaId);
            }
        }
        return deferred || !queue.listAutomaticDownloadCandidates(1).isEmpty();
    }

    private static boolean retryable(EpisodeDownloadException.Reason reason) {
        return switch (reason) {
            case ALREADY_ACTIVE, CANCELLED, FETCH_FAILED, HTTP_STATUS,
                    TARGET_CHANGED, STORAGE_FAILED -> true;
            default -> false;
        };
    }

    private final class EligibilityCheck implements BooleanSupplier {
        private final String mediaId;
        private final BooleanSupplier stopped;
        private IOException storageFailure;

        private EligibilityCheck(String mediaId, BooleanSupplier stopped) {
            this.mediaId = mediaId;
            this.stopped = stopped;
        }

        @Override
        public boolean getAsBoolean() {
            if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return true;
            }
            // The coordinator checks before, during, and after transfer; a
            // subscription archived or opted out mid-transfer cannot complete.
            try {
                return !queue.isAutomaticDownloadCandidate(mediaId);
            } catch (IOException exception) {
                storageFailure = exception;
                return true;
            }
        }
    }
}
