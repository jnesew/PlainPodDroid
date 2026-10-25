package io.github.jnesew.plainpoddroid.core.artwork;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ThumbnailDiskCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path directory() throws Exception { return temporary.newFolder().toPath(); }
    private String key(String name) { return ThumbnailDiskCache.key("https://example.com/" + name, 512); }
    @Test public void survivesRestartAndNeverStoresUrlOrTokenInFilename() throws Exception {
        Path path = directory(); String key = key("art?token=secret");
        new ThumbnailDiskCache(path).put(key, new byte[] {1,2,3}, 100);
        ThumbnailDiskCache.Entry entry = new ThumbnailDiskCache(path).get(key, 200);
        assertArrayEquals(new byte[] {1,2,3}, entry.bytes()); assertEquals(100, entry.savedAtMillis());
        try (var listing = Files.list(path)) { assertEquals(1, listing.count()); }
        assertTrue(Files.exists(path.resolve(key + ".thumb")));
        assertNotEquals(key, ThumbnailDiskCache.key("https://example.com/art?token=secret",128));
    }
    @Test public void byteAndEntryQuotasEvictLeastRecentlyAccessed() throws Exception {
        Path path = directory(); ThumbnailDiskCache cache = new ThumbnailDiskCache(path,100,2);
        cache.put(key("a"),new byte[20],100); cache.put(key("b"),new byte[20],200);
        cache.get(key("a"),300); cache.put(key("c"),new byte[20],400);
        assertNull(cache.get(key("b"),500)); assertNotNull(cache.get(key("a"),500));
        cache.put(key("d"),new byte[65],600);
        assertNull(cache.get(key("a"),700)); assertNull(cache.get(key("c"),700));
        assertNotNull(cache.get(key("d"),700));
    }
    @Test public void corruptPartialOversizedAndUnsafeKeysFailClosed() throws Exception {
        Path path = directory(); ThumbnailDiskCache cache = new ThumbnailDiskCache(path);
        Path file = path.resolve(key("a") + ".thumb"); Files.write(file,new byte[30]);
        assertNull(cache.get(key("a"),100)); assertFalse(Files.exists(file));
        assertThrows(IllegalArgumentException.class,()->cache.get("../elsewhere",100));
        assertThrows(IllegalArgumentException.class,()->cache.put(key("a"),new byte[ThumbnailDiskCache.MAX_ENTRY_BYTES+1],100));
        cache.put(key("a"),new byte[] {1},100);
        byte[] truncated=Files.readAllBytes(file); Files.write(file,java.util.Arrays.copyOf(truncated,truncated.length-1));
        assertNull(cache.get(key("a"),200));
    }
    @Test public void symbolicLinksCannotReadOutsideTheCache() throws Exception {
        Path path = directory(); ThumbnailDiskCache cache = new ThumbnailDiskCache(path);
        Path other = temporary.newFile().toPath(); Files.write(other,new byte[] {9});
        Files.createSymbolicLink(path.resolve(key("a") + ".thumb"),other);
        assertNull(cache.get(key("a"),100)); assertArrayEquals(new byte[] {9},Files.readAllBytes(other));
    }
}
