package io.github.jnesew.plainpoddroid.core.playback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackConnectionTest {
    private final List<Object> attached = new ArrayList<>();
    private final List<Object> detached = new ArrayList<>();
    private final List<FakePending> requests = new ArrayList<>();
    private final PlaybackConnection<Object> owner = new PlaybackConnection<>(() -> {
        FakePending pending = new FakePending();
        requests.add(pending);
        return pending;
    }, new PlaybackConnection.Listener<>() {
        public void onConnected(Object controller) { attached.add(controller); }
        public void onDetached(Object controller) { detached.add(controller); }
        public void onStateChanged() { }
    });

    @Test public void repeatedStartSharesOnePendingAndConnectedController() {
        owner.start(); owner.start();
        assertEquals(1, requests.size());
        assertTrue(owner.isConnecting());
        requests.get(0).finish();
        owner.start();
        assertEquals(1, requests.size());
        assertEquals(List.of(requests.get(0).controller), attached);
        assertFalse(owner.isConnecting());
    }

    @Test public void stopReleasesPendingAndLateCompletionCannotReattach() {
        owner.start();
        FakePending old = requests.get(0);
        owner.stop(); old.finish();
        assertEquals(1, old.releases);
        assertEquals(0, old.reads);
        assertNull(owner.getConnected());
        assertTrue(attached.isEmpty());
    }

    @Test public void previousCompletionCannotReplaceNewConnection() {
        owner.start(); owner.stop(); owner.start();
        FakePending old = requests.get(0), current = requests.get(1);
        old.finish(); current.finish();
        assertSame(current.controller, owner.getConnected());
        assertEquals(List.of(current.controller), attached);
        owner.disconnected(old.controller);
        assertSame(current.controller, owner.getConnected());
    }

    @Test public void stopDetachesAndReleasesConnectedControllerOnlyOnce() {
        owner.start(); requests.get(0).finish();
        owner.stop(); owner.stop();
        assertEquals(List.of(requests.get(0).controller), detached);
        assertEquals(1, requests.get(0).releases);
        assertNull(owner.getConnected());
    }

    @Test public void failedFutureIsReleasedAndCanBeRetried() {
        owner.start(); requests.get(0).failed = true; requests.get(0).finish();
        assertNull(owner.getConnected());
        assertFalse(owner.isConnecting());
        assertEquals(1, requests.get(0).releases);
        owner.start(); requests.get(1).finish();
        assertSame(requests.get(1).controller, owner.getConnected());
    }

    private static final class FakePending implements PlaybackConnection.Pending<Object> {
        final Object controller = new Object();
        Runnable completion;
        boolean failed;
        int releases, reads;
        public void whenReady(Runnable completion) { this.completion = completion; }
        public Object get() throws ExecutionException {
            reads++;
            if (failed) throw new ExecutionException(new IllegalStateException("Failure"));
            return controller;
        }
        public void release() { releases++; }
        void finish() { completion.run(); }
    }
}
