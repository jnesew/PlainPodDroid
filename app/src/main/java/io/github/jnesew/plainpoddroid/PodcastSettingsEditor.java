package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.widget.Toast;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSummary;
import io.github.jnesew.plainpoddroid.ui.UiWidgets;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** ID-scoped speed, automatic-download and private-login editors with one activity lifetime. */
final class PodcastSettingsEditor implements AutoCloseable {
    interface Host {boolean canChangePodcast();boolean canEditFeedLogin();void changed();}
    private final Activity activity;
    private final PlainPodDroidApplication application;
    private final Executor worker;
    private final Handler main;
    private final Host host;
    private AlertDialog podcastOptionsDialog,feedLoginDialog,speedDialog;
    private int speedEditorGeneration,feedLoginGeneration;
    private boolean savingAutomaticDownload,closed;
    PodcastSettingsEditor(Activity activity,PlainPodDroidApplication application,Executor worker,Handler main,Host host){
        this.activity=activity;this.application=application;this.worker=worker;this.main=main;this.host=host;
    }
    boolean isSavingAutomaticDownload(){return savingAutomaticDownload;}
    void navigationChanged(){speedEditorGeneration++;}
    private boolean isUnavailable(){return closed||activity.isDestroyed();}
    private int dp(int size){return UiWidgets.dp(activity,size);}
    private String displayTitle(String title){return title.isEmpty()?activity.getString(R.string.catalog_episode_untitled):title;}
    @Override public void close(){
        closed=true;speedEditorGeneration++;feedLoginGeneration++;
        if(feedLoginDialog!=null)feedLoginDialog.dismiss();
        if(speedDialog!=null)speedDialog.dismiss();
        if(podcastOptionsDialog!=null)podcastOptionsDialog.dismiss();
    }
    void bindLoginAction(io.github.jnesew.plainpoddroid.ui.PodcastActionsMenu menu,CatalogSubscriptionSummary podcast){
        if(!podcast.isSubscribed()||podcast.getSourceUrl()==null)return;
        try{worker.execute(()->{
            io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState state;
            try{state=application.getFeedLoginStore().getPodcastLoginState(podcast.getSubscriptionId());}
            catch(IOException|RuntimeException e){return;}
            var result=state;
            main.post(()->{if(!isUnavailable())menu.setLoginState(result,host.canEditFeedLogin());});
        });}catch(RejectedExecutionException ignored){ }
    }

    void showPodcastSpeed(long podcastId) {
        if(isUnavailable()||speedDialog!=null)return;
        int ticket=++speedEditorGeneration;
        var app=application;
        try{worker.execute(()->{
            io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting selected;
            try{selected=app.getPodcastPlaybackSettingsStore().getPodcastSpeed(podcastId);}
            catch(IOException|RuntimeException e){main.post(()->{if(!isUnavailable()&&ticket==speedEditorGeneration)
                Toast.makeText(activity,R.string.ui_preference_save_failed,Toast.LENGTH_SHORT).show();});return;}
            var current=selected;
            main.post(()->{
                if(isUnavailable()||ticket!=speedEditorGeneration)return;
                var speeds=io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting.values();
                String[] labels=new String[speeds.length+1];
                labels[0]=activity.getString(R.string.ui_use_default_speed,speedLabel(new io.github.jnesew.plainpoddroid.playback.PlaybackControlSettings(activity).getPlaybackSpeed()));
                for(int i=0;i<speeds.length;i++)labels[i+1]=speedLabel(speeds[i]);
                speedDialog=new AlertDialog.Builder(activity).setTitle(R.string.ui_podcast_speed)
                    .setSingleChoiceItems(labels,current==null?0:current.ordinal()+1,(dialog,which)->{
                        dialog.dismiss();var chosen=which==0?null:speeds[which-1];
                        try{worker.execute(()->{
                            boolean saved;
                            try{app.getPodcastPlaybackSettingsStore().setPodcastSpeed(podcastId,chosen);saved=true;}
                            catch(IOException|RuntimeException e){saved=false;}
                            boolean success=saved;
                            main.post(()->{if(isUnavailable())return;
                                if(success)new io.github.jnesew.plainpoddroid.playback.PlaybackControlSettings(activity).notifyPodcastSettingsChanged();
                                else Toast.makeText(activity,R.string.ui_preference_save_failed,Toast.LENGTH_SHORT).show();});
                        });}catch(RejectedExecutionException e){Toast.makeText(activity,R.string.ui_preference_save_failed,Toast.LENGTH_SHORT).show();}
                    }).setNegativeButton(android.R.string.cancel,null).show();
                speedDialog.setOnDismissListener(dialog->speedDialog=null);
            });
        });}catch(RejectedExecutionException e){Toast.makeText(activity,R.string.ui_preference_save_failed,Toast.LENGTH_SHORT).show();}
    }

    private String speedLabel(io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting speed) {
        return java.text.NumberFormat.getNumberInstance().format(speed.getPercent()/100.0)+"×";
    }


