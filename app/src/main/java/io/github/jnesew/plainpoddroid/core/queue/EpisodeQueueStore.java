package io.github.jnesew.plainpoddroid.core.queue;

import java.io.IOException;
import java.util.List;

/** App-private durable queue operations whose public inputs contain only opaque media IDs. */
public interface EpisodeQueueStore {
    List<EpisodeQueueEntry> listQueue() throws IOException;

    EpisodeQueueMutation append(String mediaId) throws EpisodeQueueException;

    EpisodeQueueMutation playNext(String mediaId) throws EpisodeQueueException;

    EpisodeQueueMutation remove(String mediaId) throws EpisodeQueueException;

    EpisodeQueueMutation move(String mediaId, int targetPosition)
            throws EpisodeQueueException;
}
