package io.github.jnesew.plainpoddroid.playback;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import io.github.jnesew.plainpoddroid.core.download.AppPrivateDownloadStore;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackQueuePolicy;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackProgressCoordinator;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackRequestResolver;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackSource;
import io.github.jnesew.plainpoddroid.core.playback.SleepTimerOption;
import io.github.jnesew.plainpoddroid.core.playback.SleepTimerState;
import io.github.jnesew.plainpoddroid.PlainPodDroidApplication;
import io.github.jnesew.plainpoddroid.BuildConfig;
import io.github.jnesew.plainpoddroid.DownloadRetentionSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class PlaybackService extends MediaSessionService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PlainPodPlayback";
    private static final long PROGRESS_SAMPLE_INTERVAL_MILLIS = 5_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable sleepTimerExpiry = this::expireSleepTimerIfNeeded;
    private final Runnable progressSampler = new Runnable() {
        @Override
        public void run() {
            if (player == null || !player.isPlaying()) {
                return;
            }
            sampleCurrentProgress(false);
            mainHandler.postDelayed(this, PROGRESS_SAMPLE_INTERVAL_MILLIS);
        }
    };
    private final Player.Listener progressListener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateProtectedMediaId();
            if (isPlaying) {
                expireSleepTimerIfNeeded();
                sampleCurrentProgress(false);
                scheduleProgressSampling();
            } else {
                mainHandler.removeCallbacks(progressSampler);
                sampleCurrentProgress(true);
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            updateProtectedMediaId();
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                sampleCurrentProgress(true);
                if (playbackState == Player.STATE_ENDED && player != null) {
                    MediaItem endedItem = player.getCurrentMediaItem();
                    if (endedItem != null) queueCompletedDownloadCleanup(endedItem.mediaId);
                }
                cancelSleepTimerInternal(true);
            }
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (!playWhenReady
                    && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                    && sleepTimerState.isEndOfEpisode()) {
                sampleCurrentProgress(true);
                cancelSleepTimerInternal(true);
            }
        }

        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            updateProtectedMediaId();
            sampleCurrentProgress(false);
            applyPlaybackControlSettings();
        }

        @Override
        public void onPositionDiscontinuity(
                Player.PositionInfo oldPosition,
                Player.PositionInfo newPosition,
                int reason) {
            MediaItem oldItem = oldPosition.mediaItem;
            MediaItem newItem = newPosition.mediaItem;
            if (oldItem != null
                    && (newItem == null || !oldItem.mediaId.equals(newItem.mediaId))) {
                forceDiscontinuedEpisode(oldItem.mediaId, oldPosition.positionMs);
                updateProtectedMediaId();
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    queueCompletedDownloadCleanup(oldItem.mediaId);
                }
            }
            sampleCurrentProgress(false);
        }
    };
    private PlaybackPrivacySettings privacySettings;
    private PlaybackControlSettings controlSettings;
    private PlaybackCompletionSettings completionSettings;
    private DownloadRetentionSettings retentionSettings;
    private volatile String protectedMediaId;
    private AppPrivateDownloadStore downloadStore;
    private ExecutorService progressExecutor;
    private PlaybackResolutionWorker resolutionWorker;
    private PlaybackProgressCoordinator progressCoordinator;
    private io.github.jnesew.plainpoddroid.core.playback.PodcastPlaybackSpeedCoordinator speedCoordinator;
    private SleepTimerState sleepTimerState = SleepTimerState.inactive();
    private ExoPlayer player;
    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        privacySettings = new PlaybackPrivacySettings(this);
        controlSettings = new PlaybackControlSettings(this);
        completionSettings = new PlaybackCompletionSettings(this);
        try {
            PlainPodDroidApplication application =
                    (PlainPodDroidApplication) getApplication();
            retentionSettings = application.getDownloadRetentionSettings();
            downloadStore = application.getPlaybackDownloadStore();
            progressExecutor = Executors.newSingleThreadExecutor(
                    PlaybackService::newProgressThread);
            speedCoordinator=new io.github.jnesew.plainpoddroid.core.playback.PodcastPlaybackSpeedCoordinator(
                    application.getPodcastPlaybackSettingsStore(),progressExecutor,command->mainHandler.post(command),
                    speed->{if(player!=null)player.setPlaybackSpeed(speed.getMultiplier());});
            progressCoordinator = new PlaybackProgressCoordinator(
                    application.getPlaybackProgressStore(),
                    progressExecutor,
                    () -> Log.w(TAG, "Playback progress could not be saved"),
                    completionSettings::getMode);
            PlaybackRequestResolver resolver = new PlaybackRequestResolver(
                    privacySettings,
                    downloadStore,
                    application.getRemoteEpisodeCatalog());
            resolutionWorker = new PlaybackResolutionWorker(resolver);
            DataSource.Factory remoteSourceFactory = new OkHttpDataSource.Factory(
                    application.getPlaybackNetworkStack()
                            .newMediaStreamingCallFactory(BuildConfig.VERSION_NAME,
                                    privacySettings::isDownloadBeforePlayingEnabled));
            DataSource.Factory localSourceFactory = new VerifiedDownloadDataSource.Factory(downloadStore);
            DataSource.Factory mediaDataSourceFactory = new PlaybackRoutingDataSource.Factory(
                    localSourceFactory, remoteSourceFactory,
                    new DownloadBeforePlayingDataSource.Factory(
                            application.getPlaybackDownloadPreparer(), localSourceFactory));
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(
                    mediaDataSourceFactory);
            player = new ExoPlayer.Builder(this, mediaSourceFactory)
                    .setHandleAudioBecomingNoisy(true)
                    .build();
            applyPlaybackControlSettings();
            player.addListener(progressListener);
            mediaSession = new MediaSession.Builder(this, new PodcastSessionPlayer(player))
                    .setCallback(new PlaybackSessionCallback(
                            Process.myUid(),
                            resolutionWorker,
                            this::enforceCurrentPlaylist,
                            new PlaybackSessionCallback.SleepTimerControl() {
                                @Override
                                public SessionResult arm(SleepTimerOption option) {
                                    return armSleepTimer(option);
                                }

                                @Override
                                public SessionResult cancel() {
                                    cancelSleepTimerInternal(true);
                                    return sleepTimerStatusResult();
                                }

                                @Override
                                public SessionResult getStatus() {
                                    expireSleepTimerIfNeeded();
                                    return sleepTimerStatusResult();
                                }
                            }))
                    .build();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to initialize app-private playback storage", exception);
            stopSelf();
            return;
        }
        privacySettings.registerListener(this);
        controlSettings.registerListener(this);
        enforceCurrentPlaylist();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onSharedPreferenceChanged(
            SharedPreferences sharedPreferences,
            String key) {
        boolean enforcePrivacy = PlaybackPrivacySettings.isNeverStreamPreference(key)
                || PlaybackPrivacySettings.isDownloadBeforePlayingPreference(key);
        boolean updatePlaybackControls = PlaybackControlSettings.isSkipPreference(key)
                || PlaybackControlSettings.isSpeedPreference(key);
        if (!enforcePrivacy && !updatePlaybackControls) {
            return;
        }
        Runnable update = () -> {
            if (updatePlaybackControls) {
                applyPlaybackControlSettings();
            }
            if (enforcePrivacy) {
                enforceCurrentPlaylist();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run();
        } else {
            mainHandler.post(update);
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(progressSampler);
        cancelSleepTimerInternal(false);
        if (privacySettings != null) {
            privacySettings.unregisterListener(this);
        }
        if(speedCoordinator!=null)speedCoordinator.close();
        if (controlSettings != null) {
            controlSettings.unregisterListener(this);
        }
        if (player != null) {
            sampleCurrentProgress(true);
            player.removeListener(progressListener);
        }
        if (progressCoordinator != null) {
            progressCoordinator.close(
                    SystemClock.elapsedRealtime(),
                    System.currentTimeMillis());
            progressCoordinator = null;
        }
        if (resolutionWorker != null) resolutionWorker.close();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (progressExecutor != null) {
            progressExecutor.shutdown();
            progressExecutor = null;
        }
        super.onDestroy();
    }

    private boolean enforceCurrentPlaylist() {
        if (player == null || privacySettings == null
                || (!privacySettings.isNeverStreamAudioEnabled()
                    && !privacySettings.isDownloadBeforePlayingEnabled())) {
            return true;
        }

        List<PlaybackSource.Kind> kinds = new ArrayList<>(player.getMediaItemCount());
        for (int index = 0; index < player.getMediaItemCount(); index++) {
            MediaItem item = player.getMediaItemAt(index);
            boolean verified = false;
            if (item.localConfiguration != null && downloadStore != null) {
                try {
                    verified = downloadStore.isVerifiedCompleteUri(
                            item.localConfiguration.uri.toString());
                } catch (IOException | RuntimeException exception) {
                    Log.w(TAG, "Unable to verify queued playback source", exception);
                }
            }
            kinds.add(verified
                    ? PlaybackSource.Kind.VERIFIED_LOCAL
                    : item.localConfiguration != null && !privacySettings.isOfflineModeEnabled()
                            && io.github.jnesew.plainpoddroid.core.playback.DownloadPlaybackUri.SCHEME.equals(
                                    item.localConfiguration.uri.getScheme())
                            ? PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL : PlaybackSource.Kind.REMOTE_HTTPS);
        }

        if (!PlaybackQueuePolicy.mustStopAndClear(privacySettings.isOfflineModeEnabled(),
                privacySettings.isDownloadBeforePlayingEnabled(), kinds)) {
            return true;
        }
        sampleCurrentProgress(true);
        player.stop();
        player.clearMediaItems();
        return false;
    }

    private void scheduleProgressSampling() {
        mainHandler.removeCallbacks(progressSampler);
        if (player != null && player.isPlaying()) {
            mainHandler.postDelayed(progressSampler, PROGRESS_SAMPLE_INTERVAL_MILLIS);
        }
    }

    private void updateProtectedMediaId() {
        MediaItem item = player == null ? null : player.getCurrentMediaItem();
        protectedMediaId = item != null
                && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlaybackState() != Player.STATE_ENDED ? item.mediaId : null;
    }

    private void queueCompletedDownloadCleanup(String mediaId) {
        if (retentionSettings == null || !retentionSettings.isAutoDeleteCompleted()
                || progressExecutor == null || downloadStore == null) return;
        PlainPodDroidApplication application = (PlainPodDroidApplication) getApplication();
        AppPrivateDownloadStore store = downloadStore;
        try {
            // The same serial worker persists the final played state before cleanup.
            progressExecutor.execute(() -> {
                if (!retentionSettings.isAutoDeleteCompleted()
                        || mediaId.equals(protectedMediaId)) return;
                try {
                    application.getCompletedDownloadRetention()
                            .deleteCompletedDownloadIfEligible(mediaId, target -> {
                                if (!retentionSettings.isAutoDeleteCompleted()
                                        || target.equals(protectedMediaId)) {
                                    throw new IOException("Completed download is protected.");
                                }
                                store.delete(target);
                            });
                } catch (IOException | RuntimeException exception) {
                    Log.w(TAG, "Completed download cleanup was deferred");
                }
            });
        } catch (RuntimeException exception) {
            Log.w(TAG, "Completed download cleanup was deferred");
        }
    }

    private void applyPlaybackControlSettings() {
        if (player == null || controlSettings == null) {
            return;
        }
        player.setSeekBackIncrementMs(controlSettings.getSkipBackInterval().getMillis());
        player.setSeekForwardIncrementMs(controlSettings.getSkipForwardInterval().getMillis());
        player.setPlaybackSpeed(controlSettings.getPlaybackSpeed().getMultiplier());
        if(speedCoordinator!=null){MediaItem item=player.getCurrentMediaItem();
            speedCoordinator.select(item==null?"":item.mediaId,controlSettings.getPlaybackSpeed());}
    }

    private SessionResult armSleepTimer(SleepTimerOption option) {
        if (player == null
                || player.getCurrentMediaItem() == null
                || player.getPlaybackState() == Player.STATE_IDLE
                || player.getPlaybackState() == Player.STATE_ENDED) {
            return new SessionResult(SessionError.ERROR_INVALID_STATE);
        }
        mainHandler.removeCallbacks(sleepTimerExpiry);
        sleepTimerState = SleepTimerState.arm(option, SystemClock.elapsedRealtime());
        player.setPauseAtEndOfMediaItems(option.isEndOfEpisode());
        if (!option.isEndOfEpisode()) {
            mainHandler.postDelayed(sleepTimerExpiry, option.getDurationMillis());
        }
        publishSleepTimerStatus();
        return sleepTimerStatusResult();
    }

    private void expireSleepTimerIfNeeded() {
        if (!sleepTimerState.isExpired(SystemClock.elapsedRealtime())) {
            return;
        }
        if (player != null) {
            player.pause();
            sampleCurrentProgress(true);
        }
        cancelSleepTimerInternal(true);
    }

    private void cancelSleepTimerInternal(boolean publish) {
        if (!sleepTimerState.isActive()) {
            return;
        }
        mainHandler.removeCallbacks(sleepTimerExpiry);
        sleepTimerState = SleepTimerState.inactive();
        if (player != null) {
            player.setPauseAtEndOfMediaItems(false);
        }
        if (publish) {
            publishSleepTimerStatus();
        }
    }

    private SessionResult sleepTimerStatusResult() {
        return new SessionResult(
                SessionResult.RESULT_SUCCESS,
                PlaybackSleepTimerCommands.statusExtras(
                        sleepTimerState,
                        SystemClock.elapsedRealtime()));
    }

    private void publishSleepTimerStatus() {
        if (mediaSession == null) {
            return;
        }
        Bundle status = PlaybackSleepTimerCommands.statusExtras(
                sleepTimerState,
                SystemClock.elapsedRealtime());
        for (MediaSession.ControllerInfo controller : mediaSession.getConnectedControllers()) {
            if (controller.getUid() == Process.myUid()) {
                mediaSession.sendCustomCommand(
                        controller,
                        PlaybackSleepTimerCommands.statusChangedCommand(),
                        status);
            }
        }
    }

    private void sampleCurrentProgress(boolean force) {
        ExoPlayer currentPlayer = player;
        PlaybackProgressCoordinator coordinator = progressCoordinator;
        if (currentPlayer == null || coordinator == null) {
            return;
        }
        MediaItem item = currentPlayer.getCurrentMediaItem();
        if (item == null) {
            return;
        }
        long elapsedRealtime = SystemClock.elapsedRealtime();
        long wallClock = System.currentTimeMillis();
        try {
            if (currentPlayer.isPlaying()) coordinator.recordListening(item.mediaId, elapsedRealtime, wallClock);
            if (force) {
                coordinator.force(
                        item.mediaId,
                        currentPlayer.getCurrentPosition(),
                        currentPlayer.getDuration(),
                        elapsedRealtime,
                        wallClock);
            } else {
                coordinator.sample(
                        item.mediaId,
                        currentPlayer.getCurrentPosition(),
                        currentPlayer.getDuration(),
                        elapsedRealtime,
                        wallClock);
            }
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Invalid playback progress sample was ignored");
        }
    }

    private void forceDiscontinuedEpisode(String mediaId, long positionMillis) {
        PlaybackProgressCoordinator coordinator = progressCoordinator;
        if (coordinator == null) {
            return;
        }
        try {
            coordinator.forceCurrentPosition(
                    mediaId,
                    positionMillis,
                    SystemClock.elapsedRealtime(),
                    System.currentTimeMillis());
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Invalid playback progress transition was ignored");
        }
    }

    private static Thread newProgressThread(Runnable action) {
        return new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            action.run();
        }, "PlainPodPlaybackProgress");
    }
}
