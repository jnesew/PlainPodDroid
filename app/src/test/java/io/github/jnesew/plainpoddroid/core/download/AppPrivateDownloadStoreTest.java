package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class AppPrivateDownloadStoreTest {
    private static final long TEST_LIMIT_BYTES = 8L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void playbackLeasesProtectFilesUntilEveryReaderCloses() throws Exception {
        AppPrivateDownloadStore store = newStore();
        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("playing", 2L)) {
            writer.write(new byte[] {1, 2});
            writer.finish();
        }
        String uri = store.lookup("playing").getCompleteFile().toURI().toString();
        Closeable first = store.acquireReadLease(uri);
        Closeable second = store.acquireReadLease(uri);
        assertThrows(IOException.class, () -> store.delete("playing"));
        assertThrows(IOException.class, () -> store.begin("playing", 2L));
        first.close();
        first.close();
        assertThrows(IOException.class, () -> store.delete("playing"));
        assertTrue(store.isVerifiedCompleteUri(uri));
        second.close();
        store.delete("playing");
        assertEquals(DownloadLookup.State.ABSENT, store.lookup("playing").getState());
    }

    @Test
    public void replacementDownloadCannotAcquirePlaybackReader() throws Exception {
        AppPrivateDownloadStore store = newStore();
        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("replace", 2L)) {
            writer.write(new byte[] {1, 2});
            writer.finish();
        }
        String uri = store.lookup("replace").getCompleteFile().toURI().toString();
        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("replace", 2L)) {
            assertTrue(store.isVerifiedCompleteUri(uri));
            assertThrows(IOException.class, () -> store.acquireReadLease(uri));
            writer.write(new byte[] {3, 4});
            writer.finish();
        }
        try (Closeable lease = store.acquireReadLease(uri)) {
            assertThrows(IOException.class, () -> store.delete("replace"));
        }
        store.delete("replace");
    }

    @Test
    public void playbackLeaseRejectsAbsentAndPartialDownloads() throws Exception {
        AppPrivateDownloadStore store = newStore();
        String absent = new File(temporaryFolder.getRoot(), "foreign.audio").toURI().toString();
        assertThrows(IOException.class, () -> store.acquireReadLease(absent));
        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("partial", 2L)) {
            writer.write(new byte[] {1});
        }
        assertThrows(IOException.class, () -> store.acquireReadLease(
                new File(temporaryFolder.getRoot(), "partial.part").toURI().toString()));
        store.delete("partial");
        assertEquals(DownloadLookup.State.ABSENT, store.lookup("partial").getState());
    }

    @Test
    public void completedDownloadBecomesVerifiedOnlyAfterFinish() throws Exception {
        AppPrivateDownloadStore store = newStore();
        byte[] content = "audio".getBytes(StandardCharsets.UTF_8);

        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin("episode-1", content.length)) {
            writer.write(content);
            assertFalse(writer.isFinished());
            assertEquals(DownloadLookup.State.PARTIAL, store.lookup("episode-1").getState());
            writer.finish();
            assertTrue(writer.isFinished());
        }

        DownloadLookup lookup = store.lookup("episode-1");
        assertEquals(DownloadLookup.State.COMPLETE, lookup.getState());
        assertArrayEquals(content, Files.readAllBytes(lookup.getCompleteFile().toPath()));
        assertTrue(store.isVerifiedCompleteUri(lookup.getCompleteFile().toURI().toString()));
    }

    @Test
    public void partialDownloadNeverBecomesPlayable() throws Exception {
        AppPrivateDownloadStore store = newStore();

        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("partial", 4L)) {
            writer.write(new byte[]{1, 2});
        }

        assertEquals(DownloadLookup.State.PARTIAL, store.lookup("partial").getState());
    }

    @Test
    public void expectedLengthMismatchRemainsPartial() throws Exception {
        AppPrivateDownloadStore store = newStore();

        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("short", 4L)) {
            writer.write(new byte[]{1, 2, 3});
            expectIOException(writer::finish);
        }

        assertEquals(DownloadLookup.State.PARTIAL, store.lookup("short").getState());
    }

    @Test
    public void zeroByteDownloadRemainsPartial() throws Exception {
        AppPrivateDownloadStore store = newStore();

        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin("empty", AppPrivateDownloadStore.UNKNOWN_LENGTH)) {
            expectIOException(writer::finish);
        }

        assertEquals(DownloadLookup.State.PARTIAL, store.lookup("empty").getState());
    }

    @Test
    public void limitPlusOneFailsBeforeWritingPastLimit() throws Exception {
        AppPrivateDownloadStore store = newStore();

        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin("oversized", AppPrivateDownloadStore.UNKNOWN_LENGTH)) {
            expectIOException(() -> writer.write(new byte[(int) TEST_LIMIT_BYTES + 1]));
        }

        assertEquals(DownloadLookup.State.PARTIAL, store.lookup("oversized").getState());
    }

    @Test
    public void perTransferWriteLimitCanBeStricterThanTheStoreLimit() throws Exception {
        AppPrivateDownloadStore store = newStore();

        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin("quota", AppPrivateDownloadStore.UNKNOWN_LENGTH, 2L)) {
            expectIOException(() -> writer.write(new byte[] {1, 2, 3}));
        }

        assertEquals(DownloadLookup.State.PARTIAL, store.lookup("quota").getState());
        assertThrows(IllegalArgumentException.class, () ->
                store.begin("zero-limit", AppPrivateDownloadStore.UNKNOWN_LENGTH, 0L));
        assertThrows(IllegalArgumentException.class, () ->
                store.begin("large-limit", AppPrivateDownloadStore.UNKNOWN_LENGTH,
                        TEST_LIMIT_BYTES + 1L));
    }

    @Test
    public void storageUsageCountsControlledCompleteAndPartialMaterial() throws Exception {
        AppPrivateDownloadStore store = newStore();
        complete(store, "complete", new byte[] {1, 2, 3});
        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin("partial-usage", 2L)) {
            writer.write(new byte[] {4});
        }

        assertEquals(
                3L + AppPrivateDownloadStore.COMPLETION_MARKER_BYTES + 1L,
                store.storageUsageBytes());
    }

    @Test
    public void unexpectedDirectoryMaterialFailsUsageClosed() throws Exception {
        File directory = temporaryFolder.newFolder("usage-invalid");
        AppPrivateDownloadStore store = new AppPrivateDownloadStore(
                directory,
                TEST_LIMIT_BYTES);
        assertTrue(new File(directory, "publisher-name.mp3").createNewFile());

        expectIOException(store::storageUsageBytes);
    }

    @Test
    public void postCompletionLengthChangeInvalidatesDownload() throws Exception {
        AppPrivateDownloadStore store = newStore();
        complete(store, "changed", new byte[]{1, 2, 3, 4});
        File complete = store.lookup("changed").getCompleteFile();

        try (FileOutputStream output = new FileOutputStream(complete, false)) {
            output.write(new byte[]{1, 2, 3});
        }

        assertEquals(DownloadLookup.State.INVALID, store.lookup("changed").getState());
        assertFalse(store.isVerifiedCompleteUri(complete.toURI().toString()));
    }

    @Test
    public void symbolicCompleteFileIsRejected() throws Exception {
        AppPrivateDownloadStore store = newStore();
        complete(store, "symbolic", new byte[]{1, 2, 3, 4});
        File complete = store.lookup("symbolic").getCompleteFile();
        File outside = temporaryFolder.newFile("outside.audio");

        Files.delete(complete.toPath());
        Files.createSymbolicLink(complete.toPath(), outside.toPath());

        assertEquals(DownloadLookup.State.INVALID, store.lookup("symbolic").getState());
        assertFalse(store.isVerifiedCompleteUri(complete.toURI().toString()));
    }

    @Test
    public void hostileMediaIdCannotBecomeAPath() throws Exception {
        File root = temporaryFolder.newFolder("downloads");
        AppPrivateDownloadStore store = new AppPrivateDownloadStore(root, TEST_LIMIT_BYTES);
        complete(store, "../../../../escape.audio", new byte[]{1});

        File complete = store.lookup("../../../../escape.audio").getCompleteFile();
        assertEquals(root.getCanonicalFile(), complete.getParentFile().getCanonicalFile());
        assertTrue(complete.getName().matches("ep-[0-9a-f]{64}\\.audio"));
        assertFalse(new File(temporaryFolder.getRoot(), "escape.audio").exists());
    }

    @Test
    public void arbitraryFileUrisAreRejected() throws Exception {
        AppPrivateDownloadStore store = newStore();
        File outside = temporaryFolder.newFile("not-a-download.audio");

        assertFalse(store.isVerifiedCompleteUri("https://media.example/episode.mp3"));
        assertFalse(store.isVerifiedCompleteUri(outside.toURI().toString()));
        assertFalse(store.isVerifiedCompleteUri("file:///tmp/ep-"
                + "a".repeat(64)
                + ".audio?changed=1"));
    }

    @Test
    public void concurrentWriterAndDeleteAreRejected() throws Exception {
        AppPrivateDownloadStore store = newStore();

        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("busy", 1L)) {
            assertFalse(writer.isFinished());
            expectIOException(() -> store.begin("busy", 1L));
            expectIOException(() -> store.delete("busy"));
        }
    }

    @Test
    public void deleteRemovesCompleteAndPartialState() throws Exception {
        AppPrivateDownloadStore store = newStore();
        complete(store, "delete-complete", new byte[]{1});
        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin("delete-partial", 2L)) {
            writer.write(new byte[]{1});
        }

        store.delete("delete-complete");
        store.delete("delete-partial");

        assertEquals(DownloadLookup.State.ABSENT, store.lookup("delete-complete").getState());
        assertEquals(DownloadLookup.State.ABSENT, store.lookup("delete-partial").getState());
    }

    @Test
    public void cleanupDeletesOnlyExpiredTemporaryMaterial() throws Exception {
        File directory = temporaryFolder.newFolder("cleanup-expired");
        AppPrivateDownloadStore store = new AppPrivateDownloadStore(
                directory,
                TEST_LIMIT_BYTES);
        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin("abandoned", 2L)) {
            writer.write(new byte[] {1});
        }
        File partial = onlyFileEndingWith(directory, ".part");
        String key = partial.getName().substring(
                0,
                partial.getName().length() - ".part".length());
        File markerTemporary = new File(directory, key + ".complete.part");
        assertTrue(markerTemporary.createNewFile());
        complete(store, "retained-complete", new byte[] {2});

        long nowMillis = SecurityLimits.DOWNLOAD_TEMP_MAX_AGE_MILLIS * 2L;
        long expiredMillis = nowMillis - SecurityLimits.DOWNLOAD_TEMP_MAX_AGE_MILLIS;
        assertTrue(partial.setLastModified(expiredMillis));
        assertTrue(markerTemporary.setLastModified(expiredMillis));

        assertEquals(2, store.cleanupAbandonedTemporaryFiles(nowMillis));
        assertFalse(partial.exists());
        assertFalse(markerTemporary.exists());
        assertEquals(DownloadLookup.State.ABSENT, store.lookup("abandoned").getState());
        assertEquals(DownloadLookup.State.COMPLETE,
                store.lookup("retained-complete").getState());
    }

    @Test
    public void cleanupRetainsFreshAndActiveTemporaryMaterial() throws Exception {
        File directory = temporaryFolder.newFolder("cleanup-active");
        AppPrivateDownloadStore store = new AppPrivateDownloadStore(
                directory,
                TEST_LIMIT_BYTES);
        AppPrivateDownloadStore.DownloadWriter writer = store.begin("active", 2L);
        try {
            writer.write(new byte[] {1});
            File partial = onlyFileEndingWith(directory, ".part");
            long nowMillis = SecurityLimits.DOWNLOAD_TEMP_MAX_AGE_MILLIS * 2L;
            assertTrue(partial.setLastModified(0L));

            assertEquals(0, store.cleanupAbandonedTemporaryFiles(nowMillis));
            assertTrue(partial.exists());
            writer.close();
            assertEquals(1, store.cleanupAbandonedTemporaryFiles(nowMillis));
            assertFalse(partial.exists());
        } finally {
            writer.close();
        }
    }

    @Test
    public void cleanupRetainsTemporaryMaterialYoungerThanMaximumAge() throws Exception {
        File directory = temporaryFolder.newFolder("cleanup-fresh");
        AppPrivateDownloadStore store = new AppPrivateDownloadStore(
                directory,
                TEST_LIMIT_BYTES);
        try (AppPrivateDownloadStore.DownloadWriter writer = store.begin("fresh", 2L)) {
            writer.write(new byte[] {1});
        }
        File partial = onlyFileEndingWith(directory, ".part");
        long nowMillis = SecurityLimits.DOWNLOAD_TEMP_MAX_AGE_MILLIS * 2L;
        assertTrue(partial.setLastModified(
                nowMillis - SecurityLimits.DOWNLOAD_TEMP_MAX_AGE_MILLIS + 1L));

        assertEquals(0, store.cleanupAbandonedTemporaryFiles(nowMillis));
        assertTrue(partial.exists());
    }

    @Test
    public void cleanupFailsClosedForInvalidOrExcessDirectoryMaterial()
            throws Exception {
        File invalidDirectory = temporaryFolder.newFolder("cleanup-invalid");
        AppPrivateDownloadStore invalidStore = new AppPrivateDownloadStore(
                invalidDirectory,
                TEST_LIMIT_BYTES);
        assertTrue(new File(invalidDirectory, "publisher.mp3").createNewFile());
        expectIOException(() -> invalidStore.cleanupAbandonedTemporaryFiles(1L));

        File boundedDirectory = temporaryFolder.newFolder("cleanup-bounded");
        AppPrivateDownloadStore boundedStore = new AppPrivateDownloadStore(
                boundedDirectory,
                TEST_LIMIT_BYTES);
        try (AppPrivateDownloadStore.DownloadWriter first =
                     boundedStore.begin("first", 1L)) {
            first.write(new byte[] {1});
        }
        try (AppPrivateDownloadStore.DownloadWriter second =
                     boundedStore.begin("second", 1L)) {
            second.write(new byte[] {2});
        }

        expectIOException(() -> boundedStore.cleanupAbandonedTemporaryFiles(1L, 1));
        assertThrows(IllegalArgumentException.class, () ->
                boundedStore.cleanupAbandonedTemporaryFiles(-1L));
    }

    private AppPrivateDownloadStore newStore() throws IOException {
        return new AppPrivateDownloadStore(
                temporaryFolder.newFolder("downloads"),
                TEST_LIMIT_BYTES);
    }

    private static void complete(
            AppPrivateDownloadStore store,
            String mediaId,
            byte[] content) throws IOException {
        try (AppPrivateDownloadStore.DownloadWriter writer =
                     store.begin(mediaId, content.length)) {
            writer.write(content);
            writer.finish();
        }
    }

    private static File onlyFileEndingWith(File directory, String suffix)
            throws IOException {
        try (var paths = Files.list(directory.toPath())) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Expected controlled test file"))
                    .toFile();
        }
    }

    private static void expectIOException(IoOperation operation) {
        try {
            operation.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected fail-closed path.
        }
    }

    private interface IoOperation {
        void run() throws IOException;
    }
}
