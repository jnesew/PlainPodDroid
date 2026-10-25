package io.github.jnesew.plainpoddroid;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import io.github.jnesew.plainpoddroid.artwork.BackgroundArtworkPreparer;
import io.github.jnesew.plainpoddroid.core.artwork.BoundedArtworkAcquirer;
import io.github.jnesew.plainpoddroid.core.download.AutomaticDownloadRunner;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadActions;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadCoordinator;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadResult;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshRunner;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.network.PolicyNetworkStack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Refreshes and prepares bounded batches. There is no player or playback command here. */
public final class PreparationJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private Run active;

    @Override public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "scheduled-podcast-preparation"));
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        if (parameters.getJobId() != PreparationScheduler.JOB_ID || active != null) return false;
        var app = app();
        var config = app.getPreparationSettings().get();
        if (!app.getPreparationScheduler().mayStart(parameters.getExtras(), config)) {
            main.post(() -> app.getPreparationScheduler().reconcile());
            return false;
        }
        Run run = new Run(parameters, config);
        active = run;
        run.future = worker.submit(() -> execute(app, run));
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Run run = active;
        if (run == null || parameters.getJobId() != run.parameters.getJobId()
                || parameters.getExtras().getLong(PreparationScheduler.DUE) != run.due
                || parameters.getExtras().getLong(PreparationScheduler.REVISION) != run.config.revision()) return false;
        active = null;
        revoke(run, true);
        boolean retry = app().getPreparationScheduler().mayStart(parameters.getExtras(), run.config);
        record(run, interruptionOutcome(run.due, System.currentTimeMillis()));
        if (!retry) main.post(() -> app().getPreparationScheduler().reconcile());
        return retry;
    }

    @Override public void onNetworkChanged(JobParameters parameters) {
        Run run = active;
        if (run != null && !java.util.Objects.equals(run.selectedNetwork, parameters.getNetwork())) revoke(run, false);
    }

    @Override public void onDestroy() {
        if (active != null) {
            revoke(active, true);
            record(active, PreparationSettings.Outcome.PAUSED);
            active = null;
        }
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    private PlainPodDroidApplication app() { return (PlainPodDroidApplication) getApplication(); }

    static PreparationSettings.Outcome interruptionOutcome(long due, long now) {
        return now > due && now - due > io.github.jnesew.plainpoddroid.core.refresh.WeeklyPreparationSchedule.MAX_LATENESS_MILLIS
                ? PreparationSettings.Outcome.MISSED : PreparationSettings.Outcome.PAUSED;
    }

    private static void revoke(Run run, boolean interrupt) {
        synchronized (run) { run.revoked.set(true); }
        if (run.network != null) run.network.onOfflineModeChanged();
        if (interrupt && run.future != null) run.future.cancel(true);
    }

    private boolean stopped(PlainPodDroidApplication app, Run run) {
        if (run.revoked.get() || Thread.currentThread().isInterrupted() || run.persistenceFailed
                || !run.config.equals(app.getPreparationSettings().get())
                || !app.getPreparationScheduler().mayStart(run.parameters.getExtras(), run.config)
                || (run.attempt != null && !app.getPreparationSettings().owns(run.attempt))
                || run.selectedNetwork == null
                || !run.selectedNetwork.equals(run.parameters.getNetwork())) return true;
        NetworkCapabilities caps = getSystemService(ConnectivityManager.class).getNetworkCapabilities(run.selectedNetwork);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || (app.getPreparationScheduler().unmetered(run.config)
                    && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))) return true;
        return run.config.charging() && !getSystemService(BatteryManager.class).isCharging();
    }

    private void execute(PlainPodDroidApplication app, Run run) {
        boolean more = false;
        PreparationSettings.Outcome outcome = PreparationSettings.Outcome.FAILED;
        try {
            synchronized (run) {
                if (run.revoked.get()) return;
                run.attempt = app.getPreparationSettings().begin(run.config, run.due);
                if (run.attempt == null) return;
                var previous = app.getPreparationSettings().status();
                run.refreshed = previous.refreshed(); run.downloaded = previous.downloaded();
                run.transfers = previous.transfers(); run.problems = previous.problems();
                run.refreshFinished = previous.refreshComplete();
            }
            if (stopped(app, run)) return;
            run.network = app.getPlaybackNetworkStack().forNetwork(run.selectedNetwork, () -> stopped(app, run));
            if (!run.refreshFinished) {
                var refresher = app.getSubscriptionCoordinator().refresherFor(
                        run.network.newFeedTransport(BuildConfig.VERSION_NAME), () -> { });
                var refreshed = new ScheduledRefreshRunner(refresher, app.getScheduledRefreshStateStore())
                        .runPreparation(System.currentTimeMillis(), () -> stopped(app, run));
                synchronized (run) {
                    run.refreshed = Math.max(run.refreshed, refreshed.getSucceeded());
                    run.refreshFinished = !refreshed.isCancelled();
                    run.problems |= refreshed.getFailed() > 0 || refreshed.getDeferred() > 0;
                    if (!run.revoked.get()) record(run, PreparationSettings.Outcome.RUNNING);
                }
                if (refreshed.isCancelled() || stopped(app, run)) return;
            }
            Future<Boolean> transfers = app.getDownloadJobExecutor().submit(() -> downloadBatch(app, run));
            try {
                while (!stopped(app, run)) {
                    try { more = transfers.get(200L, TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException pending) { /* Cancellation remains responsive while queued. */ }
                }
            } finally { if (!transfers.isDone()) transfers.cancel(false); }
            if (stopped(app, run)) return;
            List<CanonicalHttpsUrl> covers = new ArrayList<>();
            for (var podcast : app.getCatalogBrowserStore().listSubscriptions()) {
                if (podcast.isSubscribed() && podcast.isAutoDownload() && podcast.getArtworkUrl() != null)
                    covers.add(podcast.getArtworkUrl());
                if (covers.size() >= SecurityLimits.MAX_PREPARATION_COVERS) break;
            }
            BackgroundArtworkPreparer.prepare(new BoundedArtworkAcquirer(run.network.newArtworkTransport(BuildConfig.VERSION_NAME)),
                    getCacheDir().toPath().resolve("artwork-v1"), covers, () -> stopped(app, run));
            outcome = run.problems || more ? PreparationSettings.Outcome.PARTIAL : PreparationSettings.Outcome.COMPLETE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | RuntimeException failure) {
            run.problems = true;
        } finally {
            boolean interrupted = stopped(app, run);
            boolean continuation = run.attempt != null && !run.persistenceFailed && (more || interrupted)
                    && run.transfers < SecurityLimits.MAX_PREPARATION_DOWNLOADS;
            PreparationSettings.Outcome result = run.persistenceFailed ? PreparationSettings.Outcome.FAILED
                    : interrupted ? PreparationSettings.Outcome.PAUSED : outcome;
            // Permanently revoke this attempt before its serial transfer can outlive the worker.
            revoke(run, false);
            main.post(() -> finish(app, run, result, continuation));
        }
    }

    private boolean downloadBatch(PlainPodDroidApplication app, Run run) throws IOException {
        int remaining = SecurityLimits.MAX_PREPARATION_DOWNLOADS - run.transfers;
        if (remaining <= 0) return !app.getAutomaticDownloadQueue().listAutomaticDownloadCandidates(1).isEmpty();
        EpisodeDownloadActions coordinator = new EpisodeDownloadCoordinator(app.getLibraryCatalog(),
                app.getPlaybackDownloadStore(), run.network.newMediaDownloadTransport(BuildConfig.VERSION_NAME),
                app.getDownloadStorageSettings()::getQuotaBytes, app::isOfflineModeEnabled);
        EpisodeDownloadActions counted = new EpisodeDownloadActions() {
            @Override public EpisodeDownloadResult download(String id, BooleanSupplier cancelled) throws EpisodeDownloadException {
                final boolean reserved;
                synchronized (run) {
                    boolean cancelledBeforeReservation = cancelled.getAsBoolean();
                    reserved = !cancelledBeforeReservation && app.getPreparationSettings().reserveDownload(run.attempt);
                    if (reserved) run.transfers++;
                    else if (!cancelledBeforeReservation) run.persistenceFailed = true;
                }
                try {
                    EpisodeDownloadResult result = coordinator.download(id,
                            () -> !reserved || cancelled.getAsBoolean() || stopped(app, run));
                    synchronized (run) {
                        if (!run.revoked.get()) {
                            run.downloaded++;
                            record(run, PreparationSettings.Outcome.RUNNING);
                        }
                    }
                    return result;
                } catch (EpisodeDownloadException failure) {
                    if (!cancelled.getAsBoolean()) run.problems = true;
                    throw failure;
                }
            }
            @Override public void delete(String id) throws EpisodeDownloadException { coordinator.delete(id); }
            @Override public long getStorageUsageBytes() throws EpisodeDownloadException { return coordinator.getStorageUsageBytes(); }
        };
        return new AutomaticDownloadRunner(app.getAutomaticDownloadQueue(), counted).run(
                () -> stopped(app, run) || app.hasWaitingPlaybackDownload(),
                Math.min(remaining, SecurityLimits.MAX_AUTOMATIC_DOWNLOADS_PER_JOB));
    }

    private void record(Run run, PreparationSettings.Outcome outcome) {
        synchronized (run) {
            if (run.attempt != null && !app().getPreparationSettings().record(run.attempt, outcome,
                    run.refreshed, run.downloaded, run.problems, run.refreshFinished)) run.persistenceFailed = true;
        }
    }

    private void finish(PlainPodDroidApplication app, Run run, PreparationSettings.Outcome outcome, boolean more) {
        if (active != run) return;
        active = null;
        app.getManualDownloads().refresh();
        jobFinished(run.parameters, false);
        if (!run.config.equals(app.getPreparationSettings().get()) || !run.config.enabled()) {
            app.getPreparationScheduler().reconcile(); return;
        }
        if (more && app.getPreparationScheduler().retry(run.config, run.due)) record(run, PreparationSettings.Outcome.PAUSED);
        else if (more && app.isOfflineModeEnabled()
                && interruptionOutcome(run.due, System.currentTimeMillis()) == PreparationSettings.Outcome.PAUSED) {
            record(run, PreparationSettings.Outcome.PAUSED);
            app.getPreparationScheduler().reconcile();
        }
        else {
            if (outcome == PreparationSettings.Outcome.PAUSED)
                outcome = PreparationSettings.Outcome.PARTIAL;
            record(run, outcome);
            app.getPreparationScheduler().reconcile(true);
        }
    }

    private static final class Run {
        final JobParameters parameters;
        final PreparationSettings.Config config;
        final long due;
        final Network selectedNetwork;
        final AtomicBoolean revoked = new AtomicBoolean();
        volatile PreparationSettings.Attempt attempt;
        volatile PolicyNetworkStack network;
        Future<?> future;
        volatile int refreshed, downloaded, transfers;
        volatile boolean problems, refreshFinished, persistenceFailed;
        Run(JobParameters parameters, PreparationSettings.Config config) {
            this.parameters = parameters; this.config = config;
            due = parameters.getExtras().getLong(PreparationScheduler.DUE);
            selectedNetwork = parameters.getNetwork();
        }
    }
}
