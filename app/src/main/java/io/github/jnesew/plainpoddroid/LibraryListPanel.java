package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.artwork.ArtworkThumbnailLoader;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisodeSummary;
import io.github.jnesew.plainpoddroid.core.catalog.DownloadedEpisode;
import io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.ui.NavigationState.Destination;
import io.github.jnesew.plainpoddroid.ui.EpisodeListView;
import io.github.jnesew.plainpoddroid.ui.EpisodeMetadataFormatter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/** Destination-owned bounded local lists, with generation guards for obsolete callbacks. */
final class LibraryListPanel implements AutoCloseable {
    private record Item(CatalogEpisodeSummary episode, long when, long bytes) { }
    private final Activity activity;
    private final Destination destination;
    private final PlainPodDroidApplication application;
    private final Executor worker;
    private final Handler main;
    private final EpisodeListView list;
    private final EpisodeMetadataFormatter metadata;
    private final TextView summary,pageSummary;
    private final View progress;
    private final Button previous,next;
    private int offset;
    private volatile int generation;
    private volatile boolean visible,closed;
    private boolean busy,hasMore;
    private final Runnable refreshListening = new Runnable() {
        @Override public void run() {
            if (closed || !visible) return;
            if (!busy&&!list.isSelecting()) load();
            main.postDelayed(this, SecurityLimits.PLAYBACK_PROGRESS_WRITE_INTERVAL_MILLIS);
        }
    };

