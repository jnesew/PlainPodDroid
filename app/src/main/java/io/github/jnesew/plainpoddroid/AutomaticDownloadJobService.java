package io.github.jnesew.plainpoddroid;

import android.app.job.JobParameters;
import android.app.job.JobService;
import io.github.jnesew.plainpoddroid.core.download.AutomaticDownloadRunner;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual and automatic jobs share one application-owned transfer lane. */
public final class AutomaticDownloadJobService extends JobService {
    private final Object owner=new Object();
    private final Map<Integer,ActiveRun> active=new HashMap<>();
    @Override public boolean onStartJob(JobParameters parameters){
        int id=parameters.getJobId();
        if(id!=ManualDownloadScheduler.JOB_ID&&id!=AutomaticDownloadScheduler.JOB_ID)return false;
        var app=(PlainPodDroidApplication)getApplication();
        if(app.getEpisodeDownloadActions()==null){app.getManualDownloads().refresh();return false;}
        synchronized(owner){
            if(active.containsKey(id))return true;
            ActiveRun run=new ActiveRun(parameters);active.put(id,run);
            run.future=app.getDownloadJobExecutor().submit(()->runJob(app,run));
        }
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){
        synchronized(owner){stop(active.remove(parameters.getJobId()));}return true;
    }
    @Override public void onDestroy(){
        synchronized(owner){for(ActiveRun run:active.values())stop(run);active.clear();}
        // The application lane outlives this service, so an old cancelled transfer
        // finishes cleanup before a recreated service can claim another request.
        super.onDestroy();
    }
    private static void stop(ActiveRun run){if(run!=null){run.stopped.set(true);if(run.future!=null)run.future.cancel(true);}}
    private void runJob(PlainPodDroidApplication app,ActiveRun run){
        boolean reschedule=false;
        try{
            if(run.parameters.getJobId()==ManualDownloadScheduler.JOB_ID)
                reschedule=app.getManualDownloadRunner().run(run.stopped::get);
            else {
                boolean more=new AutomaticDownloadRunner(app.getAutomaticDownloadQueue(),app.getEpisodeDownloadActions())
                        .run(()->run.stopped.get()||app.hasWaitingPlaybackDownload());
                if(!run.stopped.get()&&more)reschedule=!app.getAutomaticDownloadScheduler().scheduleLater();
            }
        }catch(IOException|RuntimeException e){reschedule=true;}
        finally{
            synchronized(owner){if(active.get(run.parameters.getJobId())==run)active.remove(run.parameters.getJobId());}
            app.getManualDownloads().refresh();
            if(!run.stopped.get())jobFinished(run.parameters,reschedule);
        }
    }
    private static final class ActiveRun {
        final JobParameters parameters;final AtomicBoolean stopped=new AtomicBoolean();Future<?> future;
        ActiveRun(JobParameters parameters){this.parameters=parameters;}
    }
}
