package io.github.jnesew.plainpoddroid.core.playback;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** One visible-owner connection; stopping also releases a connection still in flight. */
public final class PlaybackConnection<C> {
    public interface Pending<C> {
        void whenReady(Runnable completion);
        C get() throws InterruptedException, ExecutionException;
        void release();
    }

    public interface Listener<C> {
        void onConnected(C connection);
        void onDetached(C connection);
        void onStateChanged();
    }

    private final Supplier<Pending<C>> factory;
    private final Listener<C> listener;
    private Pending<C> pending;
    private C connected;

    public PlaybackConnection(Supplier<Pending<C>> factory, Listener<C> listener) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public C getConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return pending != null && connected == null;
    }

    public void start() {
        if (pending != null || connected != null) return;
        Pending<C> next = Objects.requireNonNull(factory.get(), "pending connection");
        pending = next;
        listener.onStateChanged();
        next.whenReady(() -> complete(next));
    }

    private void complete(Pending<C> expected) {
        if (pending != expected) return;
        try {
            connected = Objects.requireNonNull(expected.get(), "connected controller");
            listener.onConnected(connected);
        } catch (CancellationException | ExecutionException exception) {
            pending = null;
            expected.release();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pending = null;
            expected.release();
        }
        listener.onStateChanged();
    }

    public void stop() {
        Pending<C> previous = pending;
        C controller = connected;
        pending = null;
        connected = null;
        if (controller != null) listener.onDetached(controller);
        if (previous != null) previous.release();
        listener.onStateChanged();
    }

    public void disconnected(C controller) {
        if (connected != controller || controller == null) return;
        stop();
    }
}
