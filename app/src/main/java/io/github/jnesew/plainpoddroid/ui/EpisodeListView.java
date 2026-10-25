package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.artwork.ArtworkThumbnailLoader;
import io.github.jnesew.plainpoddroid.core.catalog.PodcastContext;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest;
import java.util.function.Predicate;

/** Compact rows shared by episode lists. Body, play, menu and reorder are separate callbacks. */
public final class EpisodeListView {
    public record Row(String id, String title, String caption, PodcastContext podcast,
            boolean downloaded, boolean played, long duration, long position, int queuePosition) { }
    public enum DownloadState { AVAILABLE, WAITING, ACTIVE, FAILED, COMPLETE, UNAVAILABLE }
    public interface Actions {
        void open(String id);
        void play(String id);
        void menu(Row row, View anchor);
        void move(String id, int position);
        boolean canMove();
        default DownloadState downloadState(Row row) { return row.downloaded()?DownloadState.COMPLETE:DownloadState.AVAILABLE; }
        default void download(Row row, View anchor) { }
        default void bulk(List<Row> rows,EpisodeBatchRequest.Action action) { }
    }
    private record Binding(Row data,LinearLayout row,Button play,Button download,TextView downloadStatus,ImageView art,CheckBox check,View grip) { }
    private final Context context;
    private final LinearLayout rows;
    private final ArtworkThumbnailLoader artwork;
    private final Runnable artworkReady;
    private final Actions actions;
    private final Predicate<String> playing;
    private final List<Binding> bindings = new ArrayList<>();
    private final QueueRowDrag drag;
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private ActionMode selectionMode;

