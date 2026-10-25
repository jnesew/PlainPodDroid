package io.github.jnesew.plainpoddroid.core.playback;

import java.util.Objects;

/** Immutable transient sleep-timer state based only on monotonic elapsed time. */
public final class SleepTimerState {
    public static final long NO_DEADLINE_MILLIS = -1L;

    private static final SleepTimerState INACTIVE =
            new SleepTimerState(null, NO_DEADLINE_MILLIS);

    private final SleepTimerOption option;
    private final long deadlineElapsedRealtimeMillis;

    private SleepTimerState(
            SleepTimerOption option,
            long deadlineElapsedRealtimeMillis) {
        this.option = option;
        this.deadlineElapsedRealtimeMillis = deadlineElapsedRealtimeMillis;
    }

    public static SleepTimerState inactive() {
        return INACTIVE;
    }

    public static SleepTimerState arm(
            SleepTimerOption option,
            long elapsedRealtimeMillis) {
        Objects.requireNonNull(option, "option");
        if (elapsedRealtimeMillis < 0L) {
            throw new IllegalArgumentException("Elapsed realtime must not be negative.");
        }
        if (option.isEndOfEpisode()) {
            return new SleepTimerState(option, NO_DEADLINE_MILLIS);
        }
        long durationMillis = option.getDurationMillis();
        if (elapsedRealtimeMillis > Long.MAX_VALUE - durationMillis) {
            throw new IllegalArgumentException("Sleep-timer deadline overflow.");
        }
        return new SleepTimerState(option, elapsedRealtimeMillis + durationMillis);
    }

    public boolean isActive() {
        return option != null;
    }

    public boolean isEndOfEpisode() {
        return option == SleepTimerOption.END_OF_EPISODE;
    }

    public SleepTimerOption getOption() {
        if (option == null) {
            throw new IllegalStateException("Inactive sleep timer has no option.");
        }
        return option;
    }

    public long getDeadlineElapsedRealtimeMillis() {
        return deadlineElapsedRealtimeMillis;
    }

    public long remainingMillis(long elapsedRealtimeMillis) {
        if (elapsedRealtimeMillis < 0L) {
            throw new IllegalArgumentException("Elapsed realtime must not be negative.");
        }
        if (!isActive() || isEndOfEpisode()) {
            return NO_DEADLINE_MILLIS;
        }
        return Math.max(0L, deadlineElapsedRealtimeMillis - elapsedRealtimeMillis);
    }

    public boolean isExpired(long elapsedRealtimeMillis) {
        return isActive()
                && !isEndOfEpisode()
                && remainingMillis(elapsedRealtimeMillis) == 0L;
    }
}
