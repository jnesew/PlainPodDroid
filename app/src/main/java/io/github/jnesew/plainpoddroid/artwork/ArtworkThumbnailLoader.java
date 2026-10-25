package io.github.jnesew.plainpoddroid.artwork;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.SystemClock;
import android.util.LruCache;
import io.github.jnesew.plainpoddroid.core.artwork.ArtworkPayload;
import io.github.jnesew.plainpoddroid.core.artwork.ArtworkGeometry;
import io.github.jnesew.plainpoddroid.core.artwork.ArtworkAcquisitionException;
import io.github.jnesew.plainpoddroid.core.artwork.ArtworkRetryPolicy;
import io.github.jnesew.plainpoddroid.core.artwork.BoundedArtworkAcquirer;
import io.github.jnesew.plainpoddroid.core.artwork.ThumbnailDiskCache;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared sampled covers; disk I/O and decoding never run on the UI thread. */
public final class ArtworkThumbnailLoader implements AutoCloseable {
    private static final int TARGET_PIXELS = 128;
    private static final int MAX_CACHED_BYTES = 16 * 1024 * 1024;
    static final long FRESH_MILLIS = 7L * 24 * 60 * 60 * 1000;
    static final Object DISK_LOCK = new Object();
    private final BoundedArtworkAcquirer acquirer;
    private final Handler mainHandler;
    private final int targetPixels;
    private final Path directory;
    private final java.util.function.BooleanSupplier offline;
    private ThumbnailDiskCache disk; // Worker access only, under diskLock.
    private final Object diskLock = DISK_LOCK;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
    private record Cover(Bitmap bitmap, long savedAt) { }
    private final LruCache<String, Cover> cache = new LruCache<>(MAX_CACHED_BYTES) {
        @Override protected int sizeOf(String key, Cover cover) { return cover.bitmap().getAllocationByteCount(); }
    };
    private final Set<String> inFlight = new HashSet<>();
    private final ArtworkRetryPolicy retry = new ArtworkRetryPolicy();
    private volatile boolean closed, active;
    private long wakeAt = Long.MAX_VALUE;
    private Runnable repaint;
    private final Runnable wake = () -> { wakeAt = Long.MAX_VALUE; if (active && !closed && repaint != null) repaint.run(); };

    public ArtworkThumbnailLoader(BoundedArtworkAcquirer acquirer, Handler handler) {
        this(acquirer, handler, TARGET_PIXELS, null);
    }
    public ArtworkThumbnailLoader(BoundedArtworkAcquirer acquirer, Handler handler, int targetPixels) {
        this(acquirer, handler, targetPixels, null);
    }
    public ArtworkThumbnailLoader(BoundedArtworkAcquirer acquirer, Handler handler, int targetPixels, Path directory) {
        this(acquirer, handler, targetPixels, directory, () -> false);
    }
    public ArtworkThumbnailLoader(BoundedArtworkAcquirer acquirer, Handler handler, int targetPixels,
            Path directory, java.util.function.BooleanSupplier offline) {
        if (targetPixels != TARGET_PIXELS && targetPixels != 512) throw new IllegalArgumentException("Invalid artwork target.");
        this.targetPixels = targetPixels; this.directory = directory;
        this.offline = java.util.Objects.requireNonNull(offline);
        this.acquirer = java.util.Objects.requireNonNull(acquirer, "acquirer");
        mainHandler = java.util.Objects.requireNonNull(handler, "handler");
    }
    public void start() { active = true; if (repaint != null) repaint.run(); }
    public void stop() { active = false; mainHandler.removeCallbacks(wake); wakeAt = Long.MAX_VALUE; }