    public EpisodeListView(Context context, LinearLayout rows, ArtworkThumbnailLoader artwork,
            Runnable artworkReady, Actions actions, Predicate<String> playing) {
        this.context=context;this.rows=rows;this.artwork=artwork;
        this.artworkReady=artworkReady;this.actions=actions;this.playing=playing;
        drag=new QueueRowDrag(rows,()->selectionMode==null&&actions.canMove(),actions::move);
    }
    public void clear() { cancelSelection();drag.clear();bindings.clear();rows.removeAllViews(); }
    public void cancelDrag() { drag.cancel(); }
    public boolean isSelecting(){return selectionMode!=null;}
    public void cancelSelection(){ActionMode previous=selectionMode;selectionMode=null;selected.clear();
        if(previous!=null)previous.finish();refreshPlayback();}
    public boolean beginSelection() {
        if(bindings.isEmpty())return false;
        if(selectionMode==null){
            drag.cancel();selectionMode=rows.startActionMode(new ActionMode.Callback(){
                public boolean onCreateActionMode(ActionMode mode,Menu menu){
                    int[] labels={R.string.episode_mark_played_action,R.string.episode_mark_unplayed_action,
                            R.string.episode_queue_append_action,R.string.episode_queue_play_next_action,R.string.episode_download_action};
                    for(int i=0;i<labels.length;i++)menu.add(Menu.NONE,i+1,i,labels[i]);return true;
                }
                public boolean onPrepareActionMode(ActionMode mode,Menu menu){for(int i=0;i<menu.size();i++)menu.getItem(i).setEnabled(!selected.isEmpty());return true;}
                public boolean onActionItemClicked(ActionMode mode,MenuItem item){
                    int index=item.getItemId()-1;
                    if(index<0||index>=EpisodeBatchRequest.Action.values().length)return false;
                    List<Row> snapshot=new ArrayList<>();for(Binding b:bindings)if(selected.contains(b.data().id()))snapshot.add(b.data());
                    if(!snapshot.isEmpty())actions.bulk(List.copyOf(snapshot),EpisodeBatchRequest.Action.values()[index]);return true;
                }
                public void onDestroyActionMode(ActionMode mode){selectionMode=null;selected.clear();refreshPlayback();}
            });
            if(selectionMode==null)return false;
        }
        refreshSelection();return true;
    }
    public boolean select(String id) {
        if(bindings.stream().noneMatch(b->b.data().id().equals(id))||!beginSelection())return false;
        if(!selected.contains(id)&&selected.size()>=EpisodeBatchRequest.MAX_EPISODES){
            android.widget.Toast.makeText(context,context.getResources().getQuantityString(R.plurals.ui_selection_limit,EpisodeBatchRequest.MAX_EPISODES,EpisodeBatchRequest.MAX_EPISODES),android.widget.Toast.LENGTH_SHORT).show();return false;
        }
        selected.add(id);refreshSelection();return true;
    }
    private void toggleSelection(String id){if(selected.remove(id)){if(selected.isEmpty())cancelSelection();else refreshSelection();}else select(id);}
    private void refreshSelection(){if(selectionMode!=null){selectionMode.setTitle(context.getResources().getQuantityString(R.plurals.ui_selected_count,selected.size(),selected.size()));selectionMode.invalidate();}refreshPlayback();}
    public boolean hasRows() { return !bindings.isEmpty(); }
    public void heading(String text) {
        TextView title=UiWidgets.text(context,text,16,true);
        title.setPadding(0,UiWidgets.dp(context,20),0,UiWidgets.dp(context,8));
        title.setAccessibilityHeading(true);rows.addView(title);
    }
    public void add(Row data, boolean showCover, boolean queue) {
        LinearLayout container=new LinearLayout(context);container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiWidgets.dp(context,4),UiWidgets.dp(context,12),UiWidgets.dp(context,4),UiWidgets.dp(context,12));
        Button grip=null;
        if(queue) {
            grip=UiWidgets.iconButton(context,R.drawable.ic_reorder,
                    context.getString(R.string.ui_reorder,data.title()),false);
            grip.setId(R.id.episode_row_grip);row.addView(grip);
        }
        CheckBox check=new CheckBox(context);check.setVisibility(View.GONE);
        check.setMinWidth(UiWidgets.dp(context,48));check.setMinHeight(UiWidgets.dp(context,48));
        check.setContentDescription(context.getString(R.string.ui_select_episode,data.title()));
        check.setOnClickListener(v->toggleSelection(data.id()));row.addView(check);
        LinearLayout body=new LinearLayout(context);body.setGravity(Gravity.CENTER_VERTICAL);
        body.setId(R.id.episode_row_body);
        body.setMinimumHeight(UiWidgets.dp(context,64));body.setFocusable(true);
        body.setBackgroundResource(android.R.drawable.list_selector_background);
        body.setOnClickListener(v->{if(selectionMode!=null)toggleSelection(data.id());else actions.open(data.id());});
        if(!queue)body.setOnLongClickListener(v->{actions.menu(data,v);return true;});
        ImageView image=null;
        if(showCover){image=UiWidgets.artwork(context,56);body.addView(image);}
        LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(showCover?UiWidgets.dp(context,12):UiWidgets.dp(context,8),0,UiWidgets.dp(context,8),0);
        labels.addView(label(data.title(),18,true,2));
        if(showCover&&data.podcast()!=null)labels.addView(label(data.podcast().title(),15,false,1));
        LinearLayout metadata=new LinearLayout(context);metadata.setGravity(Gravity.CENTER_VERTICAL);
        if(!data.caption().isEmpty())metadata.addView(label(data.caption(),14,false,1),new LinearLayout.LayoutParams(0,-2,1));
        if(data.played())statusIcon(metadata,R.drawable.ic_check,R.string.catalog_episode_played,R.id.episode_row_played);
        if(queue&&data.downloaded())statusIcon(metadata,R.drawable.ic_download,R.string.catalog_episode_downloaded,R.id.episode_row_downloaded);
        if(metadata.getChildCount()>0)labels.addView(metadata,new LinearLayout.LayoutParams(-1,-2));
        if(!data.played()&&data.duration()>0&&data.position()>0&&data.position()<data.duration()) {
            ProgressBar progress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);
            progress.setId(R.id.episode_row_progress);progress.setMax(1000);
            progress.setProgress((int)Math.min(1000d,1000d*data.position()/data.duration()));
            progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,UiWidgets.dp(context,3));
            size.topMargin=UiWidgets.dp(context,6);labels.addView(progress,size);
        }
        body.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        TextView downloadStatus=null;
        if(!queue){downloadStatus=UiWidgets.text(context,"",14,false);downloadStatus.setId(R.id.episode_row_download_status);
            downloadStatus.setVisibility(View.GONE);labels.addView(downloadStatus);}
        Button play=null,download=null;
        if(queue) {
            Button menu=UiWidgets.iconButton(context,R.drawable.ic_more,context.getString(R.string.ui_row_options,data.title()),false);
            menu.setId(R.id.episode_row_menu);
            menu.setOnClickListener(v->actions.menu(data,v));row.addView(menu);
        } else {
            LinearLayout controls=new LinearLayout(context);
            var configuration=context.getResources().getConfiguration();
            if(configuration.fontScale>=1.3f||configuration.screenWidthDp<360)controls.setOrientation(LinearLayout.VERTICAL);
            play=UiWidgets.iconButton(context,R.drawable.ic_play,context.getString(R.string.episode_playback_description,data.title()),true);
            play.setId(R.id.episode_row_play);
            play.setOnClickListener(v->actions.play(data.id()));controls.addView(play);
            download=UiWidgets.iconButton(context,R.drawable.ic_download,context.getString(R.string.ui_download_episode,data.title()),false);
            download.setId(R.id.episode_row_download);
            download.setOnClickListener(v->actions.download(data,v));controls.addView(download);row.addView(controls);
        }
        container.addView(row,new LinearLayout.LayoutParams(-1,-2));
        View line=new View(context);line.setBackgroundColor(context.getColor(R.color.divider));
        container.addView(line,new LinearLayout.LayoutParams(-1,UiWidgets.dp(context,1)));
        rows.addView(container,new LinearLayout.LayoutParams(-1,-2));
        bindings.add(new Binding(data,row,play,download,downloadStatus,image,check,grip));
        if(queue)drag.add(data.id(),data.title(),data.queuePosition(),container,grip);
    }
    private TextView label(String text,int size,boolean bold,int lines) {
        TextView view=UiWidgets.text(context,text,size,bold);
        view.setMaxLines(lines);view.setEllipsize(TextUtils.TruncateAt.END);return view;
    }
    private void statusIcon(LinearLayout parent,int drawable,int description,int id) {
        ImageView icon=new ImageView(context);icon.setId(id);icon.setImageResource(drawable);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_secondary)));
        icon.setContentDescription(context.getString(description));
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(UiWidgets.dp(context,18),UiWidgets.dp(context,18));
        size.setMarginStart(UiWidgets.dp(context,6));parent.addView(icon,size);
    }
    public void refreshArtwork() {
        android.graphics.Rect visible=new android.graphics.Rect();
        if(rows.getWindowVisibility()!=View.VISIBLE||!rows.getLocalVisibleRect(visible))return;
        for(Binding b:bindings)if(b.art()!=null){
            View container=(View)b.row().getParent();
            if(container.getHeight()>0&&container.getBottom()>visible.top&&container.getTop()<visible.bottom)
                UiWidgets.setArtwork(b.art(),artwork.getOrRequest(b.data().podcast()==null?null:b.data().podcast().artworkUrl(),artworkReady));
        }
    }
    public void refreshPlayback() {
        for(Binding b:bindings) {
            boolean active=playing.test(b.data().id());
            boolean checked=selected.contains(b.data().id());
            b.check().setVisibility(selectionMode==null?View.GONE:View.VISIBLE);b.check().setChecked(checked);
            b.row().setSelected(checked);b.row().setBackgroundResource(checked||active?R.drawable.button_surface:0);
            if(b.grip()!=null)b.grip().setVisibility(selectionMode==null?View.VISIBLE:View.GONE);
            if(b.download()!=null){
                DownloadState state=actions.downloadState(b.data());
                int icon=switch(state){case WAITING,ACTIVE->R.drawable.ic_close;case FAILED->R.drawable.ic_replay;
                    case COMPLETE->R.drawable.ic_check;default->R.drawable.ic_download;};
                int label=switch(state){case WAITING->R.string.ui_cancel_queued_download;case ACTIVE->R.string.ui_cancel_download_title;
                    case FAILED->R.string.ui_retry_download_title;case COMPLETE->R.string.ui_manage_download_title;default->R.string.ui_download_episode;};
                UiWidgets.icon(b.download(),icon,context.getString(label,b.data().title()),false);
                b.download().setEnabled(selectionMode==null&&state!=DownloadState.UNAVAILABLE);
                b.download().setTooltipText(b.download().getContentDescription());
                b.downloadStatus().setVisibility(state==DownloadState.ACTIVE||state==DownloadState.WAITING||state==DownloadState.FAILED?View.VISIBLE:View.GONE);
                b.downloadStatus().setText(state==DownloadState.ACTIVE?R.string.ui_downloading:state==DownloadState.WAITING?R.string.ui_download_waiting:R.string.ui_retry_download);
            }
            if(b.play()!=null)b.play().setEnabled(selectionMode==null);
            if(b.play()!=null)UiWidgets.icon(b.play(),active?R.drawable.ic_pause:R.drawable.ic_play,
                    context.getString(active?R.string.playback_pause_description:R.string.episode_playback_description,b.data().title()),true);
        }
    }
}
