package io.github.jnesew.plainpoddroid.core.playback;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Resolves saved speed off the player thread and drops obsolete results. */
public final class PodcastPlaybackSpeedCoordinator implements AutoCloseable {
    private final PodcastPlaybackSettingsStore store;
    private final Executor worker,callback;
    private final Consumer<PlaybackSpeedSetting> apply;
    private long generation;
    private boolean closed;
    public PodcastPlaybackSpeedCoordinator(PodcastPlaybackSettingsStore store,Executor worker,
            Executor callback,Consumer<PlaybackSpeedSetting> apply) {
        this.store=Objects.requireNonNull(store);this.worker=Objects.requireNonNull(worker);
        this.callback=Objects.requireNonNull(callback);this.apply=Objects.requireNonNull(apply);
    }
    public void select(String mediaId,PlaybackSpeedSetting fallback) {
        Objects.requireNonNull(mediaId);Objects.requireNonNull(fallback);
        if(!mediaId.isEmpty())PlaybackProgressUpdate.requireMediaId(mediaId);
        final long ticket;
        synchronized(this){if(closed)return;ticket=++generation;}
        if(mediaId.isEmpty()){post(ticket,fallback);return;}
        try{worker.execute(()->{
            PlaybackSpeedSetting value;
            try{value=store.findSpeedForEpisode(mediaId);}catch(IOException|RuntimeException e){value=null;}
            post(ticket,value==null?fallback:value);
        });}catch(RuntimeException e){post(ticket,fallback);}
    }
    private void post(long ticket,PlaybackSpeedSetting value) {
        try{callback.execute(()->{
            synchronized(this){if(closed||ticket!=generation)return;apply.accept(value);}
        });}catch(RuntimeException ignored){ }
    }
    @Override public synchronized void close(){closed=true;generation++;}
}
