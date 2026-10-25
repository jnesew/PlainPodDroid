package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

/** Allowlisted seek increments exposed by playback settings. */
public enum PlaybackSkipInterval {
    SECONDS_5(5),
    SECONDS_10(10),
    SECONDS_15(15),
    SECONDS_30(30),
    SECONDS_45(45),
    SECONDS_60(60);

    public static final PlaybackSkipInterval DEFAULT_BACK = SECONDS_10;
    public static final PlaybackSkipInterval DEFAULT_FORWARD = SECONDS_30;

    private final int seconds;

    PlaybackSkipInterval(int seconds) {
        if (seconds < SecurityLimits.MIN_PLAYBACK_SKIP_SECONDS
                || seconds > SecurityLimits.MAX_PLAYBACK_SKIP_SECONDS) {
            throw new IllegalArgumentException("Playback skip interval is outside its bound.");
        }
        this.seconds = seconds;
    }

    public int getSeconds() {
        return seconds;
    }

    public long getMillis() {
        return seconds * 1_000L;
    }

    public static PlaybackSkipInterval fromSeconds(int seconds) {
        for (PlaybackSkipInterval interval : values()) {
            if (interval.seconds == seconds) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unsupported playback skip interval.");
    }
}
