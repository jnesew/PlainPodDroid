package io.github.jnesew.plainpoddroid.playback;

import io.github.jnesew.plainpoddroid.core.download.DownloadLookup;
import io.github.jnesew.plainpoddroid.core.download.VerifiedDownloadStore;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackRequestResolver;
import io.github.jnesew.plainpoddroid.core.playback.RemoteEpisode;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackResolutionWorkerTest {
    private static final VerifiedDownloadStore ABSENT=new VerifiedDownloadStore(){
        public DownloadLookup lookup(String id){return DownloadLookup.withoutFile(DownloadLookup.State.ABSENT);}
        public boolean isVerifiedCompleteUri(String uri){return false;}
    };
    private static RemoteEpisode episode(String id){return new RemoteEpisode(id,URI.create("https://media.example/audio.mp3"),"audio/mpeg","Episode","Author",0);}

    @Test public void slowCatalogRunsOffCallerAndCancelledQueuedRequestDoesNoLookup() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();Thread caller=Thread.currentThread();
        try(var worker=new PlaybackResolutionWorker(new PlaybackRequestResolver(()->false,ABSENT,id->{
            assertNotSame(caller,Thread.currentThread());calls.incrementAndGet();entered.countDown();
            try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            return episode(id);
        }))){
            var first=worker.resolve(List.of("first"));assertTrue(entered.await(3,TimeUnit.SECONDS));assertFalse(first.isDone());
            var cancelled=worker.resolve(List.of("cancelled"));assertTrue(cancelled.cancel(false));
            release.countDown();assertTrue(first.get(3,TimeUnit.SECONDS).isAllowed());
            assertTrue(worker.resolve(List.of("next")).get(3,TimeUnit.SECONDS).isAllowed());assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
    @Test public void queueIsBoundedAndCloseRetiresPendingAndRunningResolution() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var worker=new PlaybackResolutionWorker(new PlaybackRequestResolver(()->false,ABSENT,id->{
            entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return episode(id);
        }));
        try{
            var running=worker.resolve(List.of("first"));assertTrue(entered.await(3,TimeUnit.SECONDS));
            var queued=new ArrayList<com.google.common.util.concurrent.ListenableFuture<?>>();
            for(int i=0;i<4;i++)queued.add(worker.resolve(List.of("queued"+i)));
            assertThrows(ExecutionException.class,()->worker.resolve(List.of("overflow")).get(3,TimeUnit.SECONDS));
            worker.close();assertTrue(running.isCancelled());for(var future:queued)assertTrue(future.isCancelled());
            assertThrows(ExecutionException.class,()->worker.resolve(List.of("retired")).get());
        }finally{release.countDown();worker.close();}
    }
    @Test public void policyIsCheckedWhenWorkRunsAndInvalidBatchDoesNotTouchStorage() throws Exception {
        var calls=new AtomicInteger();
        try(var worker=new PlaybackResolutionWorker(new PlaybackRequestResolver(()->true,ABSENT,id->{calls.incrementAndGet();return episode(id);}))) {
            assertFalse(worker.resolve(List.of("remote")).get(3,TimeUnit.SECONDS).isAllowed());assertEquals(0,calls.get());
            assertThrows(ExecutionException.class,()->worker.resolve(List.of()).get());assertEquals(0,calls.get());
        }
    }
}
