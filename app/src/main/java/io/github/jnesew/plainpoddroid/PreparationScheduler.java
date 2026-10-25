package io.github.jnesew.plainpoddroid;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PersistableBundle;
import io.github.jnesew.plainpoddroid.core.refresh.WeeklyPreparationSchedule;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.time.ZoneId;

/** One persisted, constrained job; extras contain only scheduling state. */
final class PreparationScheduler {
    static final int JOB_ID = 0x50504450;
    static final String DUE = "due", REVISION = "revision", ZONE = "zone", UNMETERED = "unmetered";
    private final Context context;
    private final PreparationSettings settings;
    private final JobScheduler jobs;
    private final ComponentName service;
    PreparationScheduler(Context context, PreparationSettings settings) {
        this.context = context.getApplicationContext(); this.settings = settings;
        jobs = context.getSystemService(JobScheduler.class);
        service = new ComponentName(context, PreparationJobService.class);
    }
    boolean reconcile() { return reconcile(false); }
    boolean reconcile(boolean force) {
        try {
            PreparationSettings.Config config = settings.get();
            configureReceiver(config.enabled());
            if (!config.enabled()) { jobs.cancel(JOB_ID); return true; }
            long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
            JobInfo pending = jobs.getPendingJob(JOB_ID);
            if (app().isOfflineModeEnabled()) {
                boolean remembered = true;
                if (pending != null && current(pending.getExtras(), config)
                        && config.schedule().keepPending(pending.getExtras().getLong(DUE), now, zone))
                    remembered = settings.rememberDue(config, pending.getExtras().getLong(DUE), zone);
                jobs.cancel(JOB_ID);
                return remembered;
            }
            if (pending != null && service.equals(pending.getService()) && pending.isPersisted()
                    && pending.isRequireCharging() == config.charging() && current(pending.getExtras(), config)
                    && config.schedule().keepPending(pending.getExtras().getLong(DUE), now, zone)
                    && !settings.finished(config, pending.getExtras().getLong(DUE))) {
                if (!settings.rememberDue(config, pending.getExtras().getLong(DUE), zone)) return false;
                return !force || schedule(config, pending.getExtras().getLong(DUE), 0L);
            }
            if (pending != null && current(pending.getExtras(), config))
                markMissed(config, pending.getExtras().getLong(DUE), now, zone);
            long remembered = settings.rememberedDue(config, zone);
            if (remembered > 0 && !settings.finished(config, remembered)
                    && config.schedule().keepPending(remembered, now, zone))
                return schedule(config, remembered, 0L);
            markMissed(config, remembered, now, zone);
            var status = settings.status();
            if (status.revision() == config.revision() && !settings.finished(config, status.due())
                    && config.schedule().keepPending(status.due(), now, zone))
                return schedule(config, status.due(), SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS);
            if (status.revision() == config.revision()) markMissed(config, status.due(), now, zone);
            return schedule(config, config.schedule().nextAfter(now, zone), 0L);
        } catch (RuntimeException unavailable) { return false; }
    }
    private void markMissed(PreparationSettings.Config config, long due, long now, ZoneId zone) {
        if (due > 0 && now > due && now - due > WeeklyPreparationSchedule.MAX_LATENESS_MILLIS
                && config.schedule().isOccurrence(due, zone)) settings.missed(config, due);
    }
    boolean retry(PreparationSettings.Config config, long due) {
        try {
            long now = System.currentTimeMillis();
            if (!config.equals(settings.get()) || !config.enabled() || app().isOfflineModeEnabled()
                    || settings.finished(config, due) || !config.schedule().mayStart(due, now, ZoneId.systemDefault())
                    || WeeklyPreparationSchedule.MAX_LATENESS_MILLIS - (now - due)
                        < SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS) return false;
            return schedule(config, due, SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS);
        } catch (RuntimeException unavailable) { return false; }
    }
    long nextDueMillis() {
        try {
            JobInfo pending = jobs.getPendingJob(JOB_ID);
            if (pending != null && current(pending.getExtras(), settings.get())) return pending.getExtras().getLong(DUE, 0L);
        } catch (RuntimeException unavailable) { /* Settings must remain usable if JobScheduler is unavailable. */ }
        return 0L;
    }
    private boolean schedule(PreparationSettings.Config config, long due, long delay) {
        if (!config.enabled() || !config.equals(settings.get()) || app().isOfflineModeEnabled()) return false;
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        var status = settings.status();
        if (status.due() == due && status.revision() == config.revision())
            delay = Math.max(delay, status.finishedAt() > now ? SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS
                    : Math.max(0L, SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS - (now - status.finishedAt())));
        if (!settings.rememberDue(config, due, zone)) return false;
        return jobs.schedule(buildJob(config, due, delay, service, now, zone, unmetered(config))) == JobScheduler.RESULT_SUCCESS;
    }
    static JobInfo buildJob(PreparationSettings.Config config, long due, long delay, ComponentName service,
            long now, ZoneId zone, boolean unmetered) {
        PersistableBundle extras = new PersistableBundle();
        extras.putLong(DUE, due); extras.putLong(REVISION, config.revision());
        extras.putString(ZONE, zone.getId());
        extras.putBoolean(UNMETERED, unmetered);
        return new JobInfo.Builder(JOB_ID, service).setPersisted(true).setExtras(extras)
                .setRequiredNetworkType(unmetered ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(config.charging()).setMinimumLatency(Math.max(Math.max(0L, delay), due - now))
                .setBackoffCriteria(SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
    }
    boolean current(PersistableBundle extras, PreparationSettings.Config config) {
        try { return config.enabled() && extras.getLong(REVISION, -1L) == config.revision()
                    && extras.getBoolean(UNMETERED, !unmetered(config)) == unmetered(config)
                    && ZoneId.systemDefault().getId().equals(extras.getString(ZONE)); }
        catch (RuntimeException invalid) { return false; }
    }
    boolean mayStart(PersistableBundle extras, PreparationSettings.Config config) {
        return current(extras, config) && !app().isOfflineModeEnabled()
                && config.schedule().mayStart(extras.getLong(DUE, 0L), System.currentTimeMillis(), ZoneId.systemDefault())
                && !settings.finished(config, extras.getLong(DUE));
    }
    boolean unmetered(PreparationSettings.Config config) {
        return config.unmetered() || app().getAutomaticRefreshSettings().isUnmeteredOnly()
                || app().getAutomaticDownloadSettings().isUnmeteredOnly();
    }
    private PlainPodDroidApplication app() { return (PlainPodDroidApplication) context; }
    private void configureReceiver(boolean enabled) {
        var manager = context.getPackageManager();
        var receiver = new ComponentName(context, PreparationScheduleReceiver.class);
        int current = manager.getComponentEnabledSetting(receiver);
        if (enabled && current != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            manager.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        else if (!enabled && current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            manager.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}
