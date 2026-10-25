package io.github.jnesew.plainpoddroid.ui;

import android.app.Dialog;
import android.content.Context;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.chapter.ChapterTimeline;
import io.github.jnesew.plainpoddroid.core.chapter.NotesChapterParser;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapter;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapterDocument;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.ArrayList;
import java.util.List;

/** Inert chapter data and native sheet. Playback and publisher contact stay explicit callbacks. */
public final class ChapterPanel {
    public interface Actions {
        void select(String mediaId,PodcastChapter chapter);
        void play(String mediaId,PodcastChapter chapter);
        void page(String mediaId,PodcastChapter chapter);
        void load();
    }
    private final Context context;
    private final Button current;
    private final ChapterSeekBar seek;
    private final Actions actions;
    private final NotesChapterParser parser=new NotesChapterParser();
    private EpisodeDetails details;
    private String parsedNotes="";
    private long parsedDuration=-2;
    private PodcastChapterDocument local,document;
    private ChapterTimeline timeline=new ChapterTimeline(null);
    private boolean enabled,canSeek,canPlay,canLoad,loading;
    private long position,duration=-1;
    private PodcastChapter playingChapter;
    private Dialog sheet;
    private LinearLayout sheetRoot;
    private ScrollView sheetScroll;
    private int offset;
    private final List<Button> chapterButtons=new ArrayList<>();

