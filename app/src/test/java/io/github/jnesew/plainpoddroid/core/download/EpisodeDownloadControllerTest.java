package io.github.jnesew.plainpoddroid.core.download;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/** Deletion lifetime only. Durable transfer behavior is covered by runner/SQLite tests. */
public final class EpisodeDownloadControllerTest {
    private static final String ID="episode-"+"a".repeat(64);
    @Test public void deletionRunsOnWorkerAndPublishesSuccess(){
        Actions actions=new Actions();Queued worker=new Queued();var states=new ArrayList<EpisodeDownloadController.State>();
        var controller=new EpisodeDownloadController(actions,worker,Runnable::run,states::add);
        assertTrue(controller.delete(ID));assertNull(actions.deleted);assertTrue(controller.getState().isBusy());
        worker.next();assertEquals(ID,actions.deleted);assertEquals(2,states.size());
        assertEquals(EpisodeDownloadController.Phase.SUCCEEDED,controller.getState().getPhase());
    }
    @Test public void invalidUrlNeverReachesDeletionWorkerOrState(){
        Queued worker=new Queued();var controller=new EpisodeDownloadController(new Actions(),worker,Runnable::run,s->{});
        assertFalse(controller.delete("https://media.example/private"));assertTrue(worker.tasks.isEmpty());
        assertEquals("",controller.getState().getMediaId());assertEquals(EpisodeDownloadController.Failure.INVALID_SELECTION,controller.getState().getFailure());
    }
    @Test public void busyDeletionDoesNotAcceptOrQueueAnotherOperation(){
        Queued worker=new Queued();var controller=new EpisodeDownloadController(new Actions(),worker,Runnable::run,s->{});
        assertTrue(controller.delete(ID));assertFalse(controller.delete("episode-"+"b".repeat(64)));
        assertFalse(controller.delete("bad"));assertEquals(1,worker.tasks.size());assertEquals(ID,controller.getState().getMediaId());
    }
    @Test public void closeSuppressesCallbacksAndNewRequestsWhileAuthorizedDeletionFinishes(){
        Actions actions=new Actions();Queued worker=new Queued();var states=new ArrayList<EpisodeDownloadController.State>();
        var controller=new EpisodeDownloadController(actions,worker,Runnable::run,states::add);
        controller.delete(ID);controller.close();worker.next();
        assertEquals(ID,actions.deleted);assertEquals(1,states.size());assertFalse(controller.delete(ID));
    }
    @Test public void unavailableWorkerReturnsTypedFailureWithoutExceptionText(){
        var controller=new EpisodeDownloadController(new Actions(),r->{throw new RejectedExecutionException("private diagnostic");},Runnable::run,s->{});
        assertTrue(controller.delete(ID));assertFalse(controller.getState().isBusy());
        assertEquals(EpisodeDownloadController.Failure.BACKGROUND_WORK_UNAVAILABLE,controller.getState().getFailure());
    }
    @Test public void readerLeaseAndStorageFailuresRemainDistinctAndUnexpectedFailureClearsBusy(){
        for(var reason:List.of(EpisodeDownloadException.Reason.PLAYBACK_IN_USE,EpisodeDownloadException.Reason.ALREADY_ACTIVE,EpisodeDownloadException.Reason.STORAGE_FAILED)){
            Actions actions=new Actions();actions.failure=reason;var controller=new EpisodeDownloadController(actions,Runnable::run,Runnable::run,s->{});
            controller.delete(ID);assertFalse(controller.getState().isBusy());
            assertEquals(reason.name(),controller.getState().getFailure().name());
        }
        Actions actions=new Actions();actions.broken=true;var controller=new EpisodeDownloadController(actions,Runnable::run,Runnable::run,s->{});
        controller.delete(ID);assertEquals(EpisodeDownloadController.Failure.STORAGE_FAILED,controller.getState().getFailure());
    }
    private static final class Queued implements Executor{
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable r){tasks.add(r);}void next(){tasks.remove().run();}
    }
    private static final class Actions implements EpisodeDownloadActions{
        String deleted;EpisodeDownloadException.Reason failure;boolean broken;
        public EpisodeDownloadResult download(String id,BooleanSupplier cancelled){throw new AssertionError("Deletion must not download");}
        public void delete(String id)throws EpisodeDownloadException{
            deleted=id;if(broken)throw new IllegalStateException("Private diagnostic");
            if(failure!=null)throw new EpisodeDownloadException(failure,"Private diagnostic");
        }
        public long getStorageUsageBytes(){return 0;}
    }
}
