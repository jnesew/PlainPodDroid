package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.widget.Toast;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest;
import io.github.jnesew.plainpoddroid.core.episode.PodcastPlayedBatch;
import io.github.jnesew.plainpoddroid.ui.EpisodeListView;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Explicit batch UI and work lifetime, separate from playback and navigation. */
final class EpisodeBatchUiController implements AutoCloseable {
    interface Host {
        boolean canMutate();
        boolean queueDownloads(List<EpisodeListView.Row> rows);
        void clearSelection();
        void changed();
    }
    private final Activity activity;
    private final PlainPodDroidApplication app;
    private final Executor worker;
    private final Handler main;
    private final Host host;
    private boolean busy,closed;
    private int previewGeneration;
    EpisodeBatchUiController(Activity activity,Executor worker,Handler main,Host host){
        this.activity=activity;this.app=(PlainPodDroidApplication)activity.getApplication();
        this.worker=worker;this.main=main;this.host=host;
    }
    boolean isBusy(){return busy;}
    void navigationChanged(){previewGeneration++;}
    void perform(List<EpisodeListView.Row> rows,EpisodeBatchRequest.Action action){
        if(closed||busy)return;
        final EpisodeBatchRequest request;
        try{request=new EpisodeBatchRequest(rows.stream().map(EpisodeListView.Row::id).collect(java.util.stream.Collectors.toList()));}
        catch(IllegalArgumentException|NullPointerException e){message(R.string.ui_batch_failed);return;}
        if(action==EpisodeBatchRequest.Action.DOWNLOAD){
            List<EpisodeListView.Row> eligible=rows.stream().filter(row->!row.downloaded()).collect(java.util.stream.Collectors.toList());
            if(eligible.isEmpty())return;
            if(host.queueDownloads(eligible))host.clearSelection();return;
        }
        if(!host.canMutate())return;
        if(action==EpisodeBatchRequest.Action.MARK_UNPLAYED&&rows.stream().anyMatch(row->row.position()>0)){
            new AlertDialog.Builder(activity).setMessage(activity.getResources().getQuantityString(
                    R.plurals.ui_confirm_mark_unplayed,request.mediaIds().size(),request.mediaIds().size()))
                    .setPositiveButton(R.string.episode_mark_unplayed_action,(dialog,which)->start(request,action))
                    .setNegativeButton(android.R.string.cancel,null).show();
        }else start(request,action);
    }
    private void start(EpisodeBatchRequest request,EpisodeBatchRequest.Action action){
        if(closed||busy||!host.canMutate())return;
        busy=true;host.clearSelection();
        try{worker.execute(()->{
            int completed=0;
            if(action==EpisodeBatchRequest.Action.MARK_PLAYED||action==EpisodeBatchRequest.Action.MARK_UNPLAYED){
                try{completed=app.getLibraryCatalog().setPlayedBatch(request,action==EpisodeBatchRequest.Action.MARK_PLAYED);}
                catch(IOException|RuntimeException ignored){ }
            }else{
                try{completed=app.getLibraryCatalog().queueBatch(request,action==EpisodeBatchRequest.Action.QUEUE_NEXT);}
                catch(IOException|RuntimeException ignored){ }

            }
            int count=completed;
            main.post(()->{if(closed||activity.isDestroyed())return;busy=false;host.changed();result(action,count,request.mediaIds().size());});
        });}catch(RejectedExecutionException e){busy=false;message(R.string.ui_batch_failed);}
    }
    void previewPodcast(long podcastId,String olderThanId){
        if(closed||busy||!host.canMutate())return;
        int ticket=++previewGeneration;
        try{worker.execute(()->{
            PodcastPlayedBatch scope;boolean failed;
            try{scope=app.getLibraryCatalog().preparePlayedBatch(podcastId,olderThanId);failed=false;}
            catch(IOException|RuntimeException e){scope=null;failed=true;}
            var preview=scope;boolean error=failed;
            main.post(()->{
                if(closed||activity.isDestroyed()||ticket!=previewGeneration)return;
                if(error){message(R.string.ui_batch_failed);return;}
                if(preview==null){message(R.string.ui_no_episode_date);return;}
                if(preview.count()==0){message(R.string.ui_no_unplayed_episodes);return;}
                new AlertDialog.Builder(activity).setMessage(activity.getResources().getQuantityString(
                        R.plurals.ui_confirm_mark_played,preview.count(),preview.count()))
                        .setPositiveButton(R.string.episode_mark_played_action,(dialog,which)->markPodcast(preview))
                        .setNegativeButton(android.R.string.cancel,null).show();
            });
        });}catch(RejectedExecutionException e){message(R.string.ui_batch_failed);}
    }
    private void markPodcast(PodcastPlayedBatch scope){
        if(closed||busy||!host.canMutate())return;
        busy=true;host.clearSelection();
        try{worker.execute(()->{
            int completed;try{completed=app.getLibraryCatalog().markPodcastPlayed(scope);}
            catch(IOException|RuntimeException e){completed=-1;}
            int count=completed;
            main.post(()->{if(closed||activity.isDestroyed())return;busy=false;host.changed();
                if(count<0)message(R.string.ui_batch_failed);
                else if(count==0)message(R.string.ui_no_unplayed_episodes);
                else result(EpisodeBatchRequest.Action.MARK_PLAYED,count,count);});
        });}catch(RejectedExecutionException e){busy=false;message(R.string.ui_batch_failed);}
    }
    private void result(EpisodeBatchRequest.Action action,int done,int total){
        int label=switch(action){
            case MARK_PLAYED->R.plurals.ui_batch_marked_played;case MARK_UNPLAYED->R.plurals.ui_batch_marked_unplayed;
            case QUEUE_LAST,QUEUE_NEXT->R.plurals.ui_batch_queued;case DOWNLOAD->R.plurals.ui_batch_downloads;
        };
        Toast.makeText(activity,done==total?activity.getResources().getQuantityString(label,done,done)
                :activity.getResources().getQuantityString(R.plurals.ui_batch_result,total,done,total),Toast.LENGTH_SHORT).show();
    }
    private void message(int label){Toast.makeText(activity,label,Toast.LENGTH_SHORT).show();}
    @Override public void close(){closed=true;previewGeneration++;}
}
