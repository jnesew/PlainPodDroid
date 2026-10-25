package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue;
import io.github.jnesew.plainpoddroid.ui.UiWidgets;
import java.util.function.Consumer;

/** A compact request list above completed downloads; navigation never starts playback. */
final class ManualDownloadPanel {
    private final Activity activity;
    private final LinearLayout root;
    private final ManualDownloadManager manager;
    private final Consumer<String> open,retry;
    ManualDownloadPanel(Activity activity,ManualDownloadManager manager,Consumer<String> open,Consumer<String> retry){
        this.activity=activity;this.manager=manager;this.open=open;this.retry=retry;
        root=activity.findViewById(R.id.manual_downloads_rows);
    }
    void render(ManualDownloadManager.Snapshot snapshot){
        root.removeAllViews();
        root.setVisibility(snapshot.entries().isEmpty()&&snapshot.error()==ManualDownloadManager.Error.NONE?View.GONE:View.VISIBLE);
        if(root.getVisibility()==View.GONE)return;
        TextView heading=UiWidgets.text(activity,activity.getString(R.string.ui_pending_downloads),18,true);
        heading.setAccessibilityHeading(true);root.addView(heading);
        if(snapshot.error()!=ManualDownloadManager.Error.NONE){
            root.addView(UiWidgets.text(activity,activity.getString(snapshot.error()==ManualDownloadManager.Error.SCHEDULER
                    ?R.string.ui_download_start_failed:R.string.ui_download_load_failed),15,false));
            Button reload=new Button(activity);reload.setText(R.string.ui_retry);reload.setOnClickListener(v->manager.refresh());root.addView(reload);
        }
        for(var entry:snapshot.entries()){
            String id=entry.request().mediaId();
            String title=entry.title().isEmpty()?activity.getString(R.string.catalog_episode_untitled):entry.title();
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0,UiWidgets.dp(activity,12),0,UiWidgets.dp(activity,12));
            LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
            body.setFocusable(true);body.setMinimumHeight(UiWidgets.dp(activity,64));body.setBackgroundResource(android.R.drawable.list_selector_background);
            body.setOnClickListener(v->open.accept(id));
            TextView label=UiWidgets.text(activity,title,17,true);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);body.addView(label);
            TextView podcast=UiWidgets.text(activity,entry.podcastTitle(),14,false);podcast.setMaxLines(1);podcast.setEllipsize(TextUtils.TruncateAt.END);body.addView(podcast);
            TextView status=UiWidgets.text(activity,activity.getString(status(entry)),14,false);body.addView(status);
            row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
            if(entry.state()==ManualDownloadQueue.State.ACTIVE){
                ProgressBar progress=new ProgressBar(activity);progress.setContentDescription(activity.getString(R.string.ui_downloading));
                row.addView(progress,new LinearLayout.LayoutParams(UiWidgets.dp(activity,24),UiWidgets.dp(activity,24)));
            }
            if(entry.state()==ManualDownloadQueue.State.FAILED){
                Button again=new Button(activity);again.setText(R.string.ui_retry);
                again.setContentDescription(activity.getString(R.string.ui_retry_download_title,title));again.setOnClickListener(v->retry.accept(id));row.addView(again);
            }
            Button cancel=UiWidgets.iconButton(activity,R.drawable.ic_close,activity.getString(
                    entry.state()==ManualDownloadQueue.State.FAILED?R.string.ui_remove_download_request:R.string.ui_cancel_download_title,title),false);
            cancel.setOnClickListener(v->manager.cancel(id));row.addView(cancel);root.addView(row);
        }
        View divider=new View(activity);divider.setBackgroundColor(activity.getColor(R.color.divider));
        root.addView(divider,new LinearLayout.LayoutParams(-1,UiWidgets.dp(activity,1)));
    }
    private static int status(ManualDownloadQueue.Entry entry){
        return switch(entry.state()){
            case WAITING->R.string.ui_download_waiting;case ACTIVE->R.string.ui_downloading;
            case FAILED->switch(entry.failure()){
                case LOGIN_REQUIRED->R.string.login_needed;case ACCESS_DENIED->R.string.login_denied;
                case QUOTA_EXCEEDED->R.string.ui_download_no_space;
                case EPISODE_NOT_FOUND,TARGET_CHANGED->R.string.episode_download_error_missing;
                case STORAGE_FAILED->R.string.episode_download_error_storage;
                case OFFLINE_MODE->R.string.episode_download_error_offline;
                default->R.string.ui_download_failed;
            };
        };
    }
}