    LibraryListPanel(Activity activity, Destination destination, Executor worker, Handler main,
            ArtworkThumbnailLoader artwork, Runnable artworkReady, EpisodeListView.Actions actions,
            Predicate<String> playing, Bundle saved) {
        this.activity=activity;this.destination=destination;this.worker=worker;this.main=main;
        application=(PlainPodDroidApplication)activity.getApplication();
        metadata=new EpisodeMetadataFormatter(activity::getString);
        int summaryId,progressId,rowsId,previousId,nextId,pageSummaryId;
        switch(destination) {
            case DOWNLOADS -> {summaryId=R.id.downloads_summary;progressId=R.id.downloads_progress;rowsId=R.id.downloads_rows;previousId=R.id.downloads_previous;nextId=R.id.downloads_next;}
            case FAVORITES -> {summaryId=R.id.favorites_summary;progressId=R.id.favorites_progress;rowsId=R.id.favorites_rows;previousId=R.id.favorites_previous;nextId=R.id.favorites_next;}
            case HISTORY -> {summaryId=R.id.history_summary;progressId=R.id.history_progress;rowsId=R.id.history_rows;previousId=R.id.history_previous;nextId=R.id.history_next;}
            case NEW_EPISODES -> {summaryId=R.id.new_episodes_summary;progressId=R.id.new_episodes_progress;rowsId=R.id.new_episodes_rows;previousId=R.id.new_episodes_previous;nextId=R.id.new_episodes_next;}
            case IN_PROGRESS -> {summaryId=R.id.in_progress_summary;progressId=R.id.in_progress_progress;rowsId=R.id.in_progress_rows;previousId=R.id.in_progress_previous;nextId=R.id.in_progress_next;}
            default -> throw new IllegalArgumentException("Unsupported library destination.");
        }
        summary=activity.findViewById(summaryId);progress=activity.findViewById(progressId);
        pageSummaryId=switch(destination) {
            case DOWNLOADS -> R.id.downloads_page_summary; case FAVORITES -> R.id.favorites_page_summary;
            case HISTORY -> R.id.history_page_summary; case NEW_EPISODES -> R.id.new_episodes_page_summary;
            case IN_PROGRESS -> R.id.in_progress_page_summary;
            default -> throw new IllegalArgumentException("Unsupported library destination.");
        };
        pageSummary=activity.findViewById(pageSummaryId);
        previous=activity.findViewById(previousId);next=activity.findViewById(nextId);
        list=new EpisodeListView(activity,activity.findViewById(rowsId),artwork,artworkReady,actions,playing);
        if(saved!=null)offset=Math.max(0,Math.min(SecurityLimits.MAX_GLOBAL_EPISODES,
                saved.getInt("library.offset."+destination.name(),0)));
        previous.setVisibility(View.GONE); next.setVisibility(View.GONE);
        previous.setOnClickListener(v->{if(!busy&&offset>0){offset=Math.max(0,offset-SecurityLimits.CATALOG_BROWSE_PAGE_SIZE);load();}});
        next.setOnClickListener(v->{if(!busy&&hasMore){offset+=SecurityLimits.CATALOG_BROWSE_PAGE_SIZE;load();}});
    }
    void setVisible(boolean value) {
        if(visible==value)return;
        visible=value;generation++;busy=false;
        if(!visible)list.cancelSelection();
        main.removeCallbacks(refreshListening);
        if(visible){load();if(destination==Destination.HISTORY||destination==Destination.IN_PROGRESS)
            main.postDelayed(refreshListening,SecurityLimits.PLAYBACK_PROGRESS_WRITE_INTERVAL_MILLIS);}
    }
    void refresh() { if(visible&&!closed&&!list.isSelecting())load(); }
    void save(Bundle out) { out.putInt("library.offset."+destination.name(),offset); }
    void refreshArtwork() { if(visible)list.refreshArtwork(); }
    void refreshPlayback() { if(visible)list.refreshPlayback(); }
    boolean hasRows(){return list.hasRows();}
    boolean beginSelection(){return list.beginSelection();}
    boolean select(String id){return list.select(id);}
    void cancelSelection(){list.cancelSelection();}
    private void load() {
        if(closed||!visible)return;
        int ticket=++generation,requestedOffset=offset;
        busy=true;summary.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);
        progress.setContentDescription(activity.getString(R.string.ui_loading));
        previous.setEnabled(false);next.setEnabled(false);
        try { worker.execute(()-> {
            if(closed||!visible||ticket!=generation)return;
            List<Item> items=new ArrayList<>();boolean more=false,failed=false;
            try {
                if(destination==Destination.DOWNLOADS) {
                    var page=application.getDownloadedEpisodeStore().listDownloadedEpisodes(requestedOffset,SecurityLimits.CATALOG_BROWSE_PAGE_SIZE);
                    more=page.hasMore();for(DownloadedEpisode e:page.episodes())items.add(new Item(e.episode(),e.completedAtMillis(),e.downloadedBytes()));
                } else {
                    var filter=switch(destination) {
                        case FAVORITES -> EpisodeLibraryStore.Filter.FAVORITES; case HISTORY -> EpisodeLibraryStore.Filter.HISTORY;
                        case NEW_EPISODES -> EpisodeLibraryStore.Filter.NEW_EPISODES; case IN_PROGRESS -> EpisodeLibraryStore.Filter.IN_PROGRESS;
                        default -> throw new IllegalArgumentException("Unsupported library destination.");
                    };
                    var page=application.getEpisodeLibraryStore().listLibraryEpisodes(filter,requestedOffset,SecurityLimits.CATALOG_BROWSE_PAGE_SIZE);
                    more=page.hasMore();for(int i=0;i<page.episodes().size();i++)
                        items.add(new Item(page.episodes().get(i),page.listenedAtMillis().get(i),0));
                }
            } catch(IOException|RuntimeException ignored) { failed=true; }
            boolean resultMore=more,resultFailed=failed;
            main.post(()-> {
                if(closed||!visible||ticket!=generation||activity.isDestroyed())return;
                busy=false;hasMore=resultMore;progress.setVisibility(View.GONE);
                if(!resultFailed&&items.isEmpty()&&offset>0){offset=0;load();return;}
                previous.setVisibility(offset>0?View.VISIBLE:View.GONE);next.setVisibility(hasMore?View.VISIBLE:View.GONE);
                previous.setEnabled(offset>0);next.setEnabled(hasMore);
                list.clear();
                summary.setVisibility(resultFailed||items.isEmpty()?View.VISIBLE:View.GONE);
                pageSummary.setVisibility(offset>0||hasMore?View.VISIBLE:View.GONE);
                pageSummary.setText(activity.getString(R.string.ui_page_current,
                        offset/SecurityLimits.CATALOG_BROWSE_PAGE_SIZE+1));
                if(resultFailed){summary.setText(R.string.ui_load_failed);return;}
                if(items.isEmpty())summary.setText(switch(destination) {
                    case DOWNLOADS -> R.string.ui_empty_downloads; case FAVORITES -> R.string.ui_empty_favorites;
                    case HISTORY -> R.string.ui_empty_history; case NEW_EPISODES -> R.string.ui_empty_new_episodes;
                    case IN_PROGRESS -> R.string.ui_empty_in_progress;
                    default -> throw new IllegalArgumentException("Unsupported library destination.");
                });

                String group="";
                for(Item item:items) {
                    if(destination==Destination.DOWNLOADS||destination==Destination.HISTORY) {
                        String current=dateGroup(item.when());
                        if(!current.equals(group)){list.heading(current);group=current;}
                    }
                    CatalogEpisodeSummary e=item.episode();
                    String caption=destination==Destination.DOWNLOADS||destination==Destination.HISTORY||destination==Destination.IN_PROGRESS
                            ?metadata.listeningTime(e.getDurationMillis(),e.getPlaybackPositionMillis(),e.isPlayed())
                            :metadata.catalog(e);
                    if(destination==Destination.DOWNLOADS&&item.bytes()>0)caption+=(caption.isEmpty()?"":" · ")
                            +Formatter.formatShortFileSize(activity,item.bytes());
                    list.add(new EpisodeListView.Row(e.getMediaId(),e.getTitle().isEmpty()?activity.getString(R.string.catalog_episode_untitled):e.getTitle(),
                            caption,e.getPodcast(),e.isDownloaded(),e.isPlayed(),
                            e.getDurationMillis(),e.getPlaybackPositionMillis(),-1),true,false);
                }
                list.refreshArtwork();list.refreshPlayback();
            });
        }); } catch(RejectedExecutionException ignored){busy=false;progress.setVisibility(View.GONE);summary.setText(R.string.ui_load_failed);summary.setVisibility(View.VISIBLE);}
    }
    private String dateGroup(long when) {
        if(when==0)return activity.getString(R.string.ui_unknown_download_time);
        if(DateUtils.isToday(when))return activity.getString(R.string.ui_today);
        Calendar day=Calendar.getInstance();day.add(Calendar.DAY_OF_YEAR,-1);
        Calendar completion=Calendar.getInstance();completion.setTimeInMillis(when);
        if(day.get(Calendar.ERA)==completion.get(Calendar.ERA)&&day.get(Calendar.YEAR)==completion.get(Calendar.YEAR)
                &&day.get(Calendar.DAY_OF_YEAR)==completion.get(Calendar.DAY_OF_YEAR))return activity.getString(R.string.ui_yesterday);
        return DateUtils.formatDateTime(activity,when,DateUtils.FORMAT_SHOW_DATE|DateUtils.FORMAT_SHOW_YEAR);
    }
    @Override public void close(){closed=true;generation++;main.removeCallbacks(refreshListening);list.cancelSelection();}
}
