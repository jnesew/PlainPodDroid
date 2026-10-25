package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;

import java.util.Objects;

public final class SubscriptionRefreshTarget {
    private final long subscriptionId;
    private final long generation;
    private final CanonicalHttpsUrl subscriptionUrl;
    private final FeedValidators validators;

    public SubscriptionRefreshTarget(
            long subscriptionId,
            CanonicalHttpsUrl subscriptionUrl,
            FeedValidators validators,
            long generation) {
        if (subscriptionId < 1L || generation < 1L) {
            throw new IllegalArgumentException("Invalid subscription ID.");
        }
        this.subscriptionId = subscriptionId;
        this.generation = generation;
        this.subscriptionUrl = Objects.requireNonNull(subscriptionUrl, "subscriptionUrl");
        this.validators = Objects.requireNonNull(validators, "validators");
    }

    /** A response from an earlier membership must never update a reactivated subscription. */
    public boolean matches(long id,long currentGeneration,CanonicalHttpsUrl source){
        return subscriptionId==id&&generation==currentGeneration&&subscriptionUrl.equals(source);
    }

    public long getGeneration() { return generation; }

    public long getSubscriptionId() {
        return subscriptionId;
    }

    public CanonicalHttpsUrl getSubscriptionUrl() {
        return subscriptionUrl;
    }

    public FeedValidators getValidators() {
        return validators;
    }
}
