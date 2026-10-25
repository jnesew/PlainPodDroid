package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.chapter.NotesChapterParser;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapter;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapterDocument;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;

@SuppressWarnings("deprecation")
public final class ChapterViewTest extends InstrumentationTestCase {
    private static final String ID="episode-"+"a".repeat(64);
    private static final String NOTES="00:00 Intro\n01:00 Middle\n02:00 End";
    private final ChapterPanel.Actions inert=new ChapterPanel.Actions() {
        public void select(String id,PodcastChapter chapter) { fail("Preview cannot seek"); }
        public void play(String id,PodcastChapter chapter) { fail("Preview cannot play"); }
        public void page(String id,PodcastChapter chapter) { fail("Preview cannot open a page"); }
        public void load() { fail("Preview cannot load publisher data"); }
    };
    private Context context(float font,boolean dark) {
        Context base=getInstrumentation().getTargetContext();
        Configuration config=new Configuration(base.getResources().getConfiguration());config.fontScale=font;
        config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)
                |(dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(base.createConfigurationContext(config),R.style.Theme_PlainPodDroid);
    }
    private EpisodeDetails episode(String notes) {
        return new EpisodeDetails(ID,"Episode","",notes,null,"",180_000,0,false,false,false,true,false,false);
    }
    private void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(UiWidgets.dp(root.getContext(),320),View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(UiWidgets.dp(root.getContext(),640),View.MeasureSpec.EXACTLY));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
    }
    public void testScrubPreviewAndLongTitlesKeepTheNotesAtTheSamePosition() {
        getInstrumentation().runOnMainSync(()->{
            for(float font:new float[]{1f,2f})for(boolean dark:new boolean[]{false,true}) {
                Context c=context(font,dark);
                ViewGroup root=(ViewGroup)LayoutInflater.from(c).inflate(R.layout.screen_episode,null);
                root.setVisibility(View.VISIBLE);root.findViewById(R.id.episode_details_panel).setVisibility(View.VISIBLE);
                Button current=root.findViewById(R.id.episode_current_chapter);ChapterSeekBar seek=root.findViewById(R.id.episode_seek);
                ChapterPanel panel=new ChapterPanel(c,current,seek,inert);
                panel.bind(episode("00:00 "+"Long title ".repeat(20)+"\n01:00 Middle\n02:00 End"),null,true,false,false);
                panel.progress(0,180_000,false,true,false);layout(root);
                int height=current.getHeight(),top=root.findViewById(R.id.episode_details_notes).getTop();
                panel.progress(70_000,180_000,true,true,false);layout(root);
                assertTrue(current.getText().toString().contains("1:10"));assertTrue(current.getText().toString().contains("Middle"));
                assertEquals(height,current.getHeight());assertEquals(top,root.findViewById(R.id.episode_details_notes).getTop());
                Bitmap image=Bitmap.createBitmap(seek.getWidth(),seek.getHeight(),Bitmap.Config.ARGB_8888);
                seek.draw(new Canvas(image));image.recycle();
            }
        });
    }
    public void testStructuredChaptersTakePriorityAndOldRouteMembershipIsRejected() throws Exception {
        PodcastChapterDocument empty=new io.github.jnesew.plainpoddroid.core.chapter.PodcastChapterParser().parse(
                new java.io.ByteArrayInputStream("{\"version\":\"1.2.0\",\"chapters\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        getInstrumentation().runOnMainSync(()->{
            Context c=context(1f,false);Button current=new Button(c);ChapterPanel panel=new ChapterPanel(c,current,new ChapterSeekBar(c),inert);
            PodcastChapterDocument structured=new NotesChapterParser().parse("00:00 Publisher\n01:00 Talk\n02:00 Finish",180_000);
            PodcastChapter first=structured.getChapters().get(0);
            panel.bind(episode(NOTES),structured,true,false,false);assertTrue(panel.contains(ID,first));
            assertFalse(panel.contains("episode-"+"b".repeat(64),first));
            panel.bind(episode(NOTES),empty,true,false,false);assertEquals(View.GONE,current.getVisibility());
            assertFalse(panel.contains(ID,first));
            panel.bind(null,null,false,false,false);assertFalse(panel.contains(ID,first));
        });
    }
}