    public ChapterPanel(Context context,Button current,ChapterSeekBar seek,Actions actions){
        this.context=context;this.current=current;this.seek=seek;this.actions=actions;
        current.setOnClickListener(v->show());
    }
    public void bind(EpisodeDetails details,PodcastChapterDocument structured,boolean enabled,boolean canLoad,boolean loading){
        String oldId=this.details==null?"":this.details.getMediaId();
        String id=details==null?"":details.getMediaId();
        if(!id.equals(oldId)){dismiss();offset=0;parsedNotes="";parsedDuration=-2;local=null;}
        boolean availability=this.enabled!=enabled||this.canLoad!=canLoad||this.loading!=loading;
        this.details=details;this.enabled=enabled;this.canLoad=canLoad;this.loading=loading;
        if(details!=null&&(!details.getNotes().equals(parsedNotes)||details.getDurationMillis()!=parsedDuration)){
            parsedNotes=details.getNotes();parsedDuration=details.getDurationMillis();local=parser.parse(parsedNotes,parsedDuration);
        }
        PodcastChapterDocument next=structured!=null?structured:local;
        boolean changed=next!=document;document=next;if(changed){timeline=new ChapterTimeline(document);offset=0;}
        current.setVisibility(details!=null&&(!timeline.chapters().isEmpty()||details.isChapterReferenceAvailable()&&(details.getPodcast()==null||details.getPodcast().subscribed()))?View.VISIBLE:View.GONE);
        current.setEnabled(details!=null);
        if(changed)playingChapter=null;
        updateLabel(false);seek.setChapters(timeline,duration);
        if(sheet!=null&&sheet.isShowing()&&(changed||availability))renderSheet();
    }
    public boolean contains(String mediaId,PodcastChapter chapter){
        return details!=null&&details.getMediaId().equals(mediaId)&&timeline.chapters().contains(chapter)&&chapter.isVisible();
    }
    public void progress(long position,long duration,boolean scrubbing,boolean canSeek,boolean canPlay){
        this.position=position;this.duration=duration;
        boolean availability=this.canSeek!=canSeek||this.canPlay!=canPlay;
        this.canSeek=canSeek;this.canPlay=canPlay;
        PodcastChapter next=timeline.at(position,duration);boolean chapterChanged=next!=playingChapter;playingChapter=next;
        updateLabel(scrubbing);seek.setChapters(timeline,duration);
        if(sheet!=null&&sheet.isShowing()){
            if(availability)renderSheet();
            else if(chapterChanged)highlight();
        }
    }
    private void updateLabel(boolean scrubbing){
        String title=playingChapter==null?"":playingChapter.getTitle();
        current.setText(scrubbing&&title.isEmpty()?time(position):title.isEmpty()?context.getString(R.string.ui_chapters):scrubbing
                ?context.getString(R.string.ui_chapter_preview,time(position),title)
                :context.getString(R.string.ui_current_chapter,title));
        current.setContentDescription(context.getString(R.string.ui_open_chapters)+
                (title.isEmpty()?"":". "+time(position)+". "+title));
    }
    public void show(){
        if(details==null||sheet!=null)return;
        int index=timeline.chapters().indexOf(playingChapter);
        if(index>=0)offset=index/SecurityLimits.MAX_VISIBLE_CHAPTERS*SecurityLimits.MAX_VISIBLE_CHAPTERS;
        sheet=new Dialog(context);sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet.setCanceledOnTouchOutside(true);
        sheetRoot=new LinearLayout(context);sheetRoot.setOrientation(LinearLayout.VERTICAL);
        sheetRoot.setPadding(UiWidgets.dp(context,20),UiWidgets.dp(context,12),UiWidgets.dp(context,20),UiWidgets.dp(context,12));
        sheetRoot.setBackgroundColor(context.getColor(R.color.background));
        sheetScroll=new ScrollView(context);sheetScroll.addView(sheetRoot);
        sheet.setContentView(sheetScroll);sheet.setOnDismissListener(d->{sheet=null;sheetRoot=null;sheetScroll=null;chapterButtons.clear();});
        renderSheet();sheet.show();Window window=sheet.getWindow();
        if(window!=null){window.setGravity(Gravity.BOTTOM);window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(.55f);}
        resizeSheet();
        sheetScroll.post(()->{if(sheetScroll!=null)for(Button row:chapterButtons)if(row.getTag()==playingChapter){
            android.graphics.Rect bounds=new android.graphics.Rect();row.getDrawingRect(bounds);
            sheetRoot.offsetDescendantRectToMyCoords(row,bounds);sheetScroll.scrollTo(0,Math.max(0,bounds.top-UiWidgets.dp(context,64)));break;
        }});
    }
    private Button button(String label,Runnable action){
        Button button=new Button(context,null,0,R.style.Widget_PlainPod_Button);
        button.setText(label);button.setMinHeight(UiWidgets.dp(context,48));button.setOnClickListener(v->action.run());return button;
    }
    private void renderSheet(){
        if(sheetRoot==null||details==null)return;
        int scrollY=sheetScroll==null?0:sheetScroll.getScrollY();
        sheetRoot.removeAllViews();chapterButtons.clear();
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.TextView heading=UiWidgets.text(context,context.getString(R.string.ui_chapters),20,true);heading.setAccessibilityHeading(true);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button close=UiWidgets.iconButton(context,R.drawable.ic_close,context.getString(R.string.ui_close_chapters),false);
        close.setOnClickListener(v->dismiss());header.addView(close);sheetRoot.addView(header);
        if(details.isChapterReferenceAvailable()&&(details.getPodcast()==null||details.getPodcast().subscribed())){
            Button load=button(context.getString(loading?R.string.chapter_status_loading:R.string.chapter_load_action),actions::load);
            load.setContentDescription(context.getString(R.string.chapter_load_description));
            load.setEnabled(canLoad&&!loading);sheetRoot.addView(load,new LinearLayout.LayoutParams(-1,-2));
        }
        List<PodcastChapter> chapters=timeline.chapters();
        if(chapters.isEmpty()){
            sheetRoot.addView(UiWidgets.text(context,context.getString(R.string.ui_chapters_unavailable),15,false));resizeSheet();return;
        }
        offset=Math.max(0,Math.min(offset,((chapters.size()-1)/SecurityLimits.MAX_VISIBLE_CHAPTERS)*SecurityLimits.MAX_VISIBLE_CHAPTERS));
        LinearLayout rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        int end=Math.min(chapters.size(),offset+SecurityLimits.MAX_VISIBLE_CHAPTERS);
        for(int i=offset;i<end;i++){
            PodcastChapter chapter=chapters.get(i);String title=chapter.getTitle().isEmpty()?context.getString(R.string.chapter_title_untitled):chapter.getTitle();
            Button row=button(time(chapter.getStartTimeMillis())+"  "+title,()->{
                if(contains(details.getMediaId(),chapter)&&enabled&&canSeek){actions.select(details.getMediaId(),chapter);dismiss();}
            });row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.setEnabled(enabled&&canSeek&&inDuration(chapter));row.setTag(chapter);
            row.setContentDescription(context.getString(R.string.ui_seek_chapter,title,time(chapter.getStartTimeMillis())));
            chapterButtons.add(row);rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
            if(canPlay){
                Button play=button(context.getString(R.string.ui_play_chapter),()->{
                    if(contains(details.getMediaId(),chapter)&&enabled&&canPlay){actions.play(details.getMediaId(),chapter);dismiss();}
                });play.setContentDescription(context.getString(R.string.chapter_play_description,title,time(chapter.getStartTimeMillis())));
                play.setEnabled(enabled&&canPlay&&inDuration(chapter));rows.addView(play,new LinearLayout.LayoutParams(-1,-2));
            }
            if(chapter.getWebsiteUrl()!=null){
                Button page=button(context.getString(R.string.chapter_page_action),()->{
                    if(contains(details.getMediaId(),chapter)&&enabled)actions.page(details.getMediaId(),chapter);
                });page.setContentDescription(context.getString(R.string.chapter_page_description,title));page.setEnabled(enabled);rows.addView(page,new LinearLayout.LayoutParams(-1,-2));
            }
        }
        sheetRoot.addView(rows,new LinearLayout.LayoutParams(-1,-2));
        if(chapters.size()>SecurityLimits.MAX_VISIBLE_CHAPTERS){
            sheetRoot.addView(UiWidgets.text(context,context.getString(R.string.ui_chapter_page,offset+1,end,chapters.size()),14,false));
            LinearLayout pages=new LinearLayout(context);
            Button previous=button(context.getString(R.string.ui_previous_chapters),()->{offset-=SecurityLimits.MAX_VISIBLE_CHAPTERS;sheetScroll.scrollTo(0,0);renderSheet();});previous.setEnabled(offset>0);
            Button next=button(context.getString(R.string.ui_next_chapters),()->{offset+=SecurityLimits.MAX_VISIBLE_CHAPTERS;sheetScroll.scrollTo(0,0);renderSheet();});next.setEnabled(end<chapters.size());
            pages.addView(previous,new LinearLayout.LayoutParams(0,-2,1));pages.addView(next,new LinearLayout.LayoutParams(0,-2,1));sheetRoot.addView(pages);
        }
        highlight();resizeSheet();
        if(sheetScroll!=null)sheetScroll.post(()->{if(sheetScroll!=null)sheetScroll.scrollTo(0,scrollY);});
    }
    private void resizeSheet(){
        if(sheet==null||!sheet.isShowing()||sheetRoot==null)return;
        Window window=sheet.getWindow();if(window==null)return;
        sheetRoot.measure(View.MeasureSpec.makeMeasureSpec(context.getResources().getDisplayMetrics().widthPixels,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(sheetRoot.getMeasuredHeight(),context.getResources().getDisplayMetrics().heightPixels*3/4);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,height);
    }
    private boolean inDuration(PodcastChapter chapter){return duration<=0||chapter.getStartTimeMillis()<duration;}
    private void highlight(){for(Button button:chapterButtons){boolean selected=button.getTag()==playingChapter;button.setSelected(selected);
        button.setBackgroundResource(selected?R.drawable.button_surface:R.drawable.icon_surface);}}
    private static String time(long millis){return DateUtils.formatElapsedTime(Math.max(0,millis)/1000);}
    public void dismiss(){if(sheet!=null)sheet.dismiss();}
}
