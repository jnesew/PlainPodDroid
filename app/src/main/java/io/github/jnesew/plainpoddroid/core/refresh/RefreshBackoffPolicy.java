package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.List;
import java.util.Objects;

public final class RefreshBackoffPolicy {
    public long dueAtMillis(
            ScheduledRefreshCandidate candidate,
            RefreshInterval interval) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(interval, "interval");
        if (!interval.isEnabled()) {
            throw new IllegalArgumentException("Automatic refresh is disabled.");
        }
        if (candidate.getConsecutiveFailures() == 0) {
            return saturatedAdd(
                    candidate.getUpdatedAtMillis(),
                    interval.getIntervalMillis());
        }
        long backoff = backoffMillis(
                candidate.getSubscriptionId(),
                candidate.getConsecutiveFailures(),
                candidate.getLastAttemptAtMillis());
        return saturatedAdd(candidate.getLastAttemptAtMillis(), backoff);
    }

    public boolean isDue(
            ScheduledRefreshCandidate candidate,
            RefreshInterval interval,
            long nowMillis) {
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("Invalid current time.");
        }
        return dueAtMillis(candidate, interval) <= nowMillis;
    }

    public long nextDelayMillis(
            List<ScheduledRefreshCandidate> candidates,
            RefreshInterval interval,
            long nowMillis) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(interval, "interval");
        if (nowMillis < 0L || !interval.isEnabled()) {
            throw new IllegalArgumentException("Invalid scheduling request.");
        }
        if (candidates.isEmpty()) {
            return interval.getIntervalMillis();
        }
        long earliest = Long.MAX_VALUE;
        for (ScheduledRefreshCandidate candidate : candidates) {
            earliest = Math.min(earliest, dueAtMillis(candidate, interval));
        }
        return earliest <= nowMillis ? 0L : earliest - nowMillis;
    }

    public long backoffMillis(
            long subscriptionId,
            int consecutiveFailures,
            long attemptedAtMillis) {
        if (subscriptionId < 1L || consecutiveFailures < 1
                || consecutiveFailures > SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES
                || attemptedAtMillis < 1L) {
            throw new IllegalArgumentException("Invalid refresh failure state.");
        }
        long base = SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS;
        for (int index = 1; index < consecutiveFailures; index++) {
            if (base >= SecurityLimits.MAX_REFRESH_BACKOFF_MILLIS / 2L) {
                base = SecurityLimits.MAX_REFRESH_BACKOFF_MILLIS;
                break;
            }
            base *= 2L;
        }
        if (base >= SecurityLimits.MAX_REFRESH_BACKOFF_MILLIS) {
            return SecurityLimits.MAX_REFRESH_BACKOFF_MILLIS;
        }
        long jitterLimit = Math.min(
                base / 4L,
                SecurityLimits.MAX_REFRESH_BACKOFF_MILLIS - base);
        long mixed = mix(subscriptionId, consecutiveFailures, attemptedAtMillis);
        long jitter = Math.floorMod(mixed, jitterLimit + 1L);
        return base + jitter;
    }

    private static long mix(long subscriptionId, int failures, long attemptedAtMillis) {
        long value = subscriptionId * 0x9e3779b97f4a7c15L;
        value ^= attemptedAtMillis + 0x632be59bd9b4e019L;
        value ^= (long) failures * 0x94d049bb133111ebL;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
