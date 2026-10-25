package io.github.jnesew.plainpoddroid.core.refresh;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

public enum RefreshInterval {
    OFF(0),
    ONE_HOUR(1),
    THREE_HOURS(3),
    SIX_HOURS(6),
    TWELVE_HOURS(12),
    TWENTY_FOUR_HOURS(24);

    public static final RefreshInterval DEFAULT = SIX_HOURS;

    private final int hours;
    private final long intervalMillis;

    RefreshInterval(int hours) {
        this.hours = hours;
        this.intervalMillis = hours * 60L * 60L * 1_000L;
        if (hours != 0 && intervalMillis < SecurityLimits.MIN_REFRESH_INTERVAL_MILLIS) {
            throw new AssertionError("Refresh interval violates the security floor.");
        }
    }

    public int getHours() {
        return hours;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public static RefreshInterval fromHours(int hours) {
        for (RefreshInterval interval : values()) {
            if (interval.hours == hours) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unsupported refresh interval.");
    }
}
