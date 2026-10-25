package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

/** Pure normalization and completion policy for player-reported positions. */
public final class PlaybackProgressPolicy {
    public static final long UNKNOWN_DURATION_MILLIS = -1L;

    private PlaybackProgressPolicy() {
        throw new AssertionError("No instances");
    }

    public static long normalizeDuration(long durationMillis) {
        if (durationMillis <= 0L
                || durationMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS) {
            return UNKNOWN_DURATION_MILLIS;
        }
        return durationMillis;
    }

    public static long normalizePosition(long positionMillis, long durationMillis) {
        long bounded = Math.max(0L, Math.min(
                positionMillis,
                SecurityLimits.MAX_MEDIA_DURATION_MILLIS));
        long normalizedDuration = normalizeDuration(durationMillis);
        if (normalizedDuration != UNKNOWN_DURATION_MILLIS) {
            return Math.min(bounded, normalizedDuration);
        }
        return bounded;
    }

    public static boolean isCompleted(long positionMillis, long durationMillis) {
        return isCompleted(positionMillis, durationMillis, CompletionMode.NEAR_END);
    }

    public static boolean isCompleted(
            long positionMillis, long durationMillis, CompletionMode mode) {
        if (mode == null) throw new IllegalArgumentException("Completion mode is required.");
        if (mode == CompletionMode.MANUAL_ONLY) return false;
        long duration = normalizeDuration(durationMillis);
        if (duration == UNKNOWN_DURATION_MILLIS) {
            return false;
        }
        long position = normalizePosition(positionMillis, duration);
        if (mode == CompletionMode.AT_RECORDED_END) return position == duration;
        if (position < SecurityLimits.PLAYBACK_COMPLETION_MINIMUM_POSITION_MILLIS) {
            return false;
        }
        long remaining = duration - position;
        return remaining <= SecurityLimits.PLAYBACK_COMPLETION_REMAINING_MILLIS
                || position * 100L >= duration * SecurityLimits.PLAYBACK_COMPLETION_PERCENT;
    }
}
