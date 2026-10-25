package io.github.jnesew.plainpoddroid.playback;

import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** Session next/previous buttons use podcast skip intervals, even for a single item. */
@UnstableApi
final class PodcastSessionPlayer extends ForwardingSimpleBasePlayer {
    PodcastSessionPlayer(Player player) { super(player); }

    @Override protected State getState() {
        State state = super.getState();
        Player.Commands.Builder commands = state.availableCommands.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM);
        if (state.availableCommands.contains(Player.COMMAND_SEEK_FORWARD)) {
            commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM);
        }
        if (state.availableCommands.contains(Player.COMMAND_SEEK_BACK)) {
            commands.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM);
        }
        return state.buildUpon().setAvailableCommands(commands.build()).build();
    }

    @Override protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs,
            int seekCommand) {
        switch (seekCommand) {
            case Player.COMMAND_SEEK_TO_NEXT:
            case Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM:
                getPlayer().seekForward();
                return Futures.immediateVoidFuture();
            case Player.COMMAND_SEEK_TO_PREVIOUS:
            case Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
                getPlayer().seekBack();
                return Futures.immediateVoidFuture();
            default:
                return super.handleSeek(mediaItemIndex, positionMs, seekCommand);
        }
    }
}
