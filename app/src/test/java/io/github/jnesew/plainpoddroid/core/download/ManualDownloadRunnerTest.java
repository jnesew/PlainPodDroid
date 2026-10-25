package io.github.jnesew.plainpoddroid.core.download;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ManualDownloadRunnerTest {
    private static String id(char c){return "episode-"+String.valueOf(c).repeat(64);}
    @Test public void fifoContinuesAfterFailureAndDoesNotRetryAuthenticationSilently()throws Exception{
        Queue queue=new Queue();queue.enqueueManualDownload(id('b'));queue.enqueueManualDownload(id('a'));
        Downloads downloads=new Downloads();downloads.failure=EpisodeDownloadException.Reason.LOGIN_REQUIRED;
        assertFalse(runner(queue,downloads,()->false).run(()->false));
        assertEquals(List.of(id('b'),id('a')),downloads.attempted);
        assertEquals(2,queue.listManualDownloads().size());
        assertTrue(queue.listManualDownloads().stream().allMatch(e->e.state()==ManualDownloadQueue.State.FAILED));
        assertFalse(runner(queue,downloads,()->false).run(()->false));assertEquals(2,downloads.attempted.size());
        downloads.failure=null;queue.enqueueManualDownload(id('b'));
        assertFalse(runner(queue,downloads,()->false).run(()->false));
        assertEquals(List.of(id('b'),id('a'),id('b')),downloads.attempted);
    }
    @Test public void jobStopPreservesWorkAndNextRunRecoversInterruptedAttempt()throws Exception{
        Queue queue=new Queue();queue.enqueueManualDownload(id('a'));
        var interrupted=queue.claimManualDownload();AtomicBoolean stopped=new AtomicBoolean();
        Downloads downloads=new Downloads(){@Override void during(String id,BooleanSupplier cancel){
            stopped.set(true);assertTrue(cancel.getAsBoolean());
        }};
        assertTrue(runner(queue,downloads,()->false).run(stopped::get));
        assertEquals(ManualDownloadQueue.State.WAITING,queue.listManualDownloads().get(0).state());
        assertFalse(queue.isManualDownloadCurrent(interrupted));
        assertFalse(runner(queue,new Downloads(),()->false).run(()->false));assertTrue(queue.listManualDownloads().isEmpty());
    }
    @Test public void cancelAndRequeueDuringTransferCannotBeRemovedByLateSuccess()throws Exception{
        Queue queue=new Queue();queue.enqueueManualDownload(id('a'));AtomicBoolean stopped=new AtomicBoolean();
        Downloads downloads=new Downloads(){@Override void during(String mediaId,BooleanSupplier cancel)throws IOException{
            var old=queue.listManualDownloads().get(0).request();queue.cancelManualDownload(mediaId);queue.enqueueManualDownload(mediaId);
            assertTrue(cancel.getAsBoolean());queue.finishManualDownload(old,null);stopped.set(true);
        }};
        assertTrue(runner(queue,downloads,()->false).run(stopped::get));
        assertEquals(1,queue.listManualDownloads().size());
        assertEquals(ManualDownloadQueue.State.WAITING,queue.listManualDownloads().get(0).state());
    }
    @Test public void offlineClearsPendingWithoutReadingNetworkAndKeepsFailedRequests()throws Exception{
        Queue queue=new Queue();queue.enqueueManualDownload(id('a'));queue.enqueueManualDownload(id('b'));
        var failed=queue.claimManualDownload();queue.finishManualDownload(failed,EpisodeDownloadException.Reason.QUOTA_EXCEEDED);
        Downloads downloads=new Downloads();assertFalse(runner(queue,downloads,()->true).run(()->false));
        assertTrue(downloads.attempted.isEmpty());assertEquals(1,queue.listManualDownloads().size());
        assertEquals(ManualDownloadQueue.State.FAILED,queue.listManualDownloads().get(0).state());
    }
    @Test public void changedAuthorityCancelsAndStorageReadFailureFailsClosed()throws Exception{
        Queue queue=new Queue();queue.enqueueManualDownload(id('a'));queue.available=false;
        Downloads downloads=new Downloads();assertFalse(runner(queue,downloads,()->false).run(()->false));
        assertEquals(EpisodeDownloadException.Reason.TARGET_CHANGED,queue.listManualDownloads().get(0).failure());
        queue.enqueueManualDownload(id('a'));queue.available=true;queue.broken=true;
        assertThrows(IOException.class,()->runner(queue,new Downloads(),()->false).run(()->false));
        assertEquals(ManualDownloadQueue.State.ACTIVE,queue.listManualDownloads().get(0).state());
    }
    @Test public void verifiedExistingDownloadCompletesRequestWithoutFailure()throws Exception{
        Queue queue=new Queue();queue.enqueueManualDownload(id('a'));
        Downloads downloads=new Downloads();downloads.failure=EpisodeDownloadException.Reason.ALREADY_DOWNLOADED;
        assertFalse(runner(queue,downloads,()->false).run(()->false));assertTrue(queue.listManualDownloads().isEmpty());
    }
    @Test public void requestModelsRejectNetworkTargetsAndInconsistentFailureState(){
        assertThrows(IllegalArgumentException.class,()->new ManualDownloadQueue.Request(1,"https://pod.example/private",1));
        assertThrows(IllegalArgumentException.class,()->new ManualDownloadQueue.Request(0,id('a'),1));
        assertThrows(IllegalArgumentException.class,()->new ManualDownloadQueue.Request(1,id('a'),-1));
        var request=new ManualDownloadQueue.Request(1,id('a'),1);
        assertThrows(IllegalArgumentException.class,()->new ManualDownloadQueue.Entry(request,ManualDownloadQueue.State.FAILED,null,"A","P"));
    }
    private static ManualDownloadRunner runner(Queue q,Downloads d,BooleanSupplier offline){return new ManualDownloadRunner(q,d,offline,()->{});}
    private static class Downloads implements EpisodeDownloadActions{
        final List<String> attempted=new ArrayList<>();EpisodeDownloadException.Reason failure;
        void during(String mediaId,BooleanSupplier cancel)throws IOException{}
        public EpisodeDownloadResult download(String mediaId,BooleanSupplier cancel)throws EpisodeDownloadException{
            attempted.add(mediaId);
            try{during(mediaId,cancel);}catch(IOException e){throw new EpisodeDownloadException(EpisodeDownloadException.Reason.STORAGE_FAILED,"Test failure");}
            if(cancel.getAsBoolean())throw new EpisodeDownloadException(EpisodeDownloadException.Reason.CANCELLED,"Cancelled");
            if(failure!=null)throw new EpisodeDownloadException(failure,"Typed failure");return new EpisodeDownloadResult(mediaId,1);
        }
        public void delete(String id){}public long getStorageUsageBytes(){return 0;}
    }
    private static final class Queue implements ManualDownloadQueue{
        final LinkedHashMap<String,Entry> rows=new LinkedHashMap<>();long nextId;boolean available=true,broken;
        public Enqueued enqueueManualDownload(String mediaId){
            var old=rows.get(mediaId);if(old!=null&&old.state()!=State.FAILED)return Enqueued.EXISTING;
            rows.remove(mediaId);rows.put(mediaId,new Entry(new Request(++nextId,mediaId,0),State.WAITING,null,"Episode","Podcast"));return Enqueued.ADDED;
        }
        public List<Entry> listManualDownloads(){return List.copyOf(rows.values());}
        public void recoverInterruptedManualDownloads(){for(var e:listManualDownloads())if(e.state()==State.ACTIVE)deferManualDownload(e.request());}
        public Request claimManualDownload(){for(var e:listManualDownloads())if(e.state()==State.WAITING){
            var r=new Request(e.request().id(),e.request().mediaId(),e.request().attempt()+1);rows.put(r.mediaId(),new Entry(r,State.ACTIVE,null,e.title(),e.podcastTitle()));return r;
        }return null;}
        public boolean isManualDownloadCurrent(Request r){var e=rows.get(r.mediaId());return e!=null&&e.state()==State.ACTIVE&&e.request().equals(r);}
        public boolean canRunManualDownload(Request r)throws IOException{if(broken)throw new IOException("Unavailable");return available&&isManualDownloadCurrent(r);}
        public void finishManualDownload(Request r,EpisodeDownloadException.Reason reason){if(isManualDownloadCurrent(r)){
            if(reason==null)rows.remove(r.mediaId());else rows.put(r.mediaId(),new Entry(r,State.FAILED,reason,"Episode","Podcast"));}}
        public void deferManualDownload(Request r){if(isManualDownloadCurrent(r))rows.put(r.mediaId(),new Entry(r,State.WAITING,null,"Episode","Podcast"));}
        public void cancelManualDownload(String id){rows.remove(id);}
        public void clearCompletedManualDownloads(){}
        public void clearPendingManualDownloads(){rows.values().removeIf(e->e.state()!=State.FAILED);}
        public void failPendingManualDownloads(EpisodeDownloadException.Reason reason){for(var e:listManualDownloads())if(e.state()!=State.FAILED)rows.put(e.request().mediaId(),new Entry(e.request(),State.FAILED,reason,"Episode","Podcast"));}
        public boolean hasWaitingManualDownloads(){return rows.values().stream().anyMatch(e->e.state()==State.WAITING);}
    }
}
