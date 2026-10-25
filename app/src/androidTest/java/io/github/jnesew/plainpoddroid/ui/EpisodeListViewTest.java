package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.artwork.ArtworkThumbnailLoader;
import io.github.jnesew.plainpoddroid.core.artwork.BoundedArtworkAcquirer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class EpisodeListViewTest extends InstrumentationTestCase {
    private static final String ID = "episode-" + "a".repeat(64);
    private final List<String> calls=new ArrayList<>();
    private LinearLayout rows;
    private EpisodeListView list;
    private EpisodeListView.DownloadState downloadState;
    private ArtworkThumbnailLoader artwork;
    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().runOnMainSync(()->{
            Context context=new ContextThemeWrapper(getInstrumentation().getTargetContext(),R.style.Theme_PlainPodDroid);
            artwork=new ArtworkThumbnailLoader(new BoundedArtworkAcquirer(url->{throw new IOException("No network in row tests");}),new Handler(Looper.getMainLooper()));
            rows=new LinearLayout(context);
            list=new EpisodeListView(context,rows,artwork,()->{},new EpisodeListView.Actions(){
                public EpisodeListView.DownloadState downloadState(EpisodeListView.Row row){return downloadState==null
                    ?EpisodeListView.Actions.super.downloadState(row):downloadState;}
                public void download(EpisodeListView.Row row,android.view.View anchor){calls.add("download:"+row.id());}
                public void open(String id){calls.add("open:"+id);}public void play(String id){calls.add("play:"+id);}
                public void menu(EpisodeListView.Row row,android.view.View anchor){calls.add("menu:"+row.id());}
                public void move(String id,int position){calls.add("move");}public boolean canMove(){return true;}
            },id->false);
        });
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(()->artwork.close());super.tearDown();
    }
    private void row(boolean queue) {
        list.add(new EpisodeListView.Row(ID, "Episode", "Metadata",null,false,false,-1,0,queue?0:-1),false,queue);
    }
    public void testBodyOnlyNavigates() {
        getInstrumentation().runOnMainSync(()->{row(false);rows.findViewById(R.id.episode_row_body).performClick();
            assertEquals(1,calls.size());assertEquals("open:"+ID,calls.get(0));});
    }
    public void testDistinctPlayControlOnlyPlays() {
        getInstrumentation().runOnMainSync(()->{row(false);rows.findViewById(R.id.episode_row_play).performClick();
            assertEquals(1,calls.size());assertEquals("play:"+ID,calls.get(0));});
    }
    public void testQueueOverflowIsTheOnlyMenuAndBodyStillNavigates() {
        getInstrumentation().runOnMainSync(()->{row(true);rows.findViewById(R.id.episode_row_grip).performClick();
            assertTrue(calls.isEmpty());assertFalse(rows.findViewById(R.id.episode_row_body).isLongClickable());
            rows.findViewById(R.id.episode_row_menu).performClick();
            rows.findViewById(R.id.episode_row_body).performClick();
            assertEquals(2,calls.size());assertEquals("menu:"+ID,calls.get(0));assertEquals("open:"+ID,calls.get(1));});
    }
    public void testCompactRowsUseStatusIconsAndOnlyUnfinishedProgress() {
        getInstrumentation().runOnMainSync(()->{
            list.add(new EpisodeListView.Row(ID,"Long episode title","48m left",null,
                    true,false,4_137_000,1_275_000,-1),false,false);
            assertNull(rows.findViewById(R.id.episode_row_downloaded));
            list.refreshPlayback();assertTrue(rows.findViewById(R.id.episode_row_download).getContentDescription().toString().contains("Downloaded"));
            assertNull(rows.findViewById(R.id.episode_row_played));
            assertNotNull(rows.findViewById(R.id.episode_row_progress));
            list.clear();
            list.add(new EpisodeListView.Row(ID,"Episode","1h",null,
                    false,true,3_600_000,30_000,-1),false,false);
            assertNotNull(rows.findViewById(R.id.episode_row_played));
            assertNull(rows.findViewById(R.id.episode_row_progress));
        });
    }
    public void testDownloadControlHasIndependentCallbackAndLiveRequestStates(){
        getInstrumentation().runOnMainSync(()->{
            row(false);list.refreshPlayback();rows.findViewById(R.id.episode_row_download).performClick();
            assertEquals(List.of("download:"+ID),calls);calls.clear();
            for(var state:EpisodeListView.DownloadState.values()){
                downloadState=state;list.refreshPlayback();android.view.View button=rows.findViewById(R.id.episode_row_download);
                assertEquals(state!=EpisodeListView.DownloadState.UNAVAILABLE,button.isEnabled());
                assertEquals(state==EpisodeListView.DownloadState.ACTIVE||state==EpisodeListView.DownloadState.WAITING
                    ||state==EpisodeListView.DownloadState.FAILED,rows.findViewById(R.id.episode_row_download_status).getVisibility()==android.view.View.VISIBLE);
                assertTrue(button.getContentDescription().toString().contains("Episode"));
            }
            assertTrue(calls.isEmpty());
        });
    }
    public void testLargeTextKeepsBothControlsAtFortyEightDpAndPreservesBodyWidth(){
        getInstrumentation().runOnMainSync(()->{
            Context base=rows.getContext();var config=new android.content.res.Configuration(base.getResources().getConfiguration());
            config.fontScale=2;config.screenWidthDp=320;
            Context c=new ContextThemeWrapper(base.createConfigurationContext(config),R.style.Theme_PlainPodDroid);
            var narrowRows=new LinearLayout(c);narrowRows.setOrientation(LinearLayout.VERTICAL);
            var narrow=new EpisodeListView(c,narrowRows,artwork,()->{},new EpisodeListView.Actions(){
                public void open(String id){}public void play(String id){}public void menu(EpisodeListView.Row row,android.view.View anchor){}
                public void move(String id,int position){}public boolean canMove(){return false;}
            },id->false);
            narrow.add(new EpisodeListView.Row(ID,"An episode with a long title","48m",null,false,false,-1,0,-1),false,false);
            narrow.refreshPlayback();int width=UiWidgets.dp(c,280);
            narrowRows.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
            narrowRows.layout(0,0,width,narrowRows.getMeasuredHeight());
            android.view.View play=narrowRows.findViewById(R.id.episode_row_play),download=narrowRows.findViewById(R.id.episode_row_download);
            assertEquals(UiWidgets.dp(c,48),play.getWidth());assertEquals(UiWidgets.dp(c,48),download.getHeight());
            assertTrue(download.getTop()>=play.getBottom());
            assertTrue(narrowRows.findViewById(R.id.episode_row_body).getWidth()>=UiWidgets.dp(c,210));
        });
    }
    public void testShadowIncludesTheWholeRowAndAnchorsTheGrip() {
        getInstrumentation().runOnMainSync(()->{
            row(true);rows.setOrientation(LinearLayout.VERTICAL);
            int width=android.view.View.MeasureSpec.makeMeasureSpec(600,android.view.View.MeasureSpec.EXACTLY);
            rows.measure(width,android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
            rows.layout(0,0,600,rows.getMeasuredHeight());
            android.view.View container=rows.getChildAt(0),grip=rows.findViewById(R.id.episode_row_grip);
            var shadow=new QueueRowDrag.RowShadow(container,grip);
            var size=new android.graphics.Point();var touch=new android.graphics.Point();
            shadow.onProvideShadowMetrics(size,touch);
            assertTrue(size.x>=container.getWidth());assertTrue(size.y>=container.getHeight());
            assertTrue(size.x>grip.getWidth());assertTrue(touch.x<size.x/2);
        });
    }

}
