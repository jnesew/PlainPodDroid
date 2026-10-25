package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.Switch;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSummary;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class PodcastActionsViewTest extends InstrumentationTestCase {
    private final List<String> calls=new ArrayList<>();
    private Context context(){return new ContextThemeWrapper(getInstrumentation().getTargetContext(),R.style.Theme_PlainPodDroid);}
    private CatalogSubscriptionSummary podcast(boolean subscribed){
        try{return new CatalogSubscriptionSummary(7,"Selected show","",0,0,0,0,subscribed,0,0,null,false,
                CanonicalHttpsUrl.parse("https://pod.example/rss"));}catch(Exception e){throw new AssertionError(e);}
    }
    private PodcastActionsMenu menu(Menu menu,boolean subscribed,boolean enabled){
        return new PodcastActionsMenu(menu,podcast(subscribed),enabled,enabled,new PodcastActionsMenu.Actions(){
            public void information(){calls.add("information");}public void settings(){calls.add("settings");}
            public void refresh(){calls.add("refresh");}public void archive(){calls.add("archive");}
            public void delete(){calls.add("delete");}public void subscribeAgain(){calls.add("subscribe");}public void login(){calls.add("login");}
        });
    }
    public void testPublicAndSavedLoginActionsAndSettingsHaveDistinctCallbacks(){
        getInstrumentation().runOnMainSync(()->{
            Context c=context();Menu items=new PopupMenu(c,new View(c)).getMenu();var binding=menu(items,true,true);
            assertEquals(6,items.size());assertFalse(items.findItem(R.id.podcast_action_login).isVisible());assertTrue(calls.isEmpty());
            items.performIdentifierAction(R.id.podcast_action_settings,0);assertEquals(List.of("settings"),calls);
            binding.setLoginState(PodcastLoginState.SAVED,true);
            assertEquals(c.getString(R.string.ui_manage_feed_login),items.findItem(R.id.podcast_action_login).getTitle());
            assertTrue(items.findItem(R.id.podcast_action_login).isVisible());items.performIdentifierAction(R.id.podcast_action_login,0);
            assertEquals(List.of("settings","login"),calls);
            binding.setLoginState(PodcastLoginState.REQUIRED,false);
            assertEquals(c.getString(R.string.ui_login_required),items.findItem(R.id.podcast_action_login).getTitle());
            assertFalse(items.findItem(R.id.podcast_action_login).isEnabled());
            items.performIdentifierAction(R.id.podcast_action_login,0);assertEquals(2,calls.size());
        });
    }
    public void testArchiveHasRestoreAndPermanentDeleteWithoutRefreshArchiveOrLogin(){
        getInstrumentation().runOnMainSync(()->{
            Context c=context();Menu items=new PopupMenu(c,new View(c)).getMenu();var binding=menu(items,false,true);
            binding.setLoginState(PodcastLoginState.REQUIRED,true);
            assertEquals(4,items.size());assertNull(items.findItem(R.id.podcast_action_refresh));
            assertNull(items.findItem(R.id.podcast_action_archive));assertNull(items.findItem(R.id.podcast_action_login));
            assertEquals(c.getString(R.string.ui_delete_archive),items.findItem(R.id.podcast_action_delete).getTitle());
            items.performIdentifierAction(R.id.podcast_action_subscribe,0);items.performIdentifierAction(R.id.podcast_action_delete,0);
            assertEquals(List.of("subscribe","delete"),calls);
        });
    }
    public void testBusyActionsAreDisabledWhileInformationAndSettingsRemainAccessible(){
        getInstrumentation().runOnMainSync(()->{
            Context c=context();Menu items=new PopupMenu(c,new View(c)).getMenu();menu(items,true,false);
            for(int id:new int[]{R.id.podcast_action_refresh,R.id.podcast_action_archive,R.id.podcast_action_delete}){
                assertFalse(items.findItem(id).isEnabled());items.performIdentifierAction(id,0);
            }
            assertTrue(calls.isEmpty());assertTrue(items.findItem(R.id.podcast_action_settings).isEnabled());
        });
    }
    public void testSettingsBindingAndFailedSwitchRollbackNeverIssueImplicitActions(){
        getInstrumentation().runOnMainSync(()->{
            var settings=new PodcastSettingsView(context(),podcast(true),true,true,()->calls.add("speed"),()->calls.add("login"),
                    (enabled,view)->{calls.add("save:"+enabled);view.setAutomaticDownloadBusy();});
            assertTrue(calls.isEmpty());Switch automatic=null;
            for(int i=0;i<settings.getChildCount();i++)if(settings.getChildAt(i)instanceof Switch value)automatic=value;
            assertNotNull(automatic);automatic.setChecked(true);assertFalse(automatic.isEnabled());assertEquals(List.of("save:true"),calls);
            settings.finishAutomaticDownload(false,true);assertFalse(automatic.isChecked());assertTrue(automatic.isEnabled());
            assertEquals(List.of("save:true"),calls);
            for(int i=0;i<settings.getChildCount();i++)if(settings.getChildAt(i)instanceof Button value&&!(value instanceof android.widget.CompoundButton))value.performClick();
            assertEquals(List.of("save:true","speed","login"),calls);
        });
    }
}
