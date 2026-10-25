package io.github.jnesew.plainpoddroid.playback;

import android.net.Uri;
import android.test.AndroidTestCase;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import io.github.jnesew.plainpoddroid.core.download.AppPrivateDownloadStore;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadActions;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadResult;
import io.github.jnesew.plainpoddroid.core.playback.DownloadPlaybackUri;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackDownloadPreparer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@UnstableApi
@SuppressWarnings("deprecation")
public final class DownloadBeforePlayingDataSourceTest extends AndroidTestCase {
    private static final String ID = "episode-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private Path directory;
    private ExecutorService lane;
    private AppPrivateDownloadStore store;
    private final AtomicInteger calls = new AtomicInteger();
    @Override protected void setUp() throws Exception {
        super.setUp();
        directory = Files.createTempDirectory(getContext().getCacheDir().toPath(), "play-first-test-");
        store = new AppPrivateDownloadStore(directory.toFile());
        lane = Executors.newSingleThreadExecutor();
    }
    @Override protected void tearDown() throws Exception {
        lane.shutdownNow();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList())) Files.delete(path);
        }
        super.tearDown();
    }
    private DownloadBeforePlayingDataSource.Factory factory(boolean commit) {
        EpisodeDownloadActions downloads = new EpisodeDownloadActions() {
            public EpisodeDownloadResult download(String id, BooleanSupplier cancelled) {
                calls.incrementAndGet();
                try (var writer = store.begin(id, 3L, 1024L)) {
                    writer.write(new byte[]{10, 20, 30}, 0, 3);
                    if (commit) writer.finish();
                } catch (IOException exception) { throw new IllegalStateException(exception); }
                return null;
            }
            public void delete(String id) { throw new AssertionError(); }
            public long getStorageUsageBytes() { return 0L; }
        };
        return new DownloadBeforePlayingDataSource.Factory(new PlaybackDownloadPreparer(
                store, downloads, lane, () -> false), new VerifiedDownloadDataSource.Factory(store));
    }
    public void testOnlyCompleteLocalFileIsOpenedAndRequestedOffsetIsPreserved() throws Exception {
        var source = factory(true).createDataSource();
        try {
            assertEquals(2L, source.open(new DataSpec.Builder()
                    .setUri(Uri.parse(DownloadPlaybackUri.create(ID).toString())).setPosition(1L).build()));
            assertEquals("file", source.getUri().getScheme());
            byte[] bytes = new byte[2];
            assertEquals(2, source.read(bytes, 0, 2));
            assertEquals(20, bytes[0]); assertEquals(30, bytes[1]); assertEquals(1, calls.get());
        } finally { source.close(); }
    }
    public void testPartialFileNeverOpensForPlayback() throws Exception {
        var source = factory(false).createDataSource();
        try {
            source.open(new DataSpec(Uri.parse(DownloadPlaybackUri.create(ID).toString())));
            fail("Partial audio was accepted");
        } catch (IOException expected) { assertNull(source.getUri()); }
        finally { source.close(); }
    }
    public void testExternalSourcesCannotUseThePreparationPath() throws Exception {
        var source = factory(true).createDataSource();
        try { source.open(new DataSpec(Uri.parse("https://media.example/audio.mp3"))); fail(); }
        catch (IOException expected) { assertEquals(0, calls.get()); }
        finally { source.close(); }
    }
}
