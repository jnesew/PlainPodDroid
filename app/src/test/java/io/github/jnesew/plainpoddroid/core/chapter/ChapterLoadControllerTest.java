package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChapterLoadControllerTest {
    private static final String ID="episode-"+"a".repeat(64);
    private static final String OTHER="episode-"+"b".repeat(64);
    private static final Executor DIRECT=Runnable::run;
    private static final Executor REJECT=task->{throw new RejectedExecutionException();};

    @Test public void doesNothingUntilExplicitLoadAndRetainsOnlyReadyDocument() throws Exception {
        AtomicInteger requests=new AtomicInteger(); Queue worker=new Queue(); List<ChapterLoadController.State> states=new ArrayList<>();
        ChapterLoadController controller=controller(catalog(ID),requests,worker,DIRECT,states);
        assertEquals(0,requests.get()); assertEquals(ChapterLoadController.Phase.IDLE,controller.getState().getPhase());
        assertTrue(controller.load(ID)); assertTrue(controller.getState().isBusy());
        assertFalse(controller.load(OTHER)); assertFalse(controller.reset()); assertEquals(0,requests.get());
        worker.runAll(); assertEquals(1,requests.get());
        assertEquals(ID,controller.getState().getMediaId()); assertNotNull(controller.getState().getDocument());
        assertEquals(ChapterLoadController.Phase.READY,controller.getState().getPhase());
        assertTrue(controller.reset()); assertNull(controller.getState().getDocument());
    }

    @Test public void invalidIdCannotReachCatalogOrTransport() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        EpisodeChapterCatalog catalog=id->{fail("lookup must not run"); return null;};
        ChapterLoadController controller=controller(catalog,requests,DIRECT,DIRECT,new ArrayList<>());
        assertFalse(controller.load("https://publisher.example/chapters"));
        assertEquals(ChapterLoadController.Failure.INVALID_MEDIA_ID,controller.getState().getFailure());
        assertEquals(0,requests.get());
    }

    @Test public void missingMismatchedAndFailedLookupNeverFetch() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        List<EpisodeChapterCatalog> catalogs=List.of(id->null,catalog(OTHER),id->{throw new IOException("secret");});
        for(EpisodeChapterCatalog catalog:catalogs) {
            ChapterLoadController controller=controller(catalog,requests,DIRECT,DIRECT,new ArrayList<>());
            assertTrue(controller.load(ID)); assertEquals(ChapterLoadController.Phase.ERROR,controller.getState().getPhase());
            assertNull(controller.getState().getDocument());
        }
        assertEquals(0,requests.get());
    }

    @Test public void workerAndCallbackRejectionCannotLeaveBusyOrFetch() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        for(Executor[] executors:List.of(new Executor[]{REJECT,DIRECT},new Executor[]{DIRECT,REJECT})) {
            ChapterLoadController controller=controller(catalog(ID),requests,executors[0],executors[1],new ArrayList<>());
            assertFalse(controller.load(ID)); assertFalse(controller.getState().isBusy());
            assertEquals(ChapterLoadController.Failure.BACKGROUND_WORK_UNAVAILABLE,controller.getState().getFailure());
        }
        assertEquals(0,requests.get());
    }

    @Test public void closeBeforeQueuedWorkerPreventsPublisherContact() throws Exception {
        AtomicInteger requests=new AtomicInteger(); Queue worker=new Queue(); Queue callbacks=new Queue();
        List<ChapterLoadController.State> states=new ArrayList<>();
        ChapterLoadController controller=controller(catalog(ID),requests,worker,callbacks,states);
        assertTrue(controller.load(ID)); controller.close(); worker.runAll(); callbacks.runAll();
        assertEquals(0,requests.get()); assertTrue(states.isEmpty()); assertFalse(controller.load(ID));
        assertNull(controller.getState().getDocument());
    }

    @Test public void resetSuppressesOldCompletionCallback() throws Exception {
        AtomicInteger requests=new AtomicInteger(); Queue callbacks=new Queue();
        List<ChapterLoadController.State> states=new ArrayList<>();
        ChapterLoadController controller=controller(catalog(ID),requests,DIRECT,callbacks,states);
        assertTrue(controller.load(ID)); assertTrue(controller.reset()); callbacks.runAll();
        assertEquals(1,states.size()); assertEquals(ChapterLoadController.Phase.IDLE,states.get(0).getPhase());
    }

    @Test public void transportFailuresAreRedactedAndRetryable() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        ChapterTransport transport=url->{requests.incrementAndGet();throw new IOException("private token secret");};
        ChapterLoadController controller=new ChapterLoadController(new EpisodeChapterCoordinator(catalog(ID),
                new BoundedChapterAcquirer(transport)),DIRECT,DIRECT,state->{});
        assertTrue(controller.load(ID));
        assertEquals(ChapterLoadController.Failure.LOAD_FAILED,controller.getState().getFailure());
        assertNull(controller.getState().getDocument()); assertTrue(controller.load(ID)); assertEquals(2,requests.get());
    }

    private static EpisodeChapterCatalog catalog(String targetId) throws Exception {
        EpisodeChapterTarget target=new EpisodeChapterTarget(targetId,CanonicalHttpsUrl.parse("https://publisher.example/chapters"));
        return id->target;
    }
    private static ChapterLoadController controller(EpisodeChapterCatalog catalog,AtomicInteger requests,
            Executor worker,Executor callbacks,List<ChapterLoadController.State> states) {
        ChapterTransport transport=url->{
            requests.incrementAndGet();
            return new ChapterTransport.Exchange() {
                @Override public int getStatusCode(){return 200;}
                @Override public String getHeader(String name){return name.equals("Content-Type")?"application/json+chapters":null;}
                @Override public InputStream getBody(){return new ByteArrayInputStream("{\"version\":\"1.2\",\"chapters\":[]}".getBytes(StandardCharsets.UTF_8));}
                @Override public void close(){}
            };
        };
        return new ChapterLoadController(new EpisodeChapterCoordinator(catalog,new BoundedChapterAcquirer(transport)),
                worker,callbacks,states::add);
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        @Override public void execute(Runnable task){tasks.add(task);}
        void runAll(){while(!tasks.isEmpty()){tasks.removeFirst().run();}}
    }
}
