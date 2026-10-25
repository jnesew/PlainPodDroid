package io.github.jnesew.plainpoddroid;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import io.github.jnesew.plainpoddroid.core.refresh.RefreshInterval;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.Objects;

final class AutomaticRefreshScheduler {
    static final int JOB_ID = 0x50504452;
    private static final String EXTRA_INTERVAL_HOURS = "interval_hours";
    private static final String EXTRA_UNMETERED_ONLY = "unmetered_only";

    private final JobScheduler jobScheduler;
    private final ComponentName service;
    private final AutomaticRefreshSettings settings;
    private final io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings privacy;

    AutomaticRefreshScheduler(Context context, AutomaticRefreshSettings settings) {
        Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
        privacy = new io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings(context);
        jobScheduler = Objects.requireNonNull(
                context.getSystemService(JobScheduler.class),
                "jobScheduler");
        service = new ComponentName(context, ScheduledRefreshJobService.class);
    }

    boolean reconcile() {
        RefreshInterval interval = settings.getInterval();
        if (privacy.isOfflineModeEnabled() || !interval.isEnabled()) {
            jobScheduler.cancel(JOB_ID);
            return true;
        }
        boolean unmeteredOnly = settings.isUnmeteredOnly();
        JobInfo pending = jobScheduler.getPendingJob(JOB_ID);
        if (matches(pending, interval, unmeteredOnly)) {
            return true;
        }
        return scheduleNext(interval, unmeteredOnly, interval.getIntervalMillis());
    }

    boolean scheduleNext(
            RefreshInterval interval,
            boolean unmeteredOnly,
            long requestedDelayMillis) {
        Objects.requireNonNull(interval, "interval");
        if (privacy.isOfflineModeEnabled()) { cancel(); return true; }
        if (!interval.isEnabled() || requestedDelayMillis < 0L) {
            throw new IllegalArgumentException("Invalid automatic refresh schedule.");
        }
        long delayMillis = Math.min(
                requestedDelayMillis,
                SecurityLimits.MAX_REFRESH_BACKOFF_MILLIS);
        PersistableBundle extras = new PersistableBundle();
        extras.putInt(EXTRA_INTERVAL_HOURS, interval.getHours());
        extras.putBoolean(EXTRA_UNMETERED_ONLY, unmeteredOnly);
        JobInfo job = new JobInfo.Builder(JOB_ID, service)
                .setExtras(extras)
                .setRequiredNetworkType(unmeteredOnly
                        ? JobInfo.NETWORK_TYPE_UNMETERED
                        : JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMillis)
                .setBackoffCriteria(
                        SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS,
                        JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        return jobScheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    void cancel() {
        jobScheduler.cancel(JOB_ID);
    }

    private boolean matches(
            JobInfo pending,
            RefreshInterval interval,
            boolean unmeteredOnly) {
        if (pending == null) {
            return false;
        }
        PersistableBundle extras = pending.getExtras();
        return service.equals(pending.getService())
                && extras.getInt(EXTRA_INTERVAL_HOURS, Integer.MIN_VALUE)
                        == interval.getHours()
                && extras.getBoolean(EXTRA_UNMETERED_ONLY, !unmeteredOnly)
                        == unmeteredOnly
                && !pending.isPersisted();
    }
}
