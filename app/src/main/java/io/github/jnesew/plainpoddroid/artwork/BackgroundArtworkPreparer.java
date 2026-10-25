package io.github.jnesew.plainpoddroid.artwork;

import android.graphics.Bitmap;
import io.github.jnesew.plainpoddroid.core.artwork.BoundedArtworkAcquirer;
import io.github.jnesew.plainpoddroid.core.artwork.ThumbnailDiskCache;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded cover preparation shares the screen's decoder, key, freshness and disk quota. */
public final class BackgroundArtworkPreparer {
    private BackgroundArtworkPreparer() { }
    public static void prepare(BoundedArtworkAcquirer acquirer, Path directory,
            List<CanonicalHttpsUrl> sources, BooleanSupplier stopped) {
        if (stopped.getAsBoolean()) return;
        ThumbnailDiskCache cache;
        try {
            synchronized (ArtworkThumbnailLoader.DISK_LOCK) { cache = new ThumbnailDiskCache(directory); }
        } catch (IOException | RuntimeException unavailable) { return; }
        int attempted = 0;
        var seen = new HashSet<CanonicalHttpsUrl>();
        for (CanonicalHttpsUrl url : sources) {
            if (stopped.getAsBoolean() || attempted >= SecurityLimits.MAX_PREPARATION_COVERS) return;
            if (url == null || !seen.add(url)) continue;
            attempted++;
            try {
                if (fresh(cache, url, 128) && fresh(cache, url, 512)) continue;
                var payload = acquirer.acquire(url);
                for (int target : new int[]{128, 512}) {
                    if (stopped.getAsBoolean()) return;
                    Bitmap bitmap = ArtworkThumbnailLoader.decode(payload, target);
                    if (bitmap == null) continue;
                    try {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) && !stopped.getAsBoolean())
                            synchronized (ArtworkThumbnailLoader.DISK_LOCK) {
                                cache.put(ThumbnailDiskCache.key(url.toString(), target), output.toByteArray(), System.currentTimeMillis());
                            }
                    } finally { bitmap.recycle(); }
                }
            } catch (IOException | RuntimeException rejected) {
                // Optional artwork never prevents successfully downloaded audio from being used.
            }
        }
    }
    private static boolean fresh(ThumbnailDiskCache cache, CanonicalHttpsUrl url, int target) throws IOException {
        long now = System.currentTimeMillis();
        ThumbnailDiskCache.Entry existing;
        synchronized (ArtworkThumbnailLoader.DISK_LOCK) { existing = cache.get(ThumbnailDiskCache.key(url.toString(), target), now); }
        return existing != null && existing.savedAtMillis() <= now
                && now - existing.savedAtMillis() < ArtworkThumbnailLoader.FRESH_MILLIS;
    }
}
