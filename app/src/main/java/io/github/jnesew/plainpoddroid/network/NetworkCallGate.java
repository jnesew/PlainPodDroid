package io.github.jnesew.plainpoddroid.network;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class NetworkCallGate {
    private final Semaphore allCalls;
    private final Semaphore feedCalls;
    private final Semaphore mediaCalls;

    NetworkCallGate() {
        this(
                SecurityLimits.MAX_CONCURRENT_NETWORK_CALLS,
                SecurityLimits.MAX_CONCURRENT_FEED_CALLS,
                SecurityLimits.MAX_CONCURRENT_MEDIA_CALLS);
    }

    NetworkCallGate(int maximumCalls, int maximumFeedCalls) {
        this(maximumCalls, maximumFeedCalls, maximumCalls);
    }

    NetworkCallGate(int maximumCalls, int maximumFeedCalls, int maximumMediaCalls) {
        if (maximumCalls <= 0 || maximumFeedCalls <= 0
                || maximumFeedCalls > maximumCalls
                || maximumMediaCalls <= 0
                || maximumMediaCalls > maximumCalls) {
            throw new IllegalArgumentException("Invalid network concurrency limits.");
        }
        allCalls = new Semaphore(maximumCalls, true);
        feedCalls = new Semaphore(maximumFeedCalls, true);
        mediaCalls = new Semaphore(maximumMediaCalls, true);
    }

    // Chapters share the feed/metadata category rather than adding concurrency capacity.
    Lease acquireMetadata() throws NetworkTransportException {
        return acquireFeed();
    }

    Lease acquireFeed() throws NetworkTransportException {
        return acquireFeed(SecurityLimits.FEED_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    Lease acquireFeed(long timeout, TimeUnit unit) throws NetworkTransportException {
        return acquire(feedCalls, timeout, unit);
    }

    Lease acquireMedia() throws NetworkTransportException {
        return acquireMedia(SecurityLimits.MEDIA_RESPONSE_HEADER_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
    }

    Lease acquireMedia(long timeout, TimeUnit unit) throws NetworkTransportException {
        return acquire(mediaCalls, timeout, unit);
    }

    Lease acquirePlayback() throws NetworkTransportException {
        return acquirePlayback(
                SecurityLimits.MEDIA_RESPONSE_HEADER_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
    }

    Lease acquirePlayback(long timeout, TimeUnit unit) throws NetworkTransportException {
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        try {
            if (!allCalls.tryAcquire(timeout, unit)) {
                throw busy();
            }
            return new Lease(allCalls, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NetworkTransportException(
                    NetworkTransportException.Reason.INTERRUPTED,
                    "Network request was interrupted.");
        }
    }

    private Lease acquire(Semaphore category, long timeout, TimeUnit unit)
            throws NetworkTransportException {
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        boolean categoryAcquired = false;
        boolean allAcquired = false;
        try {
            categoryAcquired = category.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!categoryAcquired) {
                throw busy();
            }
            long remaining = Math.max(0L, deadline - System.nanoTime());
            allAcquired = allCalls.tryAcquire(remaining, TimeUnit.NANOSECONDS);
            if (!allAcquired) {
                throw busy();
            }
            return new Lease(allCalls, category);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NetworkTransportException(
                    NetworkTransportException.Reason.INTERRUPTED,
                    "Network request was interrupted.");
        } finally {
            if (categoryAcquired && !allAcquired) {
                category.release();
            }
        }
    }

    private static NetworkTransportException busy() {
        return new NetworkTransportException(
                NetworkTransportException.Reason.CONCURRENCY_LIMIT,
                "Network concurrency limit was reached.");
    }

    static final class Lease implements AutoCloseable {
        private final Semaphore allCalls;
        private final Semaphore categoryCalls;
        private boolean closed;

        private Lease(Semaphore allCalls, Semaphore categoryCalls) {
            this.allCalls = allCalls;
            this.categoryCalls = categoryCalls;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            allCalls.release();
            if (categoryCalls != null) {
                categoryCalls.release();
            }
        }
    }
}