    void showPodcastLogin(CatalogSubscriptionSummary podcast) {
        if(isUnavailable())return;
        if (!podcast.isSubscribed()||podcast.getSourceUrl()==null)return;
        if (!host.canEditFeedLogin()) { Toast.makeText(activity,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();return; }
        if (feedLoginDialog != null) return;
        int ticket = ++feedLoginGeneration;
        var application = this.application;
        try { worker.execute(() -> {
            BasicFeedLogin saved = null;
            boolean unavailable = false;
            try { saved = application.getFeedLoginStore().getPodcastLogin(podcast.getSubscriptionId()); }
            catch (IOException e) { unavailable = true; }
            var login = saved; boolean failedRead = unavailable;
            main.post(() -> {
                if (isUnavailable() || ticket != feedLoginGeneration) return;
                var editor = new io.github.jnesew.plainpoddroid.ui.FeedLoginEditor(activity, () -> { });
                editor.setPadding(dp(20), dp(8), dp(20), dp(8)); editor.setExisting(login);
                var scroll = new android.widget.ScrollView(activity); scroll.addView(editor);
                var dialog = new AlertDialog.Builder(activity).setTitle(R.string.ui_feed_login).setView(scroll)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.login_save, null).create();
                feedLoginDialog = dialog;
                dialog.setOnDismissListener(ignored -> { editor.clear(); feedLoginDialog = null; }); dialog.show();
                if (failedRead) Toast.makeText(activity, R.string.login_unavailable, Toast.LENGTH_LONG).show();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (!host.canEditFeedLogin()) { Toast.makeText(activity,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();return; }
                    final BasicFeedLogin replacement;
                    try { replacement = editor.read(podcast.getSourceUrl()); }
                    catch (IllegalArgumentException e) { Toast.makeText(activity, R.string.login_invalid, Toast.LENGTH_LONG).show(); return; }
                    editor.setBusy(true); dialog.setCancelable(false); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    try { worker.execute(() -> {
                        boolean success;
                        try { application.getFeedLoginStore().savePodcastLogin(podcast.getSubscriptionId(), replacement); success = true; }
                        catch (IOException | RuntimeException e) { success = false; }
                        boolean savedOk = success;
                        main.post(() -> {
                            if (isUnavailable()) return;
                            if (savedOk) { dialog.dismiss(); Toast.makeText(activity, R.string.login_saved, Toast.LENGTH_SHORT).show(); }
                            else { editor.setBusy(false); dialog.setCancelable(true); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                Toast.makeText(activity, R.string.login_save_failed, Toast.LENGTH_LONG).show(); }
                        });
                    }); } catch (RejectedExecutionException e) { editor.setBusy(false); dialog.setCancelable(true); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
                });
            });
        }); } catch (RejectedExecutionException e) { Toast.makeText(activity, R.string.login_save_failed, Toast.LENGTH_LONG).show(); }
    }


    void showPodcastSettings(CatalogSubscriptionSummary podcast) {
        if(isUnavailable())return;
        if(podcastOptionsDialog!=null)return;
        var content=new io.github.jnesew.plainpoddroid.ui.PodcastSettingsView(activity,podcast,
                host.canChangePodcast(),host.canEditFeedLogin(),()->showPodcastSpeed(podcast.getSubscriptionId()),
                ()->showPodcastLogin(podcast),(enabled,view)->savePodcastAutomaticDownload(podcast.getSubscriptionId(),enabled,view));
        var scroll=new android.widget.ScrollView(activity);scroll.addView(content);
        podcastOptionsDialog=new AlertDialog.Builder(activity).setTitle(R.string.ui_podcast_options).setView(scroll)
                .setPositiveButton(android.R.string.ok,null).create();
        podcastOptionsDialog.setOnDismissListener(dialog->podcastOptionsDialog=null);podcastOptionsDialog.show();
    }

    private void savePodcastAutomaticDownload(long id,boolean enabled,io.github.jnesew.plainpoddroid.ui.PodcastSettingsView view){
        if(isUnavailable())return;
        if(!host.canChangePodcast()){view.finishAutomaticDownload(!enabled,false);Toast.makeText(activity,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();return;}
        savingAutomaticDownload=true;view.setAutomaticDownloadBusy();
        try{worker.execute(()->{
            boolean saved;try{saved=application.getAutomaticDownloadSettingsStore().setAutomaticDownload(id,enabled);}
            catch(IOException|RuntimeException e){saved=false;}
            boolean success=saved;main.post(()->{
                savingAutomaticDownload=false;if(isUnavailable())return;
                view.finishAutomaticDownload(success?enabled:!enabled,host.canChangePodcast());
                if(success)host.changed();else Toast.makeText(activity,R.string.automatic_download_save_failed,Toast.LENGTH_SHORT).show();
            });
        });}catch(RejectedExecutionException e){savingAutomaticDownload=false;view.finishAutomaticDownload(!enabled,host.canChangePodcast());
            Toast.makeText(activity,R.string.automatic_download_save_failed,Toast.LENGTH_SHORT).show();}
    }

}
