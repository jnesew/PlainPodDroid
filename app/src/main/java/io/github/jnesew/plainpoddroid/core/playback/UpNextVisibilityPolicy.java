package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

/** Ordinary next-title hints are useful only near the end; actionable warnings bypass this gate. */
public final class UpNextVisibilityPolicy {
    private static final long MAX_WINDOW_MILLIS = 120_000L;
    private UpNextVisibilityPolicy() { }

    public static boolean isNearEnd(long positionMillis, long durationMillis) {
        if (durationMillis <= 0L || durationMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || positionMillis < 0L || positionMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS) {
            return false;
        }
        long remaining = durationMillis - Math.min(positionMillis, durationMillis);
        long window = Math.min(MAX_WINDOW_MILLIS, durationMillis / 5L);
        return remaining <= window;
    }
}
