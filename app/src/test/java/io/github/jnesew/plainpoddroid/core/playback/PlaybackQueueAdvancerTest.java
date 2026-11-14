package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.download.DownloadLookup;
import io.github.jnesew.plainpoddroid.core.download.VerifiedDownloadStore;
import io.github.jnesew.plainpoddroid.core.queue.*;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackQueueAdvancerTest {
    private static final String A = "episode-" + "a".repeat(64);
    private static final String B = "episode-" + "b".repeat(64);
    private static final String C = "episode-" + "c".repeat(64);
    private final List<Runnable> work = new ArrayList<>(), callbacks = new ArrayList<>();
    private final List<PlaybackSource> started = new ArrayList<>();
    private final Store queue = new Store();
    private boolean offline, downloadFirst, complete = true, played;
    private Runnable duringLookup = () -> { };
    private final PlaybackRequestResolver resolver = new PlaybackRequestResolver(new PlaybackPrivacyPolicy() {
        public boolean isNeverStreamAudioEnabled() { return offline; }
        public boolean isDownloadBeforePlayingEnabled() { return downloadFirst; }
    }, new VerifiedDownloadStore() {
        public DownloadLookup lookup(String id) {
            duringLookup.run();
            return complete ? DownloadLookup.complete(new File("/tmp/queue-test-audio"))
                    : DownloadLookup.withoutFile(DownloadLookup.State.ABSENT);
        }
        public boolean isVerifiedCompleteUri(String uri) { return complete; }
    }, id -> new RemoteEpisode(id, URI.create("https://media.example/audio.mp3"),
            "audio/mpeg", "Episode", "Show", 1200L));
    private final PlaybackQueueAdvancer advancer = new PlaybackQueueAdvancer(
            queue, resolver, work::add, callbacks::add);

    private void finish() { work.remove(0).run(); callbacks.remove(0).run(); }
    @Test public void completedQueueItemIsConsumedAndNextVerifiedDownloadResumesOffline() {
        offline = true; queue.ids.addAll(List.of(A, B, C));
        advancer.advance(A, started::add, () -> { });
        assertEquals(0, started.size());
        finish();
        assertEquals(List.of(B, C), queue.ids);
        assertEquals(B, started.get(0).getMediaId());
        assertEquals(PlaybackSource.Kind.VERIFIED_LOCAL, started.get(0).getKind());
        assertEquals(1200L, started.get(0).getPlaybackPositionMillis());
    }
    @Test public void unqueuedEpisodeContinuesAtQueueHeadAndPlayedHeadRestarts() {
        played = true; queue.ids.add(B);
        advancer.advance(A, started::add, () -> { }); finish();
        assertEquals(List.of(B), queue.ids);
        assertEquals(0L, started.get(0).getPlaybackPositionMillis());
    }
    @Test public void missingOfflineHeadStopsWithoutSkippingToALaterDownload() {
        offline = true; complete = false; queue.ids.addAll(List.of(A, B, C));
        advancer.advance(A, started::add, () -> { }); finish();
        assertEquals(List.of(B, C), queue.ids); assertTrue(started.isEmpty());
    }
    @Test public void downloadFirstHeadUsesCompleteDownloadPreparationRoute() {
        downloadFirst = true; complete = false; queue.ids.add(B);
        advancer.advance(A, started::add, () -> { }); finish();
        assertEquals(PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL, started.get(0).getKind());
        assertEquals(DownloadPlaybackUri.SCHEME, started.get(0).getUri().getScheme());
    }
    @Test public void ordinaryOnlineHeadCanStreamAndEmptyQueueStops() {
        complete = false; queue.ids.add(B);
        advancer.advance(A, started::add, () -> { }); finish();
        assertEquals(PlaybackSource.Kind.REMOTE_HTTPS, started.get(0).getKind());
        started.clear(); queue.ids.clear();
        advancer.advance(B, started::add, () -> { }); finish(); assertTrue(started.isEmpty());
    }
    @Test public void cancelBeforeWorkLeavesQueueAndPlaybackUntouched() {
        queue.ids.addAll(List.of(A, B)); advancer.advance(A, started::add, () -> { }); advancer.cancel();
        work.remove(0).run(); assertTrue(callbacks.isEmpty());
        assertEquals(List.of(A, B), queue.ids); assertTrue(started.isEmpty());
    }
    @Test public void cancelWhileResolvingOrBeforeDeliveryCannotRevivePlayback() {
        queue.ids.addAll(List.of(A, B)); duringLookup = advancer::cancel;
        advancer.advance(A, started::add, () -> { }); finish(); assertTrue(started.isEmpty());
        duringLookup = () -> { }; advancer.advance(A, started::add, () -> { });
        work.remove(0).run(); advancer.cancel(); callbacks.remove(0).run(); assertTrue(started.isEmpty());
    }
    @Test public void duplicateEndEventsShareOneAttemptAndOldDeliveryCannotStartANewerRequest() {
        queue.ids.addAll(List.of(A, B)); advancer.advance(A, started::add, () -> { });
        advancer.advance(A, started::add, () -> { }); assertEquals(1, work.size());
        work.remove(0).run(); advancer.cancel();
        advancer.advance(A, started::add, () -> { }); callbacks.remove(0).run();
        assertTrue(started.isEmpty()); finish(); assertEquals(1, started.size());
    }
    @Test public void changedHeadDuringResolutionCannotStartTheOldHead() {
        queue.ids.addAll(List.of(A, B, C));
        duringLookup = () -> { queue.ids.remove(B); };
        advancer.advance(A, started::add, () -> { }); finish();
        assertTrue(started.isEmpty()); assertEquals(List.of(C), queue.ids);
    }
    @Test public void queueStorageFailureStopsAndDoesNotStartPlayback() {
        queue.fail = true; advancer.advance(A, started::add, () -> { }); finish(); assertTrue(started.isEmpty());
    }
    @Test public void rejectedWorkerDoesNotLeaveContinuationStuck() {
        Executor reject = task -> { throw new java.util.concurrent.RejectedExecutionException(); };
        var rejected = new PlaybackQueueAdvancer(queue, resolver, reject, callbacks::add);
        rejected.advance(A, started::add, () -> { }); rejected.advance(A, started::add, () -> { });
        assertTrue(started.isEmpty());
    }
    private final class Store implements EpisodeQueueStore {
        final List<String> ids = new ArrayList<>(); boolean fail;
        public List<EpisodeQueueEntry> listQueue() {
            var entries = new ArrayList<EpisodeQueueEntry>();
            for (int i = 0; i < ids.size(); i++) entries.add(new EpisodeQueueEntry(
                    ids.get(i), "Episode", "Show", 5000L, 1200L, played, complete, false, i));
            return entries;
        }
        public EpisodeQueueMutation remove(String id) throws EpisodeQueueException {
            if (fail) throw new EpisodeQueueException(EpisodeQueueException.Reason.STORAGE_FAILURE, "Fixture");
            if (!ids.remove(id)) throw new EpisodeQueueException(EpisodeQueueException.Reason.NOT_QUEUED, "Fixture");
            return EpisodeQueueMutation.REMOVED;
        }
        public EpisodeQueueMutation append(String id) { throw new AssertionError(); }
        public EpisodeQueueMutation playNext(String id) { throw new AssertionError(); }
        public EpisodeQueueMutation move(String id, int to) { throw new AssertionError(); }
    }
}
