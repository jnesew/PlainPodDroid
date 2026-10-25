package io.github.jnesew.plainpoddroid;

import android.app.job.JobParameters;
import android.app.job.JobService;

import io.github.jnesew.plainpoddroid.core.refresh.RefreshBackoffPolicy;
import io.github.jnesew.plainpoddroid.core.refresh.RefreshInterval;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshCandidate;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshRunResult;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshRunner;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScheduledRefreshJobService extends JobService {
    private final Object owner = new Object();
    private ExecutorService executor;
    private ActiveRun active;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(command ->
                new Thread(command, "scheduled-feed-refresh"));
    }

    @Override
    public boolean onStartJob(JobParameters parameters) {
        PlainPodDroidApplication application = (PlainPodDroidApplication) getApplication();
        RefreshInterval interval = application.getAutomaticRefreshSettings().getInterval();
        if (application.isOfflineModeEnabled() || !interval.isEnabled()) {
            application.getAutomaticRefreshScheduler().cancel();
            return false;
        }
        synchronized (owner) {
            if (active != null && !active.future.isDone()) {
                return false;
            }
            ActiveRun run = new ActiveRun(parameters);
            active = run;
            run.future = executor.submit(() -> runJob(application, run, interval));
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        synchronized (owner) {
            if (active != null) {
                active.stopRequested.set(true);
                active.future.cancel(true);
                active = null;
            }
        }
        return true;
    }

    @Override
    public void onDestroy() {
        synchronized (owner) {
            if (active != null) {
                active.stopRequested.set(true);
                active.future.cancel(true);
                active = null;
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    private void runJob(
            PlainPodDroidApplication application,
            ActiveRun run,
            RefreshInterval startedInterval) {
        boolean platformReschedule = false;
        try {
            ScheduledRefreshRunner runner = new ScheduledRefreshRunner(
                    application.getSubscriptionCoordinator(),
                    application.getScheduledRefreshStateStore());
            ScheduledRefreshRunResult result = runner.run(
                    startedInterval,
                    System.currentTimeMillis(),
                    () -> run.stopRequested.get() || application.isOfflineModeEnabled());
            if (!run.stopRequested.get()) {
                platformReschedule = !scheduleFollowingRun(application, result);
            }
        } catch (IOException | RuntimeException exception) {
            if (!run.stopRequested.get()) {
                platformReschedule = !scheduleFallback(application);
            }
        } finally {
            synchronized (owner) {
                if (active == run) {
                    active = null;
                }
            }
            if (!run.stopRequested.get()) {
                jobFinished(run.parameters, platformReschedule);
            }
        }
    }

    private boolean scheduleFollowingRun(
            PlainPodDroidApplication application,
            ScheduledRefreshRunResult result) throws IOException {
        AutomaticRefreshSettings settings = application.getAutomaticRefreshSettings();
        RefreshInterval interval = settings.getInterval();
        if (!interval.isEnabled()) {
            application.getAutomaticRefreshScheduler().cancel();
            return true;
        }
        List<ScheduledRefreshCandidate> candidates = application
                .getScheduledRefreshStateStore()
                .listScheduledRefreshCandidates();
        long delay = new RefreshBackoffPolicy().nextDelayMillis(
                candidates,
                interval,
                System.currentTimeMillis());
        if (result.getDeferred() > 0 || result.isCancelled()) {
            delay = Math.max(delay, SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS);
        }
        return application.getAutomaticRefreshScheduler().scheduleNext(
                interval,
                settings.isUnmeteredOnly(),
                delay);
    }

    private boolean scheduleFallback(PlainPodDroidApplication application) {
        AutomaticRefreshSettings settings = application.getAutomaticRefreshSettings();
        RefreshInterval interval = settings.getInterval();
        if (!interval.isEnabled()) {
            application.getAutomaticRefreshScheduler().cancel();
            return true;
        }
        return application.getAutomaticRefreshScheduler().scheduleNext(
                interval,
                settings.isUnmeteredOnly(),
                SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS);
    }

    private static final class ActiveRun {
        private final JobParameters parameters;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private Future<?> future;

        private ActiveRun(JobParameters parameters) {
            this.parameters = parameters;
        }
    }
}
