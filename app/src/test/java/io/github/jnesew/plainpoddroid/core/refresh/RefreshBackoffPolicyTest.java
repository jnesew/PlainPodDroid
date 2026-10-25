package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class RefreshBackoffPolicyTest {
    @Test
    public void intervalChoicesAreExactAndDefaultToSixHours() {
        assertEquals(
                List.of(0, 1, 3, 6, 12, 24),
                List.of(RefreshInterval.values()).stream()
                        .map(RefreshInterval::getHours)
                        .toList());
        assertEquals(RefreshInterval.SIX_HOURS, RefreshInterval.DEFAULT);
        assertFalse(RefreshInterval.OFF.isEnabled());
        assertEquals(RefreshInterval.THREE_HOURS, RefreshInterval.fromHours(3));
        assertThrows(IllegalArgumentException.class, () -> RefreshInterval.fromHours(2));
    }

    @Test
    public void healthyFeedsUseTheConfiguredIntervalWithSaturation() {
        RefreshBackoffPolicy policy = new RefreshBackoffPolicy();
        ScheduledRefreshCandidate candidate = new ScheduledRefreshCandidate(
                7L,
                1_000L,
                0,
                0L);

        long due = policy.dueAtMillis(candidate, RefreshInterval.ONE_HOUR);

        assertEquals(1_000L + 60L * 60L * 1_000L, due);
        assertFalse(policy.isDue(candidate, RefreshInterval.ONE_HOUR, due - 1L));
        assertTrue(policy.isDue(candidate, RefreshInterval.ONE_HOUR, due));
        ScheduledRefreshCandidate nearOverflow = new ScheduledRefreshCandidate(
                8L,
                Long.MAX_VALUE - 5L,
                0,
                0L);
        assertEquals(
                Long.MAX_VALUE,
                policy.dueAtMillis(nearOverflow, RefreshInterval.TWENTY_FOUR_HOURS));
    }

    @Test
    public void failureBackoffIsDeterministicJitteredBoundedAndCapped() {
        RefreshBackoffPolicy policy = new RefreshBackoffPolicy();
        long attempt = 1_000_000L;

        long first = policy.backoffMillis(7L, 1, attempt);
        long second = policy.backoffMillis(7L, 2, attempt);
        long seventh = policy.backoffMillis(7L, 7, attempt);
        long capped = policy.backoffMillis(7L, 31, attempt);

        assertTrue(first >= SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS);
        assertTrue(first <= SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS * 5L / 4L);
        assertTrue(second >= SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS * 2L);
        assertTrue(second <= SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS * 5L / 2L);
        assertTrue(seventh >= 16L * 60L * 60L * 1_000L);
        assertTrue(seventh <= 20L * 60L * 60L * 1_000L);
        assertEquals(SecurityLimits.MAX_REFRESH_BACKOFF_MILLIS, capped);
        assertEquals(first, policy.backoffMillis(7L, 1, attempt));
        assertTrue(first != policy.backoffMillis(8L, 1, attempt));
    }

    @Test
    public void failedCandidateUsesBackoffInsteadOfNormalInterval() {
        RefreshBackoffPolicy policy = new RefreshBackoffPolicy();
        ScheduledRefreshCandidate failed = new ScheduledRefreshCandidate(
                9L,
                100L,
                1,
                2_000L);
        long due = policy.dueAtMillis(failed, RefreshInterval.TWENTY_FOUR_HOURS);

        assertTrue(due >= 2_000L + SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS);
        assertTrue(due < 2_000L + 60L * 60L * 1_000L);
        assertEquals(
                0L,
                policy.nextDelayMillis(List.of(failed), RefreshInterval.SIX_HOURS, due));
        assertEquals(
                RefreshInterval.SIX_HOURS.getIntervalMillis(),
                policy.nextDelayMillis(List.of(), RefreshInterval.SIX_HOURS, due));
    }

    @Test
    public void invalidCandidateAndDisabledSchedulingFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduledRefreshCandidate(1L, 0L, 1, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduledRefreshCandidate(
                        1L,
                        0L,
                        SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES + 1,
                        1L));
        RefreshBackoffPolicy policy = new RefreshBackoffPolicy();
        ScheduledRefreshCandidate healthy = new ScheduledRefreshCandidate(1L, 0L, 0, 0L);
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.dueAtMillis(healthy, RefreshInterval.OFF));
    }
}
