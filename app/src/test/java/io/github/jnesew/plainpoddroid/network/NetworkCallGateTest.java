package io.github.jnesew.plainpoddroid.network;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class NetworkCallGateTest {
    @Test
    public void holdsBothLimitsUntilTheLeaseCloses() throws Exception {
        NetworkCallGate gate = new NetworkCallGate(1, 1);
        NetworkCallGate.Lease first = gate.acquireFeed(0L, TimeUnit.MILLISECONDS);

        NetworkTransportException busy = assertThrows(
                NetworkTransportException.class,
                () -> gate.acquireFeed(0L, TimeUnit.MILLISECONDS));
        assertEquals(NetworkTransportException.Reason.CONCURRENCY_LIMIT, busy.getReason());

        first.close();
        first.close();
        NetworkCallGate.Lease second = gate.acquireFeed(0L, TimeUnit.MILLISECONDS);
        second.close();
    }

    @Test
    public void interruptionIsTypedAndPreservesTheInterruptFlag() {
        NetworkCallGate gate = new NetworkCallGate(1, 1);
        Thread.currentThread().interrupt();
        try {
            NetworkTransportException interrupted = assertThrows(
                    NetworkTransportException.class,
                    () -> gate.acquireFeed(1L, TimeUnit.SECONDS));
            assertEquals(NetworkTransportException.Reason.INTERRUPTED, interrupted.getReason());
            assertEquals(true, Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void mediaAndFeedHaveSeparateCategoryCapsButShareGlobalCap()
            throws Exception {
        NetworkCallGate gate = new NetworkCallGate(2, 2, 1);
        NetworkCallGate.Lease media = gate.acquireMedia(0L, TimeUnit.MILLISECONDS);

        NetworkTransportException mediaBusy = assertThrows(
                NetworkTransportException.class,
                () -> gate.acquireMedia(0L, TimeUnit.MILLISECONDS));
        assertEquals(NetworkTransportException.Reason.CONCURRENCY_LIMIT,
                mediaBusy.getReason());

        NetworkCallGate.Lease feed = gate.acquireFeed(0L, TimeUnit.MILLISECONDS);
        NetworkTransportException globallyBusy = assertThrows(
                NetworkTransportException.class,
                () -> gate.acquireFeed(0L, TimeUnit.MILLISECONDS));
        assertEquals(NetworkTransportException.Reason.CONCURRENCY_LIMIT,
                globallyBusy.getReason());

        feed.close();
        media.close();
        NetworkCallGate.Lease afterClose =
                gate.acquireMedia(0L, TimeUnit.MILLISECONDS);
        afterClose.close();
    }

    @Test
    public void rejectsImpossibleLimitCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkCallGate(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NetworkCallGate(2, 3));
        assertThrows(IllegalArgumentException.class, () -> new NetworkCallGate(2, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> new NetworkCallGate(2, 1, 0));
    }

    @Test
    public void playbackSharesGlobalCapWithoutConsumingDownloadCapacity()
            throws Exception {
        NetworkCallGate gate = new NetworkCallGate(2, 1, 1);
        NetworkCallGate.Lease playback =
                gate.acquirePlayback(0L, TimeUnit.MILLISECONDS);
        NetworkCallGate.Lease media = gate.acquireMedia(0L, TimeUnit.MILLISECONDS);

        NetworkTransportException globallyBusy = assertThrows(
                NetworkTransportException.class,
                () -> gate.acquirePlayback(0L, TimeUnit.MILLISECONDS));
        assertEquals(NetworkTransportException.Reason.CONCURRENCY_LIMIT,
                globallyBusy.getReason());

        playback.close();
        NetworkCallGate.Lease secondPlayback =
                gate.acquirePlayback(0L, TimeUnit.MILLISECONDS);
        secondPlayback.close();
        media.close();
    }
}
