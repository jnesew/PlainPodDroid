package io.github.jnesew.plainpoddroid.ui;

import android.view.Menu;
import android.view.MenuItem;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSummary;

/** Same contextual actions for a library row and its opened podcast. */
public final class PodcastActionsMenu {
    public interface Actions {
        void information();void settings();void refresh();void archive();void delete();void subscribeAgain();void login();
    }
    private final MenuItem login;
    public PodcastActionsMenu(Menu menu,CatalogSubscriptionSummary podcast,boolean canChange,boolean canRefresh,Actions actions){
        add(menu,R.id.podcast_action_information,R.string.ui_podcast_information,true,actions::information);
        add(menu,R.id.podcast_action_settings,R.string.ui_podcast_options,true,actions::settings);
        login=podcast.isSubscribed()&&podcast.getSourceUrl()!=null
                ?add(menu,R.id.podcast_action_login,R.string.ui_feed_login,false,actions::login).setVisible(false):null;
        if(podcast.isSubscribed()){
            add(menu,R.id.podcast_action_refresh,R.string.subscription_refresh_action,canRefresh,actions::refresh);
            add(menu,R.id.podcast_action_archive,R.string.ui_archive_podcast,canChange,actions::archive);
        }else add(menu,R.id.podcast_action_subscribe,R.string.ui_subscribe_again,canRefresh&&podcast.getSourceUrl()!=null,actions::subscribeAgain);
        add(menu,R.id.podcast_action_delete,podcast.isSubscribed()?R.string.ui_delete_podcast:R.string.ui_delete_archive,canChange,actions::delete);
    }
    public void setLoginState(PodcastLoginState state,boolean editable){
        if(login==null)return;login.setVisible(state!=PodcastLoginState.NONE).setEnabled(editable)
                .setTitle(state==PodcastLoginState.REQUIRED?R.string.ui_login_required:R.string.ui_manage_feed_login);
    }
    private static MenuItem add(Menu menu,int id,int label,boolean enabled,Runnable action){
        return menu.add(Menu.NONE,id,Menu.NONE,label).setEnabled(enabled).setOnMenuItemClickListener(item->{action.run();return true;});
    }
}
