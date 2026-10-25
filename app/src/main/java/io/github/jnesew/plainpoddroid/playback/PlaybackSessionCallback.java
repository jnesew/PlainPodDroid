package io.github.jnesew.plainpoddroid.playback;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import android.os.Bundle;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import io.github.jnesew.plainpoddroid.core.playback.ControllerAccessPolicy;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackBatchResolution;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackDisplayMetadata;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackSource;
import io.github.jnesew.plainpoddroid.core.playback.SleepTimerOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@UnstableApi
final class PlaybackSessionCallback implements MediaSession.Callback {
    interface PlaybackBoundary {
        boolean enforceCurrentPlaylist();
    }

    interface SleepTimerControl {
        SessionResult arm(SleepTimerOption option);

        SessionResult cancel();

        SessionResult getStatus();
    }

    private final int applicationUid;
    private final PlaybackResolutionWorker resolver;
    private final PlaybackBoundary playbackBoundary;
    private final SleepTimerControl sleepTimerControl;

    PlaybackSessionCallback(
            int applicationUid,
            PlaybackResolutionWorker resolver,
            PlaybackBoundary playbackBoundary,
            SleepTimerControl sleepTimerControl) {
        this.applicationUid = applicationUid;
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.playbackBoundary = Objects.requireNonNull(playbackBoundary, "playbackBoundary");
        this.sleepTimerControl = Objects.requireNonNull(sleepTimerControl, "sleepTimerControl");
    }

    @Override
    public MediaSession.ConnectionResult onConnect(
            MediaSession session,
            MediaSession.ControllerInfo controller) {
        if (!ControllerAccessPolicy.mayConnect(controller.isTrusted())) {
            return MediaSession.ConnectionResult.reject();
        }
        MediaSession.ConnectionResult.AcceptedResultBuilder result =
                new MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller);
        if (controller.getUid() == applicationUid) {
            SessionCommands applicationCommands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(PlaybackSleepTimerCommands.armCommand())
                            .add(PlaybackSleepTimerCommands.cancelCommand())
                            .add(PlaybackSleepTimerCommands.getStatusCommand())
                            .build();
            result.setAvailableSessionCommands(applicationCommands);
        } else {
            Player.Commands externalControllerCommands =
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                            .remove(Player.COMMAND_SET_MEDIA_ITEM)
                            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                            .remove(Player.COMMAND_SET_PLAYLIST_METADATA)
                            .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
                            .build();
            result.setAvailablePlayerCommands(externalControllerCommands);
        }
        return result.build();
    }

    @Override
    public ListenableFuture<SessionResult> onCustomCommand(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            SessionCommand customCommand,
            Bundle args) {
        if (controller.getUid() != applicationUid) {
            return Futures.immediateFuture(
                    new SessionResult(SessionError.ERROR_PERMISSION_DENIED));
        }
        if (PlaybackSleepTimerCommands.isArmCommand(customCommand)) {
            try {
                return Futures.immediateFuture(sleepTimerControl.arm(
                        PlaybackSleepTimerCommands.readArmOption(args)));
            } catch (RuntimeException exception) {
                return Futures.immediateFuture(
                        new SessionResult(SessionError.ERROR_BAD_VALUE));
            }
        }
        if (PlaybackSleepTimerCommands.isCancelCommand(customCommand)) {
            return Futures.immediateFuture(sleepTimerControl.cancel());
        }
        if (PlaybackSleepTimerCommands.isGetStatusCommand(customCommand)) {
            return Futures.immediateFuture(sleepTimerControl.getStatus());
        }
        return Futures.immediateFuture(
                new SessionResult(SessionError.ERROR_NOT_SUPPORTED));
    }

    @Override
    public ListenableFuture<List<MediaItem>> onAddMediaItems(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            List<MediaItem> mediaItems) {
        if (!ControllerAccessPolicy.maySetMediaItems(
                controller.isTrusted(),
                controller.getUid(),
                applicationUid)) {
            return Futures.immediateFailedFuture(
                    new SecurityException("Controller may not provide media items"));
        }

        if(mediaItems.isEmpty()||mediaItems.size()>io.github.jnesew.plainpoddroid.core.security.SecurityLimits.MAX_QUEUE_ENTRIES)
            return Futures.immediateFailedFuture(new IllegalArgumentException("Invalid playback request."));
        List<String> mediaIds = new ArrayList<>(mediaItems.size());
        for (MediaItem item : mediaItems) {
            mediaIds.add(item.mediaId);
        }
        return Futures.transform(resolver.resolve(mediaIds),PlaybackSessionCallback::resolvedMediaItems,
                com.google.common.util.concurrent.MoreExecutors.directExecutor());
    }

    private static List<MediaItem> resolvedMediaItems(PlaybackBatchResolution resolution){
        if(!resolution.isAllowed())throw new IllegalArgumentException(
                "Playback request blocked: " + resolution.getBlockReason());
        List<MediaItem> resolvedItems = new ArrayList<>(resolution.getSources().size());
        for (PlaybackSource source : resolution.getSources()) {
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(PlaybackDisplayMetadata.notificationTitle(source.getTitle()))
                    .setArtist(PlaybackDisplayMetadata.notificationSubtitle(source.getAuthor()))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build();
            resolvedItems.add(new MediaItem.Builder()
                    .setMediaId(source.getMediaId())
                    .setUri(source.getUri().toString())
                    .setMimeType(source.getMimeType())
                    .setMediaMetadata(metadata)
                    .build());
        }
        return resolvedItems;
    }

    @Override
    public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            boolean isForPlayback) {
        return Futures.immediateFailedFuture(
                new UnsupportedOperationException("Playback resumption state is unavailable"));
    }

    @Override
    @SuppressWarnings("deprecation") // Required backstop for notification/headset play on Media3 1.11.
    public int onPlayerCommandRequest(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            int playerCommand) {
        if ((playerCommand == Player.COMMAND_PLAY_PAUSE
                || playerCommand == Player.COMMAND_PREPARE)
                && !playbackBoundary.enforceCurrentPlaylist()) {
            return SessionResult.RESULT_ERROR_INVALID_STATE;
        }
        return SessionResult.RESULT_SUCCESS;
    }
}
