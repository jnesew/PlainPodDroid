package io.github.jnesew.plainpoddroid;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.Objects;

/** One network-constrained job, with no URLs or user data in scheduler extras. */
final class AutomaticDownloadScheduler {
    static final int JOB_ID = 0x50504444;
    private static final String EXTRA_UNMETERED_ONLY = "unmetered_only";

    private final JobScheduler jobScheduler;
    private final ComponentName service;
    private final AutomaticDownloadSettings settings;
    private final io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings privacy;

    AutomaticDownloadScheduler(Context context, AutomaticDownloadSettings settings) {
        Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
        privacy = new io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings(context);
        jobScheduler = Objects.requireNonNull(
                context.getSystemService(JobScheduler.class), "jobScheduler");
        service = new ComponentName(context, AutomaticDownloadJobService.class);
    }

    boolean reconcile() {
        if (privacy.isOfflineModeEnabled()) { jobScheduler.cancel(JOB_ID); return true; }
        JobInfo pending = jobScheduler.getPendingJob(JOB_ID);
        if (matches(pending)) {
            return true;
        }
        return schedule(0L);
    }

    boolean scheduleSoon() {
        if (privacy.isOfflineModeEnabled()) { jobScheduler.cancel(JOB_ID); return true; }
        if (matches(jobScheduler.getPendingJob(JOB_ID))) {
            return true;
        }
        return schedule(0L);
    }

    boolean scheduleLater() {
        return schedule(SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS);
    }

    private boolean schedule(long delayMillis) {
        if (privacy.isOfflineModeEnabled()) { jobScheduler.cancel(JOB_ID); return true; }
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_UNMETERED_ONLY, settings.isUnmeteredOnly());
        JobInfo job = new JobInfo.Builder(JOB_ID, service)
                .setExtras(extras)
                .setRequiredNetworkType(settings.isUnmeteredOnly()
                        ? JobInfo.NETWORK_TYPE_UNMETERED
                        : JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMillis)
                .setBackoffCriteria(SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS,
                        JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        return jobScheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    private boolean matches(JobInfo job) {
        return job != null
                && service.equals(job.getService())
                && job.getExtras().getBoolean(EXTRA_UNMETERED_ONLY,
                        !settings.isUnmeteredOnly()) == settings.isUnmeteredOnly()
                && !job.isPersisted();
    }
}
