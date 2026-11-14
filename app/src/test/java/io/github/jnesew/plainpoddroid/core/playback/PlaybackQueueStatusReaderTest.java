package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.download.*;
import io.github.jnesew.plainpoddroid.core.queue.*;
import io.github.jnesew.plainpoddroid.core.catalog.PodcastContext;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.jnesew.plainpoddroid.core.playback.PlaybackQueueStatusReader.Availability.*;

public final class PlaybackQueueStatusReaderTest {
    private static final String A = "episode-" + "a".repeat(64), B = "episode-" + "b".repeat(64);
    private final List<EpisodeQueueEntry> entries = new ArrayList<>();
    private final Map<String, DownloadLookup.State> files = new HashMap<>();
    private boolean offline, downloadFirst, storageError;
    private final EpisodeQueueStore queue = new EpisodeQueueStore() {
        public List<EpisodeQueueEntry> listQueue() { return List.copyOf(entries); }
        public EpisodeQueueMutation append(String id) { throw new AssertionError("Status must be read-only"); }
        public EpisodeQueueMutation remove(String id) { throw new AssertionError("Status must be read-only"); }
        public EpisodeQueueMutation playNext(String id) { throw new AssertionError("Status must be read-only"); }
        public EpisodeQueueMutation move(String id, int position) { throw new AssertionError("Status must be read-only"); }
    };
    private final PlaybackQueueStatusReader reader = new PlaybackQueueStatusReader(queue, new VerifiedDownloadStore() {
        public DownloadLookup lookup(String id) throws IOException {
            if (storageError) throw new IOException("Fixture");
            var state = files.getOrDefault(id, DownloadLookup.State.ABSENT);
            return state == DownloadLookup.State.COMPLETE ? DownloadLookup.complete(new File("/tmp/synthetic-queue-audio"))
                    : DownloadLookup.withoutFile(state);
        }
        public boolean isVerifiedCompleteUri(String uri) { throw new AssertionError(); }
    }, new PlaybackPrivacyPolicy() {
        public boolean isNeverStreamAudioEnabled() { return offline; }
        public boolean isDownloadBeforePlayingEnabled() { return downloadFirst; }
    });
    private EpisodeQueueEntry entry(String id, int position, boolean subscribed) {
        // Deliberately claim downloaded=true in the catalog; actual file evidence must win.
        return new EpisodeQueueEntry(id, id.equals(A) ? "Current" : "Next", "Fixture", 5000L,
                0L, false, true, false, position, new PodcastContext(1, "Fixture", null, subscribed));
    }
    @Test public void excludesCurrentWhereverQueuedAndUsesTheRemainingHead() throws Exception {
        entries.add(entry(B,0,true)); entries.add(entry(A,1,true));
        var result = reader.read(A,false);
        assertEquals(1,result.remaining()); assertEquals("Next",result.nextTitle()); assertEquals(STREAM,result.availability());
        assertEquals(2,entries.size());
    }
    @Test public void offlineOverridesDownloadFirstAndRejectsAbsentPartialInvalidFiles() throws Exception {
        entries.add(entry(B,0,true)); offline = true; downloadFirst = true;
        for (var state : List.of(DownloadLookup.State.ABSENT, DownloadLookup.State.PARTIAL, DownloadLookup.State.INVALID)) {
            files.put(B,state); var result = reader.read(A,true);
            assertEquals(OFFLINE_BLOCKED,result.availability()); assertFalse(result.preparing());
        }
        files.put(B,DownloadLookup.State.COMPLETE); assertEquals(DOWNLOADED,reader.read(A,false).availability());
    }
    @Test public void preparationStatusOnlyDescribesABufferingMissingCurrentDownload() throws Exception {
        entries.add(entry(B,0,true)); downloadFirst = true;
        assertEquals(DOWNLOAD_FIRST,reader.read(A,true).availability()); assertTrue(reader.read(A,true).preparing());
        assertFalse(reader.read(A,false).preparing()); assertFalse(reader.read("",true).preparing());
        files.put(A,DownloadLookup.State.COMPLETE); assertFalse(reader.read(A,true).preparing());
        downloadFirst = false; files.clear(); assertEquals(STREAM,reader.read(A,true).availability());
        assertFalse(reader.read(A,true).preparing());
    }
    @Test public void archivedEpisodeNeedsADownloadEvenOnline() throws Exception {
        entries.add(entry(B,0,false)); downloadFirst = true;
        assertEquals(UNAVAILABLE,reader.read(A,false).availability());
        files.put(B,DownloadLookup.State.COMPLETE); assertEquals(DOWNLOADED,reader.read(A,false).availability());
    }
    @Test public void emptyAndCurrentOnlyQueuesHaveNoRemainingHead() throws Exception {
        assertEquals(EMPTY,reader.read(A,false).availability()); entries.add(entry(A,0,true));
        assertEquals(0,reader.read(A,false).remaining()); assertEquals(EMPTY,reader.read(A,false).availability());
        assertEquals(1,reader.read("",false).remaining());
    }
    @Test public void unreadableDownloadIsNotAdvertisedAsReady() {
        entries.add(entry(B,0,true)); storageError = true;
        assertThrows(IOException.class,()->reader.read(A,false));
    }
}
