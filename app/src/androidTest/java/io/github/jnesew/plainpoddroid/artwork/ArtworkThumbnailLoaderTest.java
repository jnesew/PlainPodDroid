package io.github.jnesew.plainpoddroid.artwork;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import io.github.jnesew.plainpoddroid.core.artwork.BoundedArtworkAcquirer;
import io.github.jnesew.plainpoddroid.core.artwork.ThumbnailDiskCache;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Android PNG decoding and loader restarts; no publisher/network access. */
@SuppressWarnings("deprecation")
public final class ArtworkThumbnailLoaderTest extends InstrumentationTestCase {
    private Path directory;
    private CanonicalHttpsUrl url;
    private final AtomicInteger requests=new AtomicInteger();
    @Override protected void setUp() throws Exception {
        super.setUp();directory=Files.createTempDirectory(getInstrumentation().getTargetContext().getCacheDir().toPath(),"artwork-test-");
        url=CanonicalHttpsUrl.parse("https://artwork.example/cover.png");
    }
    @Override protected void tearDown() throws Exception {
        try(var paths=Files.list(directory)){for(java.util.Iterator<Path> iterator=paths.iterator();iterator.hasNext();)Files.delete(iterator.next());}Files.delete(directory);super.tearDown();
    }
    private ArtworkThumbnailLoader load(CountDownLatch ready) {
        ArtworkThumbnailLoader[] result=new ArtworkThumbnailLoader[1];
        getInstrumentation().runOnMainSync(()->{
            result[0]=new ArtworkThumbnailLoader(new BoundedArtworkAcquirer(source->{requests.incrementAndGet();throw new IOException("Offline fixture");}),
                    new Handler(Looper.getMainLooper()),512,directory);
            result[0].start();assertNull(result[0].getOrRequest(url,ready::countDown));
        });return result[0];
    }
    public void testWarmDiskCoverDecodesAfterTwoLoaderRestartsWithoutFetching() throws Exception {
        Bitmap source=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);source.eraseColor(0xffabcdef);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,bytes));source.recycle();
        new ThumbnailDiskCache(directory).put(ThumbnailDiskCache.key(url.toString(),512),bytes.toByteArray(),System.currentTimeMillis());
        for(int i=0;i<2;i++) {
            CountDownLatch ready=new CountDownLatch(1);ArtworkThumbnailLoader loader=load(ready);
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            getInstrumentation().runOnMainSync(()->{Bitmap cover=loader.getOrRequest(url,()->{});assertNotNull(cover);assertEquals(0xffabcdef,cover.getPixel(0,0));loader.close();});
        }
        assertEquals(0,requests.get());
    }

    public void testOfflineModeUsesAStaleDiskCoverWithoutFetching() throws Exception {
        Bitmap source=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,bytes));source.recycle();
        new ThumbnailDiskCache(directory).put(ThumbnailDiskCache.key(url.toString(),512),
                bytes.toByteArray(),System.currentTimeMillis()-8L*24*60*60*1000);
        CountDownLatch ready=new CountDownLatch(1);
        ArtworkThumbnailLoader[] loader=new ArtworkThumbnailLoader[1];
        getInstrumentation().runOnMainSync(()->{
            loader[0]=new ArtworkThumbnailLoader(new BoundedArtworkAcquirer(request->{
                requests.incrementAndGet();throw new AssertionError("Offline artwork request");
            }),new Handler(Looper.getMainLooper()),512,directory,()->true);
            loader[0].start();loader[0].getOrRequest(url,ready::countDown);
        });
        assertTrue(ready.await(5,TimeUnit.SECONDS));
        getInstrumentation().runOnMainSync(()->{
            assertNotNull(loader[0].getOrRequest(url,()->{}));loader[0].close();
        });
        assertEquals(0,requests.get());
    }
    public void testInvalidDecodedCacheEntryIsRemovedAndOfflineFailureDoesNotLoop() throws Exception {
        String key=ThumbnailDiskCache.key(url.toString(),512);
        new ThumbnailDiskCache(directory).put(key,new byte[]{1,2,3},System.currentTimeMillis());
        CountDownLatch ready=new CountDownLatch(1);ArtworkThumbnailLoader loader=load(ready);assertTrue(ready.await(5,TimeUnit.SECONDS));
        getInstrumentation().runOnMainSync(()->{for(int i=0;i<5;i++)assertNull(loader.getOrRequest(url,()->{}));loader.close();});
        assertEquals(1,requests.get());assertNull(new ThumbnailDiskCache(directory).get(key,System.currentTimeMillis()));
    }

    public void testPreparationCachesBothUiSizesAndDoesNotFetchFreshCoversAgain() throws Exception {
        var acquirer = preparedAcquirer(png(), () -> { });
        BackgroundArtworkPreparer.prepare(acquirer, directory, java.util.List.of(url, url), () -> false);
        for (int size : new int[]{128, 512})
            assertNotNull(new ThumbnailDiskCache(directory).get(ThumbnailDiskCache.key(url.toString(), size), System.currentTimeMillis()));
        assertEquals(1, requests.get());
        BackgroundArtworkPreparer.prepare(acquirer, directory, java.util.List.of(url), () -> false);
        assertEquals(1, requests.get());
    }

    public void testPreparationCancellationPreventsFetchAndLateCacheWrites() throws Exception {
        var stopped = new java.util.concurrent.atomic.AtomicBoolean(true);
        var acquirer = preparedAcquirer(png(), () -> stopped.set(true));
        BackgroundArtworkPreparer.prepare(acquirer, directory, java.util.List.of(url), stopped::get);
        assertEquals(0, requests.get());
        stopped.set(false);
        BackgroundArtworkPreparer.prepare(acquirer, directory, java.util.List.of(url), stopped::get);
        assertEquals(1, requests.get());
        for (int size : new int[]{128, 512})
            assertNull(new ThumbnailDiskCache(directory).get(ThumbnailDiskCache.key(url.toString(), size), System.currentTimeMillis()));
    }

    public void testPreparationDeduplicatesAndBoundsCoverAcquisition() throws Exception {
        var sources = new java.util.ArrayList<CanonicalHttpsUrl>();
        for (int i = 0; i < 20; i++) sources.add(url);
        for (int i = 0; i < 12; i++) sources.add(CanonicalHttpsUrl.parse("https://artwork.example/cover-" + i + ".png"));
        BackgroundArtworkPreparer.prepare(preparedAcquirer(png(), () -> { }), directory, sources, () -> false);
        assertEquals(io.github.jnesew.plainpoddroid.core.security.SecurityLimits.MAX_PREPARATION_COVERS, requests.get());
    }

    private static byte[] png() {
        Bitmap source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, bytes)); source.recycle();
        return bytes.toByteArray();
    }

    private BoundedArtworkAcquirer preparedAcquirer(byte[] bytes, Runnable beforeResponse) {
        return new BoundedArtworkAcquirer(source -> {
            requests.incrementAndGet(); beforeResponse.run();
            return new io.github.jnesew.plainpoddroid.core.artwork.ArtworkTransport.Exchange() {
                @Override public int getStatusCode() { return 200; }
                @Override public String getHeader(String name) {
                    if (name.equalsIgnoreCase("Content-Type")) return "image/png";
                    if (name.equalsIgnoreCase("Content-Length")) return Integer.toString(bytes.length);
                    return null;
                }
                @Override public java.io.InputStream getBody() { return new java.io.ByteArrayInputStream(bytes); }
                @Override public void close() { }
            };
        });
    }
}
