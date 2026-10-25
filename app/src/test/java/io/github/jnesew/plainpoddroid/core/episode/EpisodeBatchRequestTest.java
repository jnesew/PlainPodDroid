package io.github.jnesew.plainpoddroid.core.episode;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public final class EpisodeBatchRequestTest {
    private static String id(char c) { return "episode-" + String.valueOf(c).repeat(64); }
    @Test public void selectionIsAnImmutableDeduplicatedSnapshotInVisibleOrder() {
        var source=new ArrayList<>(List.of(id('b'),id('a'),id('b')));
        var selection=new EpisodeBatchRequest(source);source.clear();
        assertEquals(List.of(id('b'),id('a')),selection.mediaIds());
        assertThrows(UnsupportedOperationException.class,()->selection.mediaIds().clear());
    }
    @Test public void rejectsEmptyOversizedAndExternalIdentitiesBeforeWork() {
        assertThrows(IllegalArgumentException.class,()->new EpisodeBatchRequest(List.of()));
        assertThrows(IllegalArgumentException.class,()->new EpisodeBatchRequest(Collections.nCopies(101,id('a'))));
        for(String value:List.of("https://pod.example/private", "episode-../file",id('A'),""))
            assertThrows(IllegalArgumentException.class,()->new EpisodeBatchRequest(List.of(value)));
        assertThrows(IllegalArgumentException.class,()->new EpisodeBatchRequest(Arrays.asList(id('a'),null)));
        assertEquals(1,new EpisodeBatchRequest(Collections.nCopies(100,id('a'))).mediaIds().size());
    }
    @Test public void backlogScopeRejectsUnboundedOrInvalidConfirmation() {
        assertThrows(IllegalArgumentException.class,()->new PodcastPlayedBatch(0,1,0,1));
        assertThrows(IllegalArgumentException.class,()->new PodcastPlayedBatch(1,0,0,1));
        assertThrows(IllegalArgumentException.class,()->new PodcastPlayedBatch(1,1,-1,1));
        assertThrows(IllegalArgumentException.class,()->new PodcastPlayedBatch(1,1,0,Integer.MAX_VALUE));
        assertEquals(0,new PodcastPlayedBatch(1,1,0,0).count());
    }
}
