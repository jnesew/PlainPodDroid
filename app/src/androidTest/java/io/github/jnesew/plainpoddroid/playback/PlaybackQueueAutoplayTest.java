package io.github.jnesew.plainpoddroid.playback;

import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import io.github.jnesew.plainpoddroid.core.download.*;
import io.github.jnesew.plainpoddroid.core.playback.*;
import io.github.jnesew.plainpoddroid.core.queue.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Real ExoPlayer end callbacks wired to the service listener, using only synthetic local WAV. */
@UnstableApi
@SuppressWarnings("deprecation")
public final class PlaybackQueueAutoplayTest extends InstrumentationTestCase {
    private static final String A = "episode-" + "a".repeat(64), B = "episode-" + "b".repeat(64);
    private Path directory;
    private AppPrivateDownloadStore store;
    private ExecutorService worker, downloads;
    private ExoPlayer player;
    private PlaybackQueueAdvancer advancer;
    private PlaybackService service;
    private Player.Listener serviceListener;
    private final List<String> queue = new ArrayList<>(List.of(A, B));
    private final CountDownLatch nextPlaying = new CountDownLatch(1), ended = new CountDownLatch(1);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Throwable error;
    private int preparations;

    public void testVerifiedDownloadsAutoplayAfterNaturalEndOffline() throws Exception {
        start(false, false, false);
        assertTrue("Next downloaded episode never started: " + error, nextPlaying.await(10, TimeUnit.SECONDS));
        assertEquals(List.of(B), queueSnapshot());
    }
    public void testDownloadBeforePlayingPreparesNextThenAutoplays() throws Exception {
        start(true, false, false);
        assertTrue("Prepared next episode never started: " + error, nextPlaying.await(10, TimeUnit.SECONDS));
        assertEquals(1, preparations); assertTrue(store.lookup(B).getState() == DownloadLookup.State.COMPLETE);
        assertEquals(List.of(B), queueSnapshot());
    }
    public void testMissingOfflineHeadStopsWithoutStreaming() throws Exception {
        start(false, true, false);
        assertTrue(ended.await(10, TimeUnit.SECONDS)); drainWorker();
        assertFalse(nextPlaying.await(300, TimeUnit.MILLISECONDS));
        assertEquals(List.of(B), queueSnapshot()); assertEquals(0, preparations);
        assertNull(error);
    }
    public void testEndOfEpisodeSleepTimerPreventsAutoplay() throws Exception {
        start(false, false, true);
        assertTrue(ended.await(10, TimeUnit.SECONDS)); drainWorker();
        assertFalse(nextPlaying.await(300, TimeUnit.MILLISECONDS)); assertNull(error);
        assertEquals(List.of(A, B), queueSnapshot());
    }
    public void testTimedSleepTimerStaysArmedAcrossQueueBoundary() throws Exception {
        start(false, false, false);
        getInstrumentation().runOnMainSync(() -> {
            try { field("sleepTimerState", SleepTimerState.arm(SleepTimerOption.MINUTES_15, android.os.SystemClock.elapsedRealtime())); }
            catch (Exception exception) { throw new AssertionError(exception); }
        });
        assertTrue(nextPlaying.await(10, TimeUnit.SECONDS));
        getInstrumentation().runOnMainSync(() -> {
            try {
                var field = PlaybackService.class.getDeclaredField("sleepTimerState"); field.setAccessible(true);
                assertTrue(((SleepTimerState) field.get(service)).isActive());
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }
    public void testPauseBeforeEndDoesNotConsumeQueueOrAutoplay() throws Exception {
        start(false, false, false);
        getInstrumentation().runOnMainSync(player::pause);
        assertFalse(nextPlaying.await(500, TimeUnit.MILLISECONDS));
        assertEquals(List.of(A, B), queueSnapshot()); assertNull(error);
    }
    private void start(boolean downloadFirst, boolean missing, boolean sleep) throws Exception {
        directory = Files.createTempDirectory(getInstrumentation().getTargetContext().getCacheDir().toPath(), "queue-audio-");
        store = new AppPrivateDownloadStore(directory.toFile());
        worker = Executors.newSingleThreadExecutor(); downloads = Executors.newSingleThreadExecutor();
        write(A); if (!downloadFirst && !missing) write(B);
        var policy = new PlaybackPrivacyPolicy() {
            public boolean isNeverStreamAudioEnabled() { return !downloadFirst; }
            public boolean isDownloadBeforePlayingEnabled() { return downloadFirst; }
        };
        var resolver = new PlaybackRequestResolver(policy, store, id -> new RemoteEpisode(id,
                URI.create("https://media.example/fixture.wav"), "audio/wav", "Fixture", "Fixture", 0L));
        var local = new VerifiedDownloadDataSource.Factory(store);
        EpisodeDownloadActions actions = new EpisodeDownloadActions() {
            public EpisodeDownloadResult download(String id, BooleanSupplier cancelled) {
                try { preparations++; write(id); } catch (Exception exception) { throw new IllegalStateException(exception); }
                return null;
            }
            public void delete(String id) { throw new AssertionError(); }
            public long getStorageUsageBytes() { return 0L; }
        };
        // No remote data source exists in this fixture: a streaming fallback necessarily fails.
        DataSource.Factory source = new PlaybackRoutingDataSource.Factory(local, local,
                new DownloadBeforePlayingDataSource.Factory(new PlaybackDownloadPreparer(
                        store, actions, downloads, () -> false), local));
        getInstrumentation().runOnMainSync(() -> {
            try {
                service = new PlaybackService();
                player = new ExoPlayer.Builder(getInstrumentation().getTargetContext(),
                        new DefaultMediaSourceFactory(source)).build();
                advancer = new PlaybackQueueAdvancer(new QueueStore(), resolver, worker, task -> main.post(task));
                field("player", player); field("queueAdvancer", advancer);
                var listenerField = PlaybackService.class.getDeclaredField("progressListener");
                listenerField.setAccessible(true); serviceListener = (Player.Listener) listenerField.get(service);
                player.addListener(serviceListener);
                player.addListener(new Player.Listener() {
                    public void onIsPlayingChanged(boolean playing) {
                        if (playing && B.equals(player.getCurrentMediaItem().mediaId)) nextPlaying.countDown();
                    }
                    public void onPlaybackStateChanged(int state) { if (state == Player.STATE_ENDED) ended.countDown(); }
                    public void onPlayerError(androidx.media3.common.PlaybackException exception) { error = exception; ended.countDown(); }
                });
                if (sleep) {
                    field("sleepTimerState", SleepTimerState.arm(SleepTimerOption.END_OF_EPISODE, 0L));
                    player.setPauseAtEndOfMediaItems(true);
                }
                player.setMediaItem(new MediaItem.Builder().setMediaId(A)
                        .setUri(store.lookup(A).getCompleteFile().toURI().toString()).setMimeType("audio/wav").build());
                player.prepare(); player.play();
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }
    private void field(String name, Object value) throws Exception {
        var field = PlaybackService.class.getDeclaredField(name); field.setAccessible(true); field.set(service, value);
    }
    private void write(String id) throws Exception {
        int samples = 8000; // One second of silent, mono 16-bit PCM.
        var wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(wav.capacity() - 8).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000)
                .putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(samples * 2);
        try (var writer = store.begin(id, wav.capacity(), 1024 * 1024)) {
            writer.write(wav.array(), 0, wav.capacity()); writer.finish();
        }
    }
    private void drainWorker() throws Exception {
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS); getInstrumentation().waitForIdleSync();
    }
    private List<String> queueSnapshot() { synchronized (queue) { return List.copyOf(queue); } }
    @Override protected void tearDown() throws Exception {
        if (player != null) getInstrumentation().runOnMainSync(() -> {
            advancer.cancel(); player.removeListener(serviceListener); player.release();
        });
        if (worker != null) { worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS); }
        if (downloads != null) { downloads.shutdownNow(); downloads.awaitTermination(5, TimeUnit.SECONDS); }
        if (directory != null) try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
        }
        super.tearDown();
    }
    private final class QueueStore implements EpisodeQueueStore {
        public List<EpisodeQueueEntry> listQueue() {
            synchronized (queue) {
                var entries = new ArrayList<EpisodeQueueEntry>();
                for (int i = 0; i < queue.size(); i++) entries.add(new EpisodeQueueEntry(queue.get(i),
                        "Fixture", "Fixture", 1000L, 0L, false, true, false, i));
                return entries;
            }
        }
        public EpisodeQueueMutation remove(String id) throws EpisodeQueueException {
            synchronized (queue) {
                if (!queue.remove(id)) throw new EpisodeQueueException(EpisodeQueueException.Reason.NOT_QUEUED, "Fixture");
                return EpisodeQueueMutation.REMOVED;
            }
        }
        public EpisodeQueueMutation append(String id) { throw new AssertionError(); }
        public EpisodeQueueMutation playNext(String id) { throw new AssertionError(); }
        public EpisodeQueueMutation move(String id, int index) { throw new AssertionError(); }
    }
}
