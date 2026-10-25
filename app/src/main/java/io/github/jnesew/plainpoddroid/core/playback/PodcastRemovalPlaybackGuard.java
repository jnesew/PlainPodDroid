package io.github.jnesew.plainpoddroid.core.playback;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Resolve playback membership off-thread and recheck identity before stopping for removal. */
public final class PodcastRemovalPlaybackGuard {
    public interface Membership { long podcastId(String mediaId) throws IOException; }
    private final Executor worker,callback;
    private final Supplier<String> current;
    private final Membership membership;
    private final Runnable stop;
    public PodcastRemovalPlaybackGuard(Executor worker,Executor callback,Supplier<String> current,Membership membership,Runnable stop){
        this.worker=worker;this.callback=callback;this.current=current;this.membership=membership;this.stop=stop;
    }
    public void prepare(long podcastId,BooleanSupplier valid,Runnable ready,Runnable failed){
        check(podcastId,valid,ready,failed,0);
    }
    private void check(long podcastId,BooleanSupplier valid,Runnable ready,Runnable failed,int changes){
        if(!valid.getAsBoolean())return;
        String id=current.get();
        if(id.isEmpty()){ready.run();return;}
        if(changes>=3){failed.run();return;}
        try{worker.execute(()->{
            long owner;
            try{owner=membership.podcastId(id);}catch(IOException|RuntimeException e){callback.execute(()->{if(valid.getAsBoolean())failed.run();});return;}
            callback.execute(()->{
                if(!valid.getAsBoolean())return;
                if(!id.equals(current.get())){check(podcastId,valid,ready,failed,changes+1);return;}
                if(owner==podcastId)stop.run();
                ready.run();
            });
        });}catch(RejectedExecutionException e){if(valid.getAsBoolean())failed.run();}
    }
}
