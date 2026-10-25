package io.github.jnesew.plainpoddroid.core.subscription;

public interface SubscriptionRefresher {
    void refresh(long subscriptionId) throws SubscriptionRefreshException;
}
