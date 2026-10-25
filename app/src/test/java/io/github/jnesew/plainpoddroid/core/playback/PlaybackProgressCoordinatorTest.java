package io.github.jnesew.plainpoddroid.core.playback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PlaybackProgressCoordinatorTest {
    private static final String FIRST_ID = "episode-" + "a".repeat(64);
    private static final String SECOND_ID = "episode-" + "b".repeat(64);
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    public void periodicWritesOccurAtMostOncePerFifteenSeconds() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);
        coordinator.sample(FIRST_ID, 200L, 600_000L, 14_999L, 2_000L);
        coordinator.sample(FIRST_ID, 300L, 600_000L, 15_000L, 3_000L);

        assertEquals(2, store.updates.size());
        assertEquals(100L, store.updates.get(0).getPositionMillis());
        assertEquals(300L, store.updates.get(1).getPositionMillis());
    }

    @Test
    public void forcedPauseOrStopBypassesIntervalButDeduplicates() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);
        coordinator.force(FIRST_ID, 200L, 600_000L, 1_000L, 2_000L);
        coordinator.force(FIRST_ID, 200L, 600_000L, 2_000L, 3_000L);

        assertEquals(2, store.updates.size());
        assertEquals(200L, store.updates.get(1).getPositionMillis());
    }

    @Test
    public void episodeChangeFlushesOldSampleBeforeNewEpisode() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);
        coordinator.sample(FIRST_ID, 200L, 600_000L, 1_000L, 2_000L);
        coordinator.sample(SECOND_ID, 50L, 600_000L, 2_000L, 3_000L);

        assertEquals(3, store.updates.size());
        assertEquals(FIRST_ID, store.updates.get(0).getMediaId());
        assertEquals(FIRST_ID, store.updates.get(1).getMediaId());
        assertEquals(200L, store.updates.get(1).getPositionMillis());
        assertEquals(SECOND_ID, store.updates.get(2).getMediaId());
    }

    @Test
    public void discontinuityFlushUsesPreciseOldPositionAndRetainedDuration() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 100L, 400_000L, 0L, 1_000L);
        coordinator.forceCurrentPosition(FIRST_ID, 380_000L, 1_000L, 2_000L);

        assertEquals(2, store.updates.size());
        assertEquals(380_000L, store.updates.get(1).getPositionMillis());
        assertTrue(store.updates.get(1).shouldMarkPlayed());
    }

    @Test
    public void discontinuityForUntrackedEpisodeCannotReplaceCurrentState() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);
        coordinator.forceCurrentPosition(SECOND_ID, 200L, 1_000L, 2_000L);
        coordinator.force(FIRST_ID, 300L, 600_000L, 2_000L, 3_000L);

        assertEquals(2, store.updates.size());
        assertEquals(FIRST_ID, store.updates.get(1).getMediaId());
        assertEquals(300L, store.updates.get(1).getPositionMillis());
    }

    @Test
    public void completionRemainsStickyWithinCurrentEpisode() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 380_000L, 400_000L, 0L, 1_000L);
        coordinator.force(FIRST_ID, 10_000L, 400_000L, 1_000L, 2_000L);

        assertEquals(2, store.updates.size());
        assertTrue(store.updates.get(0).shouldMarkPlayed());
        assertTrue(store.updates.get(1).shouldMarkPlayed());
    }

    @Test
    public void changingToManualModeDropsUnsubmittedAutomaticCompletion() {
        RecordingStore store = new RecordingStore();
        AtomicReference<CompletionMode> mode = new AtomicReference<>(CompletionMode.NEAR_END);
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator(
                store, DIRECT_EXECUTOR, () -> {}, mode::get);

        coordinator.sample(FIRST_ID, 100_000L, 400_000L, 0L, 1_000L);
        coordinator.sample(FIRST_ID, 380_000L, 400_000L, 1_000L, 2_000L);
        mode.set(CompletionMode.MANUAL_ONLY);
        coordinator.force(FIRST_ID, 390_000L, 400_000L, 2_000L, 3_000L);

        assertEquals(2, store.updates.size());
        assertFalse(store.updates.get(1).shouldMarkPlayed());
    }

    @Test
    public void closeFlushesPendingProgressOnceAndIgnoresLaterSamples() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);
        coordinator.sample(FIRST_ID, 200L, 600_000L, 1_000L, 2_000L);
        coordinator.close(2_000L, 3_000L);
        coordinator.close(3_000L, 4_000L);
        coordinator.sample(FIRST_ID, 300L, 600_000L, 20_000L, 5_000L);

        assertEquals(2, store.updates.size());
        assertEquals(200L, store.updates.get(1).getPositionMillis());
    }

    @Test
    public void failuresExposeNoMediaOrStorageDetailsToListener() {
        AtomicInteger failures = new AtomicInteger();
        PlaybackProgressStore failingStore = update -> {
            throw new IOException("sensitive " + update.getMediaId());
        };
        PlaybackProgressCoordinator coordinator = new PlaybackProgressCoordinator(
                failingStore,
                DIRECT_EXECUTOR,
                failures::incrementAndGet);

        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);

        assertEquals(1, failures.get());
    }

    @Test
    public void invalidSampleTimesAreRejectedBeforeSubmission() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.sample(FIRST_ID, 0L, 1L, -1L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.force(FIRST_ID, 0L, 1L, 0L, -1L));
        assertTrue(store.updates.isEmpty());
    }

    @Test
    public void invalidMediaIdDoesNotPoisonLaterSamples() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.sample("https://example.test/audio", 0L, 1L, 0L, 0L));
        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);

        assertEquals(1, store.updates.size());
        assertEquals(FIRST_ID, store.updates.get(0).getMediaId());
    }

    @Test
    public void normalProgressNeverRequestsPlayedStateToBeCleared() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressCoordinator coordinator = coordinator(store, new AtomicInteger());

        coordinator.sample(FIRST_ID, 100L, 600_000L, 0L, 1_000L);

        assertEquals(1, store.updates.size());
        assertFalse(store.updates.get(0).shouldMarkPlayed());
    }

    @Test public void seeksAndCompletionChangesDoNotInventListeningEvents() {
        RecordingStore store=new RecordingStore();var c=coordinator(store,new AtomicInteger());
        c.sample(FIRST_ID,100,1000,0,100);
        c.force(FIRST_ID,900,1000,1,101);
        c.forceCurrentPosition(FIRST_ID,500,2,102);
        c.close(3,103);
        assertTrue(store.listening.isEmpty());
    }

    @Test public void listeningEventsAreThrottledButEpisodeChangesAreImmediate() {
        RecordingStore store=new RecordingStore();var c=coordinator(store,new AtomicInteger());
        c.recordListening(FIRST_ID,0,100);
        c.recordListening(FIRST_ID,14999,101);
        c.recordListening(FIRST_ID,15000,102);
        c.recordListening(SECOND_ID,15001,103);
        c.close(15002,104);c.recordListening(FIRST_ID,15003,105);
        assertEquals(List.of(FIRST_ID+":100",FIRST_ID+":102",SECOND_ID+":103"),store.listening);
    }

    @Test public void listeningEventsAreValidatedAndFailuresStayTyped() {
        RecordingStore store=new RecordingStore();AtomicInteger failures=new AtomicInteger();
        var c=coordinator(store,failures);
        assertThrows(IllegalArgumentException.class,()->c.recordListening("https://private.example/token",0,1));
        assertThrows(IllegalArgumentException.class,()->c.recordListening(FIRST_ID,-1,1));
        assertTrue(store.listening.isEmpty());
        PlaybackProgressStore broken=new PlaybackProgressStore(){
            public void savePlaybackProgress(PlaybackProgressUpdate update) { }
            public void recordListening(String id,long time)throws IOException{throw new IOException("private detail");}
        };
        coordinator(broken,failures).recordListening(FIRST_ID,0,1);
        assertEquals(1,failures.get());
    }

    private static PlaybackProgressCoordinator coordinator(
            PlaybackProgressStore store,
            AtomicInteger failures) {
        return new PlaybackProgressCoordinator(
                store,
                DIRECT_EXECUTOR,
                failures::incrementAndGet);
    }

    private static final class RecordingStore implements PlaybackProgressStore {
        private final List<PlaybackProgressUpdate> updates = new ArrayList<>();
        private final List<String> listening=new ArrayList<>();
        @Override public void recordListening(String id,long time){listening.add(id+":"+time);}


        @Override
        public void savePlaybackProgress(PlaybackProgressUpdate update) {
            updates.add(update);
        }
    }
}
