package io.github.jnesew.plainpoddroid.core.ui;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.jnesew.plainpoddroid.core.ui.NavigationState.Destination.*;

public final class NavigationStateTest {
    private static final String ID = "episode-" + "a".repeat(64);
    @Test public void outsideAndBackCloseDrawerWithoutChangingDestination() {
        NavigationState n = new NavigationState(); n.select(QUEUE); n.openDrawer();
        assertTrue(n.back()); assertFalse(n.isDrawerOpen()); assertEquals(QUEUE,n.current().destination());
        n.openDrawer(); n.closeDrawer(); assertEquals(QUEUE,n.current().destination());
    }
    @Test public void selectingCurrentDestinationAlsoClosesDrawerAndResetsChildren() {
        NavigationState n = new NavigationState(); n.select(DOWNLOADS); n.openEpisode(ID);
        n.openDrawer(); n.select(DOWNLOADS);
        assertFalse(n.isDrawerOpen()); assertFalse(n.hasParent()); assertEquals(DOWNLOADS,n.current().destination());
    }
    @Test public void episodeBackReturnsToOriginAndPodcastBackToLibrary() {
        NavigationState n = new NavigationState(); n.openPodcast(5); n.openEpisode(ID);
        assertTrue(n.back()); assertEquals(5,n.current().podcastId());
        assertTrue(n.back()); assertEquals(PODCASTS,n.current().destination()); assertFalse(n.back());
        n.select(FAVORITES); n.openEpisode(ID); n.back(); assertEquals(FAVORITES,n.current().destination());
    }
    @Test public void recreationPreservesNavigationButNeverAnOpenDrawer() {
        NavigationState n = new NavigationState(); n.select(HISTORY); n.openEpisode(ID); n.openDrawer();
        NavigationState restored = new NavigationState(); restored.restore(n.snapshot());
        assertEquals(n.current(),restored.current()); assertFalse(restored.isDrawerOpen());
        restored.back(); assertEquals(HISTORY,restored.current().destination());
    }
    @Test public void repeatedNavigationIsBoundedAndSnapshotsAreImmutable() {
        NavigationState n = new NavigationState(); n.openEpisode(ID); n.openEpisode(ID);
        assertEquals(2,n.snapshot().size());
        assertThrows(UnsupportedOperationException.class,()->n.snapshot().clear());
        for(int i=1;i<100;i++) n.openPodcast(i);
        assertEquals(NavigationState.MAX_DEPTH,n.snapshot().size());
        assertThrows(IllegalArgumentException.class,()->n.restore(List.of()));
        assertThrows(IllegalArgumentException.class,()->n.openEpisode("https://example.com/audio"));
        assertThrows(IllegalArgumentException.class,()->n.openPodcast(0));
    }
}