    /** A memory miss reads the private thumbnail first; one request per URL remains in flight. */
    public Bitmap getOrRequest(CanonicalHttpsUrl url, Runnable onReady) {
        if (url == null || closed) return null;
        repaint = onReady;
        String key = ThumbnailDiskCache.key(url.toString(), targetPixels);
        Cover current = cache.get(key);
        if (current != null && (offline.getAsBoolean() || fresh(current.savedAt()))) return current.bitmap();
        if (!active || inFlight.contains(key)) return current == null ? null : current.bitmap();
        long now = SystemClock.elapsedRealtime();
        if (!offline.getAsBoolean() && !retry.canAttempt(key, now)) {
            schedule(retry.nextAttempt(key)); return current == null ? null : current.bitmap();
        }
        inFlight.add(key);
        try { workers.execute(() -> load(url, key, current, onReady)); }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            inFlight.remove(key); schedule(now + 1000);
        }
        return current == null ? null : current.bitmap();
    }
    private static boolean fresh(long savedAt) {
        long now = System.currentTimeMillis(); return savedAt <= now && now - savedAt < FRESH_MILLIS;
    }
    private void load(CanonicalHttpsUrl url, String key, Cover existing, Runnable onReady) {
        if (closed) return;
        Cover local = existing;
        if (local == null && directory != null) {
            try {
                ThumbnailDiskCache.Entry entry;
                synchronized (diskLock) { entry = disk().get(key, System.currentTimeMillis()); }
                if (entry != null) {
                    Bitmap bitmap = decodeThumbnail(entry.bytes(), targetPixels);
                    if (bitmap != null) local = new Cover(bitmap, entry.savedAtMillis());
                    else synchronized (diskLock) { disk().remove(key); }
                }
            } catch (IOException | RuntimeException ignored) { /* Disposable cache failure must not block artwork. */ }
        }
        Cover cached = local;
        if (cached != null) mainHandler.post(() -> {
            if (closed) { if (existing == null) cached.bitmap().recycle(); }
            else { cache.put(key, cached); onReady.run(); }
        });
        if (cached != null && fresh(cached.savedAt())) { finish(key, null, false, true, onReady); return; }
        if (offline.getAsBoolean()) {
            mainHandler.post(() -> inFlight.remove(key));
            return;
        }
        if (!active || closed) {
            mainHandler.post(() -> { inFlight.remove(key); if (!closed) onReady.run(); });
            return;
        }
        Cover fetched = null; boolean rejected = false;
        try {
            Bitmap bitmap = decode(acquirer.acquire(url), targetPixels);
            if (bitmap == null) rejected = true;
            else {
                fetched = new Cover(bitmap, System.currentTimeMillis());
                if (directory != null) {
                    try {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                            synchronized (diskLock) { disk().put(key, bytes.toByteArray(), fetched.savedAt()); }
                        }
                    } catch (IOException | RuntimeException ignored) { /* RAM result remains usable. */ }
                }
            }
        } catch (ArtworkAcquisitionException refusal) {
            rejected = refusal.getReason() != ArtworkAcquisitionException.Reason.HTTP_STATUS
                    && refusal.getReason() != ArtworkAcquisitionException.Reason.MISSING_BODY;
        } catch (IOException ignored) { /* Temporary network failure. */ }
        catch (RuntimeException invalid) { rejected = true; }
        finish(key, fetched, rejected, fetched != null, onReady);
    }
    private ThumbnailDiskCache disk() throws IOException {
        if (disk == null) disk = new ThumbnailDiskCache(directory);
        return disk;
    }
    private void finish(String key, Cover result, boolean rejected, boolean success, Runnable onReady) {
        mainHandler.post(() -> {
            inFlight.remove(key);
            if (closed) { if (result != null) result.bitmap().recycle(); return; }
            if (offline.getAsBoolean() && result == null) return;
            if (success) retry.succeeded(key);
            else { retry.failed(key, SystemClock.elapsedRealtime(), rejected); schedule(retry.nextAttempt(key)); }
            if (result != null) cache.put(key, result);
            onReady.run();
        });
    }
    private void schedule(long deadline) {
        if (!active || closed || offline.getAsBoolean() || deadline >= wakeAt) return;
        wakeAt = deadline; mainHandler.removeCallbacks(wake);
        mainHandler.postDelayed(wake, Math.max(1000, deadline - SystemClock.elapsedRealtime()));
    }
    static Bitmap decode(ArtworkPayload payload) { return decode(payload, TARGET_PIXELS); }
    static Bitmap decode(ArtworkPayload payload, int targetPixels) {
        BitmapFactory.Options probe = new BitmapFactory.Options(); probe.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(payload.openStream(), null, probe);
        int sample = ArtworkGeometry.sampleSize(probe.outWidth, probe.outHeight, targetPixels);
        if (sample == 0 || !mimeMatches(payload.getFormat(), probe.outMimeType)) return null;
        BitmapFactory.Options options = options(sample);
        return checked(BitmapFactory.decodeStream(payload.openStream(), null, options), targetPixels);
    }
    private static Bitmap decodeThumbnail(byte[] bytes, int target) {
        BitmapFactory.Options probe = probe(bytes);
        if (!"image/png".equals(probe.outMimeType) || probe.outWidth < 1 || probe.outHeight < 1
                || probe.outWidth > target || probe.outHeight > target) return null;
        return bitmap(bytes, target, 1);
    }
    private static BitmapFactory.Options probe(byte[] bytes) {
        BitmapFactory.Options probe = new BitmapFactory.Options(); probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, probe); return probe;
    }
    private static Bitmap bitmap(byte[] bytes, int target, int sample) {
        return checked(BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options(sample)), target);
    }
    private static BitmapFactory.Options options(int sample) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
        options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888; return options;
    }
    private static Bitmap checked(Bitmap bitmap, int target) {
        if (bitmap != null && (bitmap.getWidth() > target || bitmap.getHeight() > target)) { bitmap.recycle(); return null; }
        return bitmap;
    }
    private static boolean mimeMatches(ArtworkPayload.Format format, String mime) {
        if (mime == null) return false;
        return switch (format) { case JPEG -> mime.equals("image/jpeg"); case PNG -> mime.equals("image/png"); case WEBP -> mime.equals("image/webp"); };
    }
    @Override public void close() {
        closed = true; stop(); workers.shutdownNow(); cache.evictAll(); retry.clear(); inFlight.clear(); repaint = null;
    }
}
