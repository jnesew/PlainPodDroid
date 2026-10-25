package io.github.jnesew.plainpoddroid.playback;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackBatchResolution;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackRequestResolver;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Session-owned bounded worker: file/catalog lookups never run in a session callback. */
final class PlaybackResolutionWorker implements AutoCloseable {
    private final PlaybackRequestResolver resolver;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4),r->new Thread(r,"playback-source-resolution"));
    private final Set<ListenableFutureTask<PlaybackBatchResolution>> pending=new HashSet<>();
    private boolean closed;

    PlaybackResolutionWorker(PlaybackRequestResolver resolver){this.resolver=java.util.Objects.requireNonNull(resolver);}

    synchronized ListenableFuture<PlaybackBatchResolution> resolve(List<String> ids){
        if(closed)return Futures.immediateFailedFuture(new IllegalStateException("Playback session is closed."));
        if(ids==null||ids.isEmpty()||ids.size()>SecurityLimits.MAX_QUEUE_ENTRIES)
            return Futures.immediateFailedFuture(new IllegalArgumentException("Invalid playback request."));
        List<String> snapshot=new ArrayList<>(ids);
        var task=ListenableFutureTask.create(()->resolver.resolveAll(snapshot));
        pending.add(task);
        task.addListener(()->finished(task),MoreExecutors.directExecutor());
        try{executor.execute(task);}catch(RejectedExecutionException e){
            task.cancel(false);return Futures.immediateFailedFuture(new IllegalStateException("Playback requests are busy."));
        }
        return task;
    }

    private synchronized void finished(ListenableFutureTask<PlaybackBatchResolution> task){
        pending.remove(task);executor.remove(task);
    }

    @Override public synchronized void close(){
        closed=true;
        for(var task:List.copyOf(pending))task.cancel(true);
        executor.shutdownNow();
    }
}
