package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackActionsTest {
    private static final String ID = "episode-" + "b".repeat(64);
    private final FakePort port = new FakePort();
    private final PlaybackActions actions = new PlaybackActions(port);

    @Test public void playResumesOpaqueEpisodeAndCompletedEpisodeRestarts() {
        assertEquals(PlaybackActions.StartResult.STARTED,
                actions.playEpisode(episode(false, false), false, false));
        assertEquals(List.of("set:" + ID + ":60000", "prepare", "play"), port.commands);
        port.commands.clear();
        actions.playEpisode(episode(true, false), false, false);
        assertEquals("set:" + ID + ":0", port.commands.get(0));
    }

    @Test public void neverStreamRejectsUndownloadedWithoutAnyPlaybackOrDownloadCommand() {
        assertEquals(PlaybackActions.StartResult.DOWNLOAD_REQUIRED,
                actions.playEpisode(episode(false, false), false, true));
        assertTrue(port.commands.isEmpty());
        assertEquals(PlaybackActions.StartResult.STARTED,
                actions.playEpisode(episode(false, true), false, true));
    }

    @Test public void unavailableOrCompetingWorkCannotReplaceCurrentPlayback() {
        assertEquals(PlaybackActions.StartResult.UNAVAILABLE,
                actions.playEpisode(episode(false, true), true, false));
        port.connected = false;
        assertEquals(PlaybackActions.StartResult.UNAVAILABLE,
                actions.playEpisode(episode(false, true), false, false));
        assertTrue(port.commands.isEmpty());
    }

    @Test public void chapterChecksDurationAndPrivacyBeforeSeekingToItsStart() {
        assertEquals(PlaybackActions.StartResult.UNAVAILABLE,
                actions.playChapter(episode(false, true), 120_000, false, false));
        assertEquals(PlaybackActions.StartResult.UNAVAILABLE,
                actions.playChapter(episode(false, true), -1, false, false));
        assertEquals(PlaybackActions.StartResult.DOWNLOAD_REQUIRED,
                actions.playChapter(episode(false, false), 30_000, false, true));
        assertTrue(port.commands.isEmpty());
        actions.playChapter(episode(false, true), 30_000, false, true);
        assertEquals(List.of("set:" + ID + ":30000", "prepare", "play"), port.commands);
    }

    @Test public void togglePausesOrPreparesStoppedItemBeforeResume() {
        port.playing = true; actions.toggle();
        assertEquals(List.of("pause"), port.commands);
        port.commands.clear(); port.playing = false; port.idle = true;
        actions.toggle();
        assertEquals(List.of("prepare", "play"), port.commands);
    }

    @Test public void skipDelegatesOnlyAvailableRelativeSessionCommands() {
        actions.skipBack(); actions.skipForward();
        assertTrue(port.commands.isEmpty());
        port.back = true; actions.skipBack(); actions.skipForward();
        assertEquals(List.of("back"), port.commands);
        port.forward = true; actions.skipForward();
        assertEquals(List.of("back", "forward"), port.commands);
    }

    @Test public void missingCurrentItemMakesTransportControlsInert() {
        port.item = false; port.back = true; port.forward = true;
        actions.toggle(); actions.stop(); actions.skipBack(); actions.skipForward();
        assertTrue(port.commands.isEmpty());
    }

    @Test public void rejectedStartStopsBeforePrepareAndPlay() {
        port.reject = true;
        assertEquals(PlaybackActions.StartResult.FAILED,
                actions.playEpisode(episode(false, true), false, false));
        assertTrue(port.commands.isEmpty());
    }

    @Test public void scrubberRequiresCurrentEpisodeAvailableCommandAndKnownDuration() {
        assertFalse(actions.seekCurrent(ID, 30_000));
        port.seek = true;
        assertFalse(actions.seekCurrent("episode-" + "a".repeat(64), 30_000));
        assertFalse(actions.seekCurrent(ID, -1));
        assertFalse(actions.seekCurrent(ID, 120_001));
        port.duration = -1; assertFalse(actions.seekCurrent(ID, 0));
        port.duration = 120_000;
        assertTrue(actions.seekCurrent(ID, 30_000));
        assertEquals(List.of("seek:30000"), port.commands);
    }

    @Test public void scrubberCannotReplaceOrStartMediaAndRejectsDisconnectedOrMissingItem() {
        port.seek = true; port.connected = false;
        assertFalse(actions.seekCurrent(ID, 0));
        port.connected = true; port.item = false;
        assertFalse(actions.seekCurrent(ID, 0));
        port.item = true;
        assertFalse(actions.seekCurrent("https://example.com/audio", 0));
        assertTrue(port.commands.isEmpty());
        assertTrue(actions.seekCurrent(ID, 120_000));
        assertEquals(List.of("seek:120000"), port.commands);
        for (boolean playing : new boolean[] {false, true}) {
            port.commands.clear(); port.playing = playing;
            assertTrue(actions.seekCurrent(ID, 30_000));
            assertEquals(List.of("seek:30000"), port.commands);
            assertEquals(playing, port.playing);
        }
    }

    private static EpisodeDetails episode(boolean played, boolean downloaded) {
        return new EpisodeDetails(ID, "Episode", "", "", null, "", 120_000,
                60_000, played, false, false, downloaded, false, false);
    }

    private static final class FakePort implements PlaybackActions.Port {
        final List<String> commands = new ArrayList<>();
        boolean connected = true, item = true, playing, idle, back, forward, reject, seek;
        long duration = 120_000;
        public boolean isConnected() { return connected; }
        public boolean hasCurrentItem() { return item; }
        public boolean isPlaying() { return playing; }
        public boolean isIdle() { return idle; }
        public boolean canSkipBack() { return back; }
        public boolean canSkipForward() { return forward; }
        public boolean canSeekCurrent() { return seek; }
        public String currentMediaId() { return ID; }
        public long durationMillis() { return duration; }
        public void setEpisode(String id, long position) {
            if (reject) throw new IllegalStateException("Rejected");
            commands.add("set:" + id + ":" + position);
        }
        public void prepare() { commands.add("prepare"); }
        public void play() { commands.add("play"); }
        public void pause() { commands.add("pause"); }
        public void stop() { commands.add("stop"); }
        public void skipBack() { commands.add("back"); }
        public void skipForward() { commands.add("forward"); }
        public void seekTo(long position) { commands.add("seek:"+position); }
    }
}
