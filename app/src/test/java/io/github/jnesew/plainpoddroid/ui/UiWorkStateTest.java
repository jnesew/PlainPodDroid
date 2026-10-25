package io.github.jnesew.plainpoddroid.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UiWorkStateTest {
    private static UiWorkState busy(int index) {
        boolean[] flags = new boolean[9];
        if (index >= 0) flags[index] = true;
        return new UiWorkState(flags[0], flags[1], flags[2], flags[3], flags[4],
                flags[5], flags[6], flags[7], flags[8]);
    }

    @Test public void acquisitionAndDownloadsAllowCatalogReadsButLocalMutationsDoNot() {
        assertFalse(busy(3).hasCatalogReadCompetingWork());
        assertFalse(busy(0).hasCatalogReadCompetingWork());
        assertFalse(busy(1).hasCatalogReadCompetingWork());
        assertFalse(busy(-1).hasCatalogReadCompetingWork());
        for (int flag : new int[] {4, 5, 6, 7, 8}) {
            assertTrue(busy(flag).hasCatalogReadCompetingWork());
        }
    }

    @Test public void idleAllowsAllActions() {
        UiWorkState idle = busy(-1);
        assertFalse(idle.hasQueueCompetingWork());
        assertFalse(idle.hasEpisodeActionCompetingWork());
        assertFalse(idle.hasDownloadCompetingWork(false));
        assertFalse(idle.isAnyMediaWorkBusy());
    }

    @Test public void queueWorkDoesNotCompeteWithItself() {
        UiWorkState queue = busy(5);
        assertFalse(queue.hasQueueCompetingWork());
        assertTrue(queue.hasEpisodeActionCompetingWork());
        assertTrue(queue.hasDownloadCompetingWork(true));
        assertTrue(queue.isAnyMediaWorkBusy());
    }

    @Test public void episodeActionDoesNotCompeteWithItself() {
        UiWorkState action = busy(6);
        assertTrue(action.hasQueueCompetingWork());
        assertFalse(action.hasEpisodeActionCompetingWork());
        assertTrue(action.hasDownloadCompetingWork(true));
        assertTrue(action.isAnyMediaWorkBusy());
    }

    @Test public void currentDownloadAllowsItsOwnCancellationButBlocksOtherEpisodes() {
        UiWorkState download = busy(3);
        assertFalse(download.hasDownloadCompetingWork(true));
        assertTrue(download.hasDownloadCompetingWork(false));
        assertTrue(download.hasEpisodeActionCompetingWork());
        assertTrue(download.hasQueueCompetingWork());
    }

    @Test public void feedSubscriptionAndCatalogWorkBlockActionsWithoutBeingMediaWork() {
        for (int flag : new int[] {0, 1, 2}) {
            UiWorkState state = busy(flag);
            assertTrue(state.hasQueueCompetingWork());
            assertTrue(state.hasEpisodeActionCompetingWork());
            assertTrue(state.hasDownloadCompetingWork(true));
            assertFalse(state.isAnyMediaWorkBusy());
        }
    }

    @Test public void storageAndChapterWorkBlockAllThreeActionGroups() {
        for (int flag : new int[] {4, 7}) {
            UiWorkState state = busy(flag);
            assertTrue(state.hasQueueCompetingWork());
            assertTrue(state.hasEpisodeActionCompetingWork());
            assertTrue(state.hasDownloadCompetingWork(true));
            assertTrue(state.isAnyMediaWorkBusy());
        }
    }

    @Test public void browsingAndFeedWorkDoNotBlockOpaquePlaybackButFileMutationsDo(){
        for(int flag:new int[]{-1,0,1,2,5,6,7})assertFalse(busy(flag).hasPlaybackCompetingWork());
        for(int flag:new int[]{3,4,8})assertTrue(busy(flag).hasPlaybackCompetingWork());
    }

    @Test public void removalAlsoBlocksNewDownloadRequests() {
        UiWorkState removal = busy(8);
        assertTrue(removal.isAnyMediaWorkBusy());
        assertFalse(removal.hasQueueCompetingWork());
        assertFalse(removal.hasEpisodeActionCompetingWork());
        assertTrue(removal.hasDownloadCompetingWork(false));
        assertTrue(removal.hasDownloadCompetingWork(true));
    }
}
