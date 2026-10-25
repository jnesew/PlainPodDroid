package io.github.jnesew.plainpoddroid.core.episode;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

/** Previewed backlog scope; a later feed refresh cannot add targets to confirmation. */
public record PodcastPlayedBatch(long subscriptionId,long generation,long beforePublishedAtMillis,int count) {
    public PodcastPlayedBatch {
        if(subscriptionId<1||generation<1||beforePublishedAtMillis<0||count<0
                ||count>SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION)throw new IllegalArgumentException("Invalid backlog scope.");
    }
}
