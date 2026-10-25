package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

/** Allowlisted playback speeds stored as exact integer percentages. */
public enum PlaybackSpeedSetting {
    HALF(50),
    THREE_QUARTERS(75),
    NORMAL(100),
    ONE_AND_QUARTER(125),
    ONE_AND_HALF(150),
    ONE_AND_THREE_QUARTERS(175),
    DOUBLE(200),
    TWO_AND_HALF(250),
    TRIPLE(300);

    public static final PlaybackSpeedSetting DEFAULT = NORMAL;

    private final int percent;

    PlaybackSpeedSetting(int percent) {
        if (percent < SecurityLimits.MIN_PLAYBACK_SPEED_PERCENT
                || percent > SecurityLimits.MAX_PLAYBACK_SPEED_PERCENT) {
            throw new IllegalArgumentException("Playback speed is outside its bound.");
        }
        this.percent = percent;
    }

    public int getPercent() {
        return percent;
    }

    public float getMultiplier() {
        return percent / 100.0f;
    }

    public static PlaybackSpeedSetting fromPercent(int percent) {
        for (PlaybackSpeedSetting speed : values()) {
            if (speed.percent == percent) {
                return speed;
            }
        }
        throw new IllegalArgumentException("Unsupported playback speed.");
    }
}
