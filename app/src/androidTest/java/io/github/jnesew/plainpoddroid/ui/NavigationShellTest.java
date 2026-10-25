package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.ui.NavigationState.Destination;

/** Actual view visibility/click checks, without starting playback or application work. */
@SuppressWarnings("deprecation")
public final class NavigationShellTest extends InstrumentationTestCase {
    private View root;
    private Context context;
    private NavigationShell shell;
    private int changes,about;
    private static final String ID = CatalogId.VALUE;
    private static final class CatalogId {
        static final String VALUE;
        static { StringBuilder id=new StringBuilder("episode-");for(int i=0;i<64;i++)id.append('a');VALUE=id.toString(); }
    }
    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().runOnMainSync(()->{
            context=new ContextThemeWrapper(getInstrumentation().getTargetContext(),R.style.Theme_PlainPodDroid);
            root=LayoutInflater.from(context).inflate(R.layout.activity_main,null);
            shell=new NavigationShell(context,root,null,()->changes++,()->{
                assertEquals(View.GONE,root.findViewById(R.id.navigation_drawer).getVisibility());about++;
            });
        });
    }
    public void testOutsideTapAndBackCloseDrawerWithoutNavigation() {
        getInstrumentation().runOnMainSync(()->{
            root.findViewById(R.id.navigation_action).performClick();
            assertEquals(View.VISIBLE,root.findViewById(R.id.navigation_drawer).getVisibility());
            root.findViewById(R.id.drawer_scrim).performClick();
            assertEquals(View.GONE,root.findViewById(R.id.navigation_drawer).getVisibility());assertEquals(0,changes);
            root.findViewById(R.id.navigation_action).performClick();assertTrue(shell.back());
            assertEquals(Destination.PODCASTS,shell.current().destination());assertEquals(0,changes);
        });
    }
    public void testSelectingEveryDestinationAndSelectingItAgainClosesDrawer() {
        getInstrumentation().runOnMainSync(()->{
            int[] buttons={R.id.nav_podcasts,R.id.nav_downloads,R.id.nav_queue,R.id.nav_favorites,
                    R.id.nav_history,R.id.nav_archives,R.id.nav_settings,R.id.nav_new_episodes,R.id.nav_in_progress};
            Destination[] destinations={Destination.PODCASTS,Destination.DOWNLOADS,Destination.QUEUE,
                    Destination.FAVORITES,Destination.HISTORY,Destination.ARCHIVES,Destination.SETTINGS,Destination.NEW_EPISODES,Destination.IN_PROGRESS};
            for(int i=0;i<buttons.length;i++) {
                root.findViewById(R.id.navigation_action).performClick();root.findViewById(buttons[i]).performClick();
                assertEquals(destinations[i],shell.current().destination());
                assertEquals(View.GONE,root.findViewById(R.id.navigation_drawer).getVisibility());
                root.findViewById(R.id.navigation_action).performClick();root.findViewById(buttons[i]).performClick();
                assertEquals(View.GONE,root.findViewById(R.id.navigation_drawer).getVisibility());
            }
        });
    }
    public void testAboutAndAddActionsCloseBeforeActingAndAddReturnsToOrigin() {
        getInstrumentation().runOnMainSync(()->{
            shell.select(Destination.QUEUE);
            root.findViewById(R.id.navigation_action).performClick();root.findViewById(R.id.nav_about).performClick();
            assertEquals(1,about);assertEquals(Destination.QUEUE,shell.current().destination());
            root.findViewById(R.id.navigation_action).performClick();root.findViewById(R.id.nav_add_podcast).performClick();
            assertEquals(View.GONE,root.findViewById(R.id.navigation_drawer).getVisibility());
            assertEquals(Destination.ADD_PODCAST,shell.current().destination());shell.back();
            assertEquals(Destination.QUEUE,shell.current().destination());
        });
    }
    public void testEpisodeBackAndSavedNavigationKeepTheOrigin() {
        getInstrumentation().runOnMainSync(()->{
            shell.select(Destination.DOWNLOADS);shell.openEpisode(ID);
            assertEquals(View.VISIBLE,root.findViewById(R.id.screen_episode).getVisibility());
            assertEquals(View.VISIBLE,root.findViewById(R.id.episode_favorite_action).getVisibility());
            Bundle saved=new Bundle();shell.save(saved);
            View restoredRoot=LayoutInflater.from(context).inflate(R.layout.activity_main,null);
            NavigationShell restored=new NavigationShell(context,restoredRoot,saved,()->{},()->{});
            assertEquals(ID,restored.current().episodeId());restored.back();
            assertEquals(Destination.DOWNLOADS,restored.current().destination());
            assertEquals(View.VISIBLE,restoredRoot.findViewById(R.id.screen_downloads).getVisibility());
        });
    }
    public void testOpenDrawerHidesBackgroundFromAccessibilityUntilClosed() {
        getInstrumentation().runOnMainSync(()->{
            root.findViewById(R.id.navigation_action).performClick();
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,root.findViewById(R.id.app_content).getImportantForAccessibility());
            assertEquals(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS,((android.view.ViewGroup)root.findViewById(R.id.app_content)).getDescendantFocusability());
            root.findViewById(R.id.drawer_scrim).performClick();
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,root.findViewById(R.id.app_content).getImportantForAccessibility());
            assertEquals(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS,((android.view.ViewGroup)root.findViewById(R.id.app_content)).getDescendantFocusability());
        });
    }
}
