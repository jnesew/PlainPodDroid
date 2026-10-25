package io.github.jnesew.plainpoddroid.core.ui;

import io.github.jnesew.plainpoddroid.core.catalog.*;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshController;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowsingDuringRefreshTest {
    @Test public void localListsAndEpisodeQueriesCompleteWhileFeedRequestIsBlocked() throws Exception {
        var feedWorker=Executors.newSingleThreadExecutor();var localWorker=Executors.newSingleThreadExecutor();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var subscriptionsReady=new CountDownLatch(1);var episodesReady=new CountDownLatch(1);
        try(var refresh=new SubscriptionRefreshController(id->{
            entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
        },feedWorker,Runnable::run,state->{});
            var browser=new CatalogBrowserController(new CatalogBrowserStore(){
                public List<CatalogSubscriptionSummary> listSubscriptions(){return List.of(new CatalogSubscriptionSummary(1,"Show","",0,0,0L));}
                public CatalogEpisodePage listEpisodes(long id,int offset,int limit,CatalogBrowseQuery query){return new CatalogEpisodePage(id,offset,List.of(),false,1);}
            },localWorker,Runnable::run,state->{
                if(state.getPhase()==CatalogBrowserController.Phase.SUBSCRIPTIONS_READY)subscriptionsReady.countDown();
                if(state.getPhase()==CatalogBrowserController.Phase.EPISODES_READY)episodesReady.countDown();
            })){
            refresh.refresh(1);assertTrue(entered.await(3,TimeUnit.SECONDS));
            browser.loadSubscriptions();assertTrue(subscriptionsReady.await(3,TimeUnit.SECONDS));
            browser.selectSubscription(1);assertTrue(episodesReady.await(3,TimeUnit.SECONDS));
            assertEquals(SubscriptionRefreshController.Phase.REFRESHING,refresh.getState().getPhase());
        }finally{release.countDown();feedWorker.shutdownNow();localWorker.shutdownNow();}
    }
}
