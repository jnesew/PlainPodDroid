package io.github.jnesew.plainpoddroid;

import android.app.Application;
import android.os.StrictMode;
import android.util.Log;

import io.github.jnesew.plainpoddroid.catalog.EpisodeCatalogDatabase;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowserStore;
import io.github.jnesew.plainpoddroid.core.catalog.DownloadedEpisodeStore;
import io.github.jnesew.plainpoddroid.core.artwork.ArtworkTransport;
import io.github.jnesew.plainpoddroid.core.chapter.BoundedChapterAcquirer;
import io.github.jnesew.plainpoddroid.core.chapter.EpisodeChapterCoordinator;
import io.github.jnesew.plainpoddroid.core.download.AppPrivateDownloadStore;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadActions;
import io.github.jnesew.plainpoddroid.core.download.AutomaticDownloadSettingsStore;
import io.github.jnesew.plainpoddroid.core.download.AutomaticDownloadQueue;
import io.github.jnesew.plainpoddroid.core.download.CompletedDownloadRetention;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadCoordinator;
import io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeActionStore;
import io.github.jnesew.plainpoddroid.core.opml.OpmlMigrationStore;
import io.github.jnesew.plainpoddroid.core.playback.RemoteEpisodeCatalog;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackProgressStore;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshStateStore;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueStore;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionCoordinator;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRemovalStore;
import io.github.jnesew.plainpoddroid.network.PolicyNetworkStack;

import java.io.File;
import java.io.IOException;

public final class PlainPodDroidApplication extends Application {
    private static final String TAG = "PlainPodApplication";

