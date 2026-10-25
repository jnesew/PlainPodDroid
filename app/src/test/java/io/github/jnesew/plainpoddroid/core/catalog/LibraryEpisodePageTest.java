package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LibraryEpisodePageTest {
    private static CatalogEpisodeSummary episode(PodcastContext parent) {
        return new CatalogEpisodeSummary("episode-"+"a".repeat(64),"Episode","",120000,0,
                true,true,false,false,false,"",parent);
    }
    @Test public void pageIsAnImmutableSnapshotWithRequiredParentContext() {
        var values=new ArrayList<CatalogEpisodeSummary>(); values.add(episode(new PodcastContext(1,"Podcast",null,false)));
        var page=new LibraryEpisodePage(0,values,false);values.clear();
        assertEquals(1,page.episodes().size());
        assertThrows(UnsupportedOperationException.class,()->page.episodes().clear());
        assertThrows(IllegalArgumentException.class,()->new LibraryEpisodePage(0,List.of(episode(null)),false));
    }
    @Test public void invalidOffsetsAndUnboundedPagesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new LibraryEpisodePage(-1,List.of(),false));
        assertThrows(IllegalArgumentException.class,()->new LibraryEpisodePage(SecurityLimits.MAX_GLOBAL_EPISODES,List.of(),true));
        assertThrows(IllegalArgumentException.class,()->new LibraryEpisodePage(0,
                java.util.Collections.nCopies(SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE+1,episode(new PodcastContext(1,"",null,true))),false));
    }
    @Test public void historyTimesAreBoundedAndCopiedWithThePage() {
        var item=episode(new PodcastContext(1,"Podcast",null,true));
        var times=new ArrayList<Long>(List.of(100L));var page=new LibraryEpisodePage(0,List.of(item),false,times);
        times.set(0,200L);assertEquals(List.of(100L),page.listenedAtMillis());
        assertThrows(UnsupportedOperationException.class,()->page.listenedAtMillis().clear());
        assertThrows(IllegalArgumentException.class,()->new LibraryEpisodePage(0,List.of(item),false,List.of()));
        assertThrows(IllegalArgumentException.class,()->new LibraryEpisodePage(0,List.of(item),false,List.of(-1L)));
    }
}
