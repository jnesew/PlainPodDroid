package io.github.jnesew.plainpoddroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest;
import io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Application-owned short database work and UI snapshots; transfers belong to JobScheduler. */
final class ManualDownloadManager {
    enum Error { NONE, OFFLINE, FULL, UNAVAILABLE, STORAGE, SCHEDULER }
    record Result(int accepted,int total,Error error) { }
    record Snapshot(List<ManualDownloadQueue.Entry> entries,Error error) {
        Snapshot {entries=List.copyOf(entries);Objects.requireNonNull(error);}
    }
    private final ManualDownloadQueue queue;
    private final ManualDownloadScheduler scheduler;
    private final boolean storageAvailable;
    private final PlaybackPrivacySettings privacy;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"manual-download-state"));
    private final Set<Runnable> listeners=new LinkedHashSet<>();
    private volatile Snapshot snapshot=new Snapshot(List.of(),Error.NONE);
    private final SharedPreferences.OnSharedPreferenceChangeListener privacyListener;
    ManualDownloadManager(Context context,ManualDownloadQueue queue,boolean storageAvailable) {
        this.queue=queue;this.storageAvailable=storageAvailable;scheduler=new ManualDownloadScheduler(context);
        privacy=new PlaybackPrivacySettings(context);
        privacyListener=(prefs,key)->{if(PlaybackPrivacySettings.isNeverStreamPreference(key))refresh();};
        privacy.registerListener(privacyListener);refresh();
    }
    Snapshot snapshot(){return snapshot;}
    void addListener(Runnable listener){listeners.add(listener);listener.run();}
    void removeListener(Runnable listener){listeners.remove(listener);}
    ManualDownloadQueue.Entry find(String id){
        for(var entry:snapshot.entries())if(entry.request().mediaId().equals(id))return entry;return null;
    }
    boolean isPending(String id){var entry=find(id);return entry!=null&&entry.state()!=ManualDownloadQueue.State.FAILED;}
    void enqueue(List<String> ids,Consumer<Result> result){
        var request=new EpisodeBatchRequest(ids);
        worker.execute(()->{
            int accepted=0;Error error=Error.NONE;
            if(privacy.isNeverStreamAudioEnabled())error=Error.OFFLINE;
            else if(!storageAvailable)error=Error.STORAGE;
            else for(String id:request.mediaIds()){
                try {var value=queue.enqueueManualDownload(id);
                    if(value==ManualDownloadQueue.Enqueued.ADDED||value==ManualDownloadQueue.Enqueued.EXISTING)accepted++;
                    else if(value==ManualDownloadQueue.Enqueued.FULL)error=Error.FULL;
                    else if(value==ManualDownloadQueue.Enqueued.UNAVAILABLE)error=Error.UNAVAILABLE;
                }catch(IOException|RuntimeException e){error=Error.STORAGE;break;}
            }
            refreshOnWorker();int count=accepted;Error failure=error;
            main.post(()->result.accept(new Result(count,request.mediaIds().size(),failure)));
        });
    }
    void cancel(String id){ManualDownloadQueue.requireMediaId(id);worker.execute(()->{
        try{queue.cancelManualDownload(id);refreshOnWorker();}
        catch(IOException|RuntimeException e){publish(new Snapshot(snapshot.entries(),Error.STORAGE));}
    });}
    void refresh(){worker.execute(this::refreshOnWorker);}
    private void refreshOnWorker(){
        try {
            if(privacy.isNeverStreamAudioEnabled())queue.clearPendingManualDownloads();
            if(!storageAvailable)queue.failPendingManualDownloads(EpisodeDownloadException.Reason.STORAGE_FAILED);
            queue.clearCompletedManualDownloads();
            var entries=queue.listManualDownloads();
            boolean waiting=entries.stream().anyMatch(e->e.state()!=ManualDownloadQueue.State.FAILED);
            Error error=waiting&&!scheduler.scheduleSoon()?Error.SCHEDULER:Error.NONE;
            publish(new Snapshot(entries,error));
        }catch(IOException|RuntimeException e){publish(new Snapshot(snapshot.entries(),Error.STORAGE));}
    }
    private void publish(Snapshot next){main.post(()->{
        if(snapshot.equals(next))return;snapshot=next;
        for(Runnable listener:List.copyOf(listeners))listener.run();
    });}
}