    private PolicyNetworkStack networkStack;
    private PlaybackPrivacySettings privacySettings;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener privacyListener;
    private EpisodeCatalogDatabase episodeCatalogDatabase;
    private SubscriptionCoordinator subscriptionCoordinator;
    private EpisodeChapterCoordinator episodeChapterCoordinator;
    private EpisodeDownloadCoordinator episodeDownloadCoordinator;
    private AppPrivateDownloadStore appPrivateDownloadStore;
    private DownloadStorageSettings downloadStorageSettings;
    private DownloadRetentionSettings downloadRetentionSettings;
    private AutomaticRefreshSettings automaticRefreshSettings;
    private AutomaticRefreshScheduler automaticRefreshScheduler;
    private AutomaticDownloadSettings automaticDownloadSettings;
    private AutomaticDownloadScheduler automaticDownloadScheduler;
    private PreparationSettings preparationSettings;
    private PreparationScheduler preparationScheduler;
    private ManualDownloadManager manualDownloads;
    private io.github.jnesew.plainpoddroid.core.download.ManualDownloadRunner manualDownloadRunner;
    private io.github.jnesew.plainpoddroid.core.playback.PlaybackDownloadPreparer playbackDownloadPreparer;
    private final java.util.concurrent.ExecutorService downloadJobExecutor=
            java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"episode-download-jobs"));

    ManualDownloadManager getManualDownloads(){return manualDownloads;}
    io.github.jnesew.plainpoddroid.core.download.ManualDownloadRunner getManualDownloadRunner(){return manualDownloadRunner;}
    java.util.concurrent.ExecutorService getDownloadJobExecutor(){return downloadJobExecutor;}
    public io.github.jnesew.plainpoddroid.core.playback.PlaybackDownloadPreparer getPlaybackDownloadPreparer() {
        if (playbackDownloadPreparer == null) throw new IllegalStateException("Download storage is unavailable.");
        return playbackDownloadPreparer;
    }
    boolean hasWaitingPlaybackDownload() {
        return playbackDownloadPreparer != null && playbackDownloadPreparer.hasWaitingPlayback();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
        }
        episodeCatalogDatabase = new EpisodeCatalogDatabase(getApplicationContext());
        privacySettings = new PlaybackPrivacySettings(this);
        networkStack = PolicyNetworkStack.create(episodeCatalogDatabase, privacySettings::isOfflineModeEnabled);
        privacyListener = (preferences, key) -> {
            if (PlaybackPrivacySettings.isNeverStreamPreference(key)) {
                networkStack.onOfflineModeChanged();
                if (automaticRefreshScheduler != null) automaticRefreshScheduler.reconcile();
                if (automaticDownloadScheduler != null) automaticDownloadScheduler.reconcile();
                if (preparationScheduler != null) preparationScheduler.reconcile();
            }
        };
        privacySettings.registerListener(privacyListener);
        subscriptionCoordinator = new SubscriptionCoordinator(
                networkStack.newFeedTransport(BuildConfig.VERSION_NAME),
                episodeCatalogDatabase,
                this::scheduleAutomaticDownloadsAfterRefresh,
                new io.github.jnesew.plainpoddroid.core.subscription.ApplePodcastResolver(
                        networkStack.newApplePodcastLookupTransport(BuildConfig.VERSION_NAME)));
        episodeChapterCoordinator = new EpisodeChapterCoordinator(episodeCatalogDatabase,
                new BoundedChapterAcquirer(networkStack.newChapterTransport(BuildConfig.VERSION_NAME)));
        downloadStorageSettings = new DownloadStorageSettings(getApplicationContext());
        downloadRetentionSettings = new DownloadRetentionSettings(getApplicationContext());
        try {
            appPrivateDownloadStore = new AppPrivateDownloadStore(
                    new File(getNoBackupFilesDir(), "audio-downloads"));
            appPrivateDownloadStore.cleanupAbandonedTemporaryFiles(System.currentTimeMillis());
            episodeDownloadCoordinator = new EpisodeDownloadCoordinator(
                    episodeCatalogDatabase,
                    appPrivateDownloadStore,
                    networkStack.newMediaDownloadTransport(BuildConfig.VERSION_NAME),
                    downloadStorageSettings::getQuotaBytes,
                    new PlaybackPrivacySettings(this)::isNeverStreamAudioEnabled);
            playbackDownloadPreparer = new io.github.jnesew.plainpoddroid.core.playback.PlaybackDownloadPreparer(
                    appPrivateDownloadStore, episodeDownloadCoordinator, downloadJobExecutor,
                    privacySettings::isOfflineModeEnabled);
        } catch (IOException | RuntimeException exception) {
            // The screen remains usable, but all media actions fail closed for this process.
            Log.e(TAG, "Unable to initialize app-private download storage", exception);
            appPrivateDownloadStore = null;
            episodeDownloadCoordinator = null;
        }
        automaticRefreshSettings = new AutomaticRefreshSettings(getApplicationContext());
        automaticRefreshScheduler = new AutomaticRefreshScheduler(
                getApplicationContext(),
                automaticRefreshSettings);
        automaticRefreshScheduler.reconcile();
        automaticDownloadSettings = new AutomaticDownloadSettings(getApplicationContext());
        automaticDownloadScheduler = new AutomaticDownloadScheduler(
                getApplicationContext(), automaticDownloadSettings);
        automaticDownloadScheduler.reconcile();
        preparationSettings = new PreparationSettings(this);
        preparationScheduler = new PreparationScheduler(this, preparationSettings);
        preparationScheduler.reconcile();
        manualDownloads=new ManualDownloadManager(this,episodeCatalogDatabase,episodeDownloadCoordinator!=null);
        if(episodeDownloadCoordinator!=null)manualDownloadRunner=new io.github.jnesew.plainpoddroid.core.download.ManualDownloadRunner(
                episodeCatalogDatabase,episodeDownloadCoordinator,new PlaybackPrivacySettings(this)::isNeverStreamAudioEnabled,manualDownloads::refresh);
    }

    private void scheduleAutomaticDownloadsAfterRefresh() {
        AutomaticDownloadScheduler scheduler = automaticDownloadScheduler;
        if (scheduler != null) {
            try {
                if (!episodeCatalogDatabase.listAutomaticDownloadCandidates(1).isEmpty()) {
                    scheduler.scheduleSoon();
                }
            } catch (IOException | RuntimeException exception) {
                Log.e(TAG, "Unable to schedule automatic download work", exception);
            }
        }
    }

    boolean isOfflineModeEnabled() { return privacySettings.isOfflineModeEnabled(); }
    PreparationSettings getPreparationSettings() { return preparationSettings; }
    PreparationScheduler getPreparationScheduler() { return preparationScheduler; }

    SubscriptionCoordinator getSubscriptionCoordinator() {
        if (subscriptionCoordinator == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return subscriptionCoordinator;
    }

    EpisodeChapterCoordinator getEpisodeChapterCoordinator() {
        if (episodeChapterCoordinator == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeChapterCoordinator;
    }

    DownloadedEpisodeStore getDownloadedEpisodeStore() {
        return episodeCatalogDatabase;
    }

    io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore getEpisodeLibraryStore() {
        return episodeCatalogDatabase;
    }

    EpisodeCatalogDatabase getFeedLoginStore() { return episodeCatalogDatabase; }
    EpisodeCatalogDatabase getLibraryCatalog() { return episodeCatalogDatabase; }

    CatalogBrowserStore getCatalogBrowserStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    ArtworkTransport getArtworkTransport() {
        if (networkStack == null) {
            throw new IllegalStateException("Application network stack is not initialized.");
        }
        return networkStack.newArtworkTransport(BuildConfig.VERSION_NAME);
    }

    ScheduledRefreshStateStore getScheduledRefreshStateStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    EpisodeDownloadActions getEpisodeDownloadActions() {
        return episodeDownloadCoordinator;
    }

    AutomaticDownloadSettingsStore getAutomaticDownloadSettingsStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    AutomaticDownloadQueue getAutomaticDownloadQueue() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    AutomaticDownloadSettings getAutomaticDownloadSettings() {
        return automaticDownloadSettings;
    }

    AutomaticDownloadScheduler getAutomaticDownloadScheduler() {
        return automaticDownloadScheduler;
    }

    EpisodeQueueStore getEpisodeQueueStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    EpisodeActionStore getEpisodeActionStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    OpmlMigrationStore getOpmlMigrationStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    SubscriptionRemovalStore getSubscriptionRemovalStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    public AppPrivateDownloadStore getPlaybackDownloadStore() {
        if (appPrivateDownloadStore == null) {
            throw new IllegalStateException("Playback storage is not initialized.");
        }
        return appPrivateDownloadStore;
    }

    public DownloadRetentionSettings getDownloadRetentionSettings() {
        if (downloadRetentionSettings == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return downloadRetentionSettings;
    }

    public CompletedDownloadRetention getCompletedDownloadRetention() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    public RemoteEpisodeCatalog getRemoteEpisodeCatalog() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    public PlaybackProgressStore getPlaybackProgressStore() {
        if (episodeCatalogDatabase == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return episodeCatalogDatabase;
    }

    public io.github.jnesew.plainpoddroid.core.playback.PodcastPlaybackSettingsStore getPodcastPlaybackSettingsStore() {
        return episodeCatalogDatabase;
    }

    public PolicyNetworkStack getPlaybackNetworkStack() {
        if (networkStack == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return networkStack;
    }

    DownloadStorageSettings getDownloadStorageSettings() {
        if (downloadStorageSettings == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return downloadStorageSettings;
    }

    AutomaticRefreshSettings getAutomaticRefreshSettings() {
        if (automaticRefreshSettings == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return automaticRefreshSettings;
    }

    AutomaticRefreshScheduler getAutomaticRefreshScheduler() {
        if (automaticRefreshScheduler == null) {
            throw new IllegalStateException("Application dependencies are not initialized.");
        }
        return automaticRefreshScheduler;
    }
}
