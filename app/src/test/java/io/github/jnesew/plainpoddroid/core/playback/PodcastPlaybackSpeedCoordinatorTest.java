package io.github.jnesew.plainpoddroid.core.playback;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PodcastPlaybackSpeedCoordinatorTest {
    private static final String A="episode-"+"a".repeat(64), B="episode-"+"b".repeat(64);
    private static final class Tasks implements Executor {
        final Queue<Runnable> queue=new ArrayDeque<>();
        public void execute(Runnable r){queue.add(r);}
        void run(){queue.remove().run();}
    }
    private static final class Store implements PodcastPlaybackSettingsStore {
        final Map<String,PlaybackSpeedSetting> speeds=new HashMap<>();boolean fail;int calls;
        public PlaybackSpeedSetting findSpeedForEpisode(String id)throws IOException{
            calls++;if(fail)throw new IOException("private detail");return speeds.get(id);
        }
        public PlaybackSpeedSetting getPodcastSpeed(long id){return null;}
        public void setPodcastSpeed(long id,PlaybackSpeedSetting speed){ }
    }
    @Test public void resolvesOffThreadAndFallsBackForInheritedSpeed() {
        var store=new Store();store.speeds.put(A,PlaybackSpeedSetting.ONE_AND_HALF);
        var work=new Tasks();var main=new Tasks();var applied=new ArrayList<PlaybackSpeedSetting>();
        var c=new PodcastPlaybackSpeedCoordinator(store,work,main,applied::add);
        c.select(A,PlaybackSpeedSetting.NORMAL);assertEquals(0,store.calls);work.run();assertTrue(applied.isEmpty());main.run();
        c.select(B,PlaybackSpeedSetting.ONE_AND_QUARTER);work.run();main.run();
        assertEquals(List.of(PlaybackSpeedSetting.ONE_AND_HALF,PlaybackSpeedSetting.ONE_AND_QUARTER),applied);
    }
    @Test public void anOldPodcastOrClosedPlayerCannotApplyALateSpeed() {
        var store=new Store();store.speeds.put(A,PlaybackSpeedSetting.DOUBLE);
        var work=new Tasks();var main=new Tasks();var applied=new ArrayList<PlaybackSpeedSetting>();
        var c=new PodcastPlaybackSpeedCoordinator(store,work,main,applied::add);
        c.select(A,PlaybackSpeedSetting.NORMAL);work.run();c.select(B,PlaybackSpeedSetting.HALF);work.run();
        main.run();assertTrue(applied.isEmpty());main.run();assertEquals(List.of(PlaybackSpeedSetting.HALF),applied);
        c.select(A,PlaybackSpeedSetting.NORMAL);work.run();c.close();main.run();assertEquals(1,applied.size());
    }
    @Test public void changedDefaultsSupersedePendingInheritedResults() {
        var store=new Store();var work=new Tasks();var main=new Tasks();var applied=new ArrayList<PlaybackSpeedSetting>();
        var c=new PodcastPlaybackSpeedCoordinator(store,work,main,applied::add);
        c.select(A,PlaybackSpeedSetting.NORMAL);work.run();c.select(A,PlaybackSpeedSetting.DOUBLE);work.run();
        main.run();main.run();assertEquals(List.of(PlaybackSpeedSetting.DOUBLE),applied);
    }
    @Test public void unreadablePreferencesUseDefaultAndInvalidIdsNeverReachStorage() {
        var store=new Store();store.fail=true;var applied=new ArrayList<PlaybackSpeedSetting>();
        var c=new PodcastPlaybackSpeedCoordinator(store,Runnable::run,Runnable::run,applied::add);
        assertThrows(IllegalArgumentException.class,()->c.select("https://private.example/secret",PlaybackSpeedSetting.NORMAL));
        assertEquals(0,store.calls);c.select(A,PlaybackSpeedSetting.THREE_QUARTERS);
        assertEquals(List.of(PlaybackSpeedSetting.THREE_QUARTERS),applied);
    }
}
