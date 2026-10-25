package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

/** Allowlisted transient sleep-timer choices exposed to the application UI. */
public enum SleepTimerOption {
    MINUTES_5(5),
    MINUTES_10(10),
    MINUTES_15(15),
    MINUTES_30(30),
    MINUTES_45(45),
    MINUTES_60(60),
    END_OF_EPISODE(-1);

    public static final int END_OF_EPISODE_COMMAND_VALUE = -1;

    private final int minutes;

    SleepTimerOption(int minutes) {
        if (minutes != END_OF_EPISODE_COMMAND_VALUE
                && (minutes < SecurityLimits.MIN_SLEEP_TIMER_MINUTES
                || minutes > SecurityLimits.MAX_SLEEP_TIMER_MINUTES)) {
            throw new IllegalArgumentException("Sleep timer is outside its bound.");
        }
        this.minutes = minutes;
    }

    public boolean isEndOfEpisode() {
        return this == END_OF_EPISODE;
    }

    public int getCommandValue() {
        return minutes;
    }

    public long getDurationMillis() {
        if (isEndOfEpisode()) {
            throw new IllegalStateException("End-of-episode timer has no fixed duration.");
        }
        return minutes * 60L * 1_000L;
    }

    public static SleepTimerOption fromCommandValue(int commandValue) {
        for (SleepTimerOption option : values()) {
            if (option.minutes == commandValue) {
                return option;
            }
        }
        throw new IllegalArgumentException("Unsupported sleep-timer option.");
    }
}
