package io.github.jnesew.plainpoddroid.playback;

import android.os.Looper;
import android.test.InstrumentationTestCase;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

@UnstableApi
@SuppressWarnings("deprecation")
public final class PodcastSessionPlayerTest extends InstrumentationTestCase {
    public void testNextPreviousUseLiveIntervalsWithoutChangingItemOrPausedState() {
        getInstrumentation().runOnMainSync(() -> {
            for (boolean single : new boolean[] {true, false}) {
                FakePlayer base = new FakePlayer(single);
                PodcastSessionPlayer session = new PodcastSessionPlayer(base);
                assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT));
                session.seekToNext();
                assertEquals(Player.COMMAND_SEEK_FORWARD, base.lastCommand);
                assertEquals(70_000L, base.getCurrentPosition());
                session.seekToPrevious();
                assertEquals(Player.COMMAND_SEEK_BACK, base.lastCommand);
                assertEquals(60_000L, base.getCurrentPosition());
                session.seekToNextMediaItem(); session.seekToPreviousMediaItem();
                assertEquals(80_000L, base.getCurrentPosition());
                assertEquals(0, session.getCurrentMediaItemIndex());
                assertFalse(session.getPlayWhenReady());
                base.update(base.state.buildUpon().setSeekForwardIncrementMs(5_000L)
                        .setSeekBackIncrementMs(20_000L).build());
                session.seekToNext(); session.seekToPrevious();
                assertEquals(65_000L, base.getCurrentPosition());
                session.seekTo(12_000L);
                assertEquals(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, base.lastCommand);
                assertEquals(12_000L, base.getCurrentPosition());
                session.release();
            }
        });
    }

    public void testUnavailableRelativeSeekCannotBecomeAnEnabledSkip() {
        getInstrumentation().runOnMainSync(() -> {
            FakePlayer base = new FakePlayer(true);
            PodcastSessionPlayer session = new PodcastSessionPlayer(base);
            base.update(base.state.buildUpon().setAvailableCommands(base.state.availableCommands
                    .buildUpon().remove(Player.COMMAND_SEEK_FORWARD)
                    .remove(Player.COMMAND_SEEK_BACK).build()).build());
            assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT));
            assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS));
            session.seekToNext(); session.seekToPrevious();
            assertEquals(-1, base.lastCommand);
            session.release();
        });
    }

    private static final class FakePlayer extends SimpleBasePlayer {
        private State state;
        private int lastCommand = -1;
        FakePlayer(boolean single) {
            super(Looper.getMainLooper());
            MediaItemData first = item("first"), second = item("second");
            state = new State.Builder().setAvailableCommands(new Player.Commands.Builder()
                    .addAllCommands().build()).setPlaylist(single ? List.of(first) : List.of(first, second))
                    .setCurrentMediaItemIndex(0).setContentPositionMs(40_000L)
                    .setPlaybackState(Player.STATE_READY)
                    .setSeekBackIncrementMs(10_000L).setSeekForwardIncrementMs(30_000L).build();
        }
        private static MediaItemData item(String id) {
            return new MediaItemData.Builder(id).setMediaItem(new MediaItem.Builder().setMediaId(id).build())
                    .setIsSeekable(true).setDurationUs(120_000_000L).build();
        }
        void update(State value) { state = value; invalidateState(); }
        @Override protected State getState() { return state; }
        @Override protected ListenableFuture<?> handleSeek(int index, long position, int command) {
            lastCommand = command;
            state = state.buildUpon().setContentPositionMs(position).build();
            return Futures.immediateVoidFuture();
        }
        @Override protected ListenableFuture<?> handleRelease() { return Futures.immediateVoidFuture(); }
    }
}
