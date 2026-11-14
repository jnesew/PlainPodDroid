package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueException;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueStore;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Transient service-owned queue continuation. Queue/file reads stay off the player thread. */
public final class PlaybackQueueAdvancer {
    private final EpisodeQueueStore queue;
    private final PlaybackRequestResolver resolver;
    private final Executor worker;
    private final Executor callback;
    private long generation;
    private boolean pending;

    public PlaybackQueueAdvancer(EpisodeQueueStore queue, PlaybackRequestResolver resolver,
            Executor worker, Executor callback) {
        this.queue = Objects.requireNonNull(queue);
        this.resolver = Objects.requireNonNull(resolver);
        this.worker = Objects.requireNonNull(worker);
        this.callback = Objects.requireNonNull(callback);
    }

    /**
     * Invoke only for natural completion with autoplay still intended, never pause/error/stop.
     * Reports either a source or unavailable; cancellation delivers neither.
     */
    public synchronized void advance(String completedId, Consumer<PlaybackSource> ready,
            Runnable unavailable) {
        if (pending) {
            return;
        }
        pending = true;
        long ticket = ++generation;
        try {
            worker.execute(() -> resolveNext(ticket, completedId, ready, unavailable));
        } catch (RuntimeException exception) {
            pending = false;
            unavailable.run();
        }
    }

    public synchronized void cancel() {
        generation++;
        pending = false;
    }

    private synchronized boolean isCurrent(long ticket) {
        return pending && ticket == generation;
    }

    private void resolveNext(long ticket, String completedId, Consumer<PlaybackSource> ready,
            Runnable unavailable) {
        if (!isCurrent(ticket)) {
            return;
        }
        PlaybackSource next = null;
        try {
            // Consume only the finished item, even when it was played from outside Queue.
            try {
                queue.remove(completedId);
            } catch (EpisodeQueueException exception) {
                if (exception.getReason() != EpisodeQueueException.Reason.NOT_QUEUED) {
                    throw exception;
                }
            }
            var entries = queue.listQueue();
            if (!entries.isEmpty() && isCurrent(ticket)) {
                var entry = entries.get(0);
                var result = resolver.resolve(entry.getMediaId());
                var latest = queue.listQueue();
                if (result.isAllowed() && !latest.isEmpty()
                        && entry.getMediaId().equals(latest.get(0).getMediaId())) {
                    var source = result.getSource();
                    next = new PlaybackSource(source.getMediaId(), source.getUri(), source.getKind(),
                            source.getMimeType(), source.getTitle(), source.getAuthor(),
                            entry.isPlayed() ? 0L : source.getPlaybackPositionMillis());
                }
            }
        } catch (IOException | RuntimeException exception) {
            // Leave an unavailable head in place. Never skip it or fall back to streaming.
        }
        PlaybackSource resolved = next;
        try {
            callback.execute(() -> {
                synchronized (this) {
                    if (!isCurrent(ticket)) {
                        return;
                    }
                    pending = false;
                }
                if (resolved != null) {
                    ready.accept(resolved);
                } else {
                    unavailable.run();
                }
            });
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (ticket == generation) {
                    pending = false;
                }
            }
        }
    }
}
