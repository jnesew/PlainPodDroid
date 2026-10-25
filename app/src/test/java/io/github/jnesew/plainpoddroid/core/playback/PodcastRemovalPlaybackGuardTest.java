package io.github.jnesew.plainpoddroid.core.playback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PodcastRemovalPlaybackGuardTest {
    @Test public void onlyStopsTheAffectedShowIncludingPendingDownloadPlayback(){
        for(long owner:new long[]{1,2}){
            List<String> calls=new ArrayList<>();
            var guard=new PodcastRemovalPlaybackGuard(Runnable::run,Runnable::run,()->"pending-audio",id->owner,()->calls.add("stop"));
            guard.prepare(1,()->true,()->calls.add("remove"),()->calls.add("failed"));
            assertEquals(owner==1?List.of("stop","remove"):List.of("remove"),calls);
        }
    }
    @Test public void changedPlaybackRechecksMembershipInsteadOfStoppingAnotherShow(){
        ArrayDeque<Runnable> work=new ArrayDeque<>(),callbacks=new ArrayDeque<>();
        List<String> calls=new ArrayList<>();String[] current={"first"};
        var guard=new PodcastRemovalPlaybackGuard(work::add,callbacks::add,()->current[0],id->id.equals("first")?1:2,()->calls.add("stop"));
        guard.prepare(1,()->true,()->calls.add("remove"),()->calls.add("failed"));
        work.remove().run();current[0]="second";callbacks.remove().run();assertTrue(calls.isEmpty());
        work.remove().run();callbacks.remove().run();assertEquals(List.of("remove"),calls);
    }
    @Test public void unavailableMembershipAndRetiredCallbacksNeverStopAudioOrRemove(){
        ArrayDeque<Runnable> work=new ArrayDeque<>();List<String> calls=new ArrayList<>();boolean[] valid={true};
        var guard=new PodcastRemovalPlaybackGuard(work::add,Runnable::run,()->"audio",id->{throw new java.io.IOException();},()->calls.add("stop"));
        guard.prepare(1,()->valid[0],()->calls.add("remove"),()->calls.add("failed"));
        work.remove().run();assertEquals(List.of("failed"),calls);calls.clear();
        guard.prepare(1,()->valid[0],()->calls.add("remove"),()->calls.add("failed"));valid[0]=false;
        work.remove().run();assertTrue(calls.isEmpty());
    }
    @Test public void stoppedPlayerNeedsNoCatalogReadAndUnstableQueueFailsBoundedly(){
        List<String> calls=new ArrayList<>();
        new PodcastRemovalPlaybackGuard(Runnable::run,Runnable::run,()->"",id->{throw new AssertionError();},()->calls.add("stop"))
                .prepare(1,()->true,()->calls.add("remove"),()->calls.add("failed"));
        assertEquals(List.of("remove"),calls);calls.clear();int[] id={0};
        new PodcastRemovalPlaybackGuard(Runnable::run,Runnable::run,()->"audio"+id[0],media->{id[0]++;return 1;},()->calls.add("stop"))
                .prepare(1,()->true,()->calls.add("remove"),()->calls.add("failed"));
        assertEquals(List.of("failed"),calls);assertEquals(3,id[0]);
    }
}
