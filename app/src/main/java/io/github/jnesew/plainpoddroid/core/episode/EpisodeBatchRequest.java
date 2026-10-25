package io.github.jnesew.plainpoddroid.core.episode;
import java.util.*;

/** A bounded explicit selection of opaque catalog identities, in visible order. */
public record EpisodeBatchRequest(List<String> mediaIds) {
    public static final int MAX_EPISODES=100;
    public enum Action { MARK_PLAYED, MARK_UNPLAYED, QUEUE_LAST, QUEUE_NEXT, DOWNLOAD }
    public EpisodeBatchRequest {
        Objects.requireNonNull(mediaIds);
        if(mediaIds.isEmpty()||mediaIds.size()>MAX_EPISODES)throw new IllegalArgumentException("Invalid episode selection.");
        for(String id:mediaIds)if(id==null||!id.matches("episode-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid episode selection.");
        mediaIds=List.copyOf(new LinkedHashSet<>(mediaIds));
    }
}
