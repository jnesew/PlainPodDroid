package io.github.jnesew.plainpoddroid.core.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.Test;

public final class SubscriptionRemovalCoordinatorTest {
    @Test
    public void keepArchivesWithoutTouchingFilesOrQueue() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        SubscriptionRemovalCoordinator coordinator = new SubscriptionRemovalCoordinator(
                catalog, mediaId -> { throw new AssertionError("Unexpected file deletion"); });

        assertTrue(coordinator.remove(1L, true));
        assertTrue(catalog.archived);
        assertFalse(catalog.deleted);
    }

    @Test
    public void fileFailureKeepsArchiveRetryableBeforeDatabaseCascade() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        int[] attempts = {0};
        SubscriptionRemovalCoordinator coordinator = new SubscriptionRemovalCoordinator(
                catalog, mediaId -> {
                    attempts[0]++;
                    if (attempts[0] == 2) throw new IOException("Active file");
                });

        assertThrows(IOException.class, () -> coordinator.remove(1L, false));
        assertTrue(catalog.archived);
        assertFalse(catalog.deleted);
        assertTrue(coordinator.remove(1L, false));
        assertTrue(catalog.deleted);
        assertEquals(4, attempts[0]);
    }

    private static final class FakeCatalog implements SubscriptionRemovalStore {
        boolean archived;
        boolean deleted;

        @Override
        public boolean archiveSubscription(long id) {
            if (id != 1L || archived) return false;
            archived = true;
            return true;
        }

        @Override
        public List<String> listArchivedMediaIds(long id) {
            return id == 1L && archived && !deleted ? List.of("first", "second") : null;
        }

        @Override
        public boolean deleteArchivedSubscription(long id) {
            if (id != 1L || !archived || deleted) return false;
            deleted = true;
            return true;
        }
    }
}
