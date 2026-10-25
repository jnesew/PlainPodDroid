package io.github.jnesew.plainpoddroid.playback;

import android.os.Bundle;

import androidx.media3.session.SessionCommand;

import io.github.jnesew.plainpoddroid.core.playback.SleepTimerOption;
import io.github.jnesew.plainpoddroid.core.playback.SleepTimerState;

/** App-private Media3 command and result encoding for the transient sleep timer. */
public final class PlaybackSleepTimerCommands {
    private static final String PREFIX =
            "io.github.jnesew.plainpoddroid.playback.sleep_timer.";
    private static final String ACTION_ARM = PREFIX + "ARM";
    private static final String ACTION_CANCEL = PREFIX + "CANCEL";
    private static final String ACTION_GET_STATUS = PREFIX + "GET_STATUS";
    private static final String ACTION_STATUS_CHANGED = PREFIX + "STATUS_CHANGED";
    private static final String EXTRA_OPTION = PREFIX + "OPTION";
    private static final String EXTRA_ACTIVE = PREFIX + "ACTIVE";
    private static final String EXTRA_REMAINING_MILLIS = PREFIX + "REMAINING_MILLIS";

    private PlaybackSleepTimerCommands() {
        throw new AssertionError("No instances");
    }

    public static SessionCommand armCommand() {
        return new SessionCommand(ACTION_ARM, Bundle.EMPTY);
    }

    public static SessionCommand cancelCommand() {
        return new SessionCommand(ACTION_CANCEL, Bundle.EMPTY);
    }

    public static SessionCommand getStatusCommand() {
        return new SessionCommand(ACTION_GET_STATUS, Bundle.EMPTY);
    }

    public static SessionCommand statusChangedCommand() {
        return new SessionCommand(ACTION_STATUS_CHANGED, Bundle.EMPTY);
    }

    public static boolean isArmCommand(SessionCommand command) {
        return ACTION_ARM.equals(command.customAction);
    }

    public static boolean isCancelCommand(SessionCommand command) {
        return ACTION_CANCEL.equals(command.customAction);
    }

    public static boolean isGetStatusCommand(SessionCommand command) {
        return ACTION_GET_STATUS.equals(command.customAction);
    }

    public static boolean isStatusChangedCommand(SessionCommand command) {
        return ACTION_STATUS_CHANGED.equals(command.customAction);
    }

    public static Bundle armArguments(SleepTimerOption option) {
        Bundle extras = new Bundle();
        extras.putInt(EXTRA_OPTION, option.getCommandValue());
        return extras;
    }

    static SleepTimerOption readArmOption(Bundle extras) {
        if (!extras.containsKey(EXTRA_OPTION)) {
            throw new IllegalArgumentException("Sleep-timer option is required.");
        }
        return SleepTimerOption.fromCommandValue(extras.getInt(EXTRA_OPTION));
    }

    static Bundle statusExtras(SleepTimerState state, long elapsedRealtimeMillis) {
        Bundle extras = new Bundle();
        extras.putBoolean(EXTRA_ACTIVE, state.isActive());
        if (state.isActive()) {
            extras.putInt(EXTRA_OPTION, state.getOption().getCommandValue());
            extras.putLong(EXTRA_REMAINING_MILLIS,
                    state.remainingMillis(elapsedRealtimeMillis));
        }
        return extras;
    }

    public static Status readStatus(Bundle extras) {
        if (!extras.getBoolean(EXTRA_ACTIVE, false)) {
            return Status.inactive();
        }
        if (!extras.containsKey(EXTRA_OPTION)
                || !extras.containsKey(EXTRA_REMAINING_MILLIS)) {
            throw new IllegalArgumentException("Incomplete sleep-timer status.");
        }
        SleepTimerOption option = SleepTimerOption.fromCommandValue(
                extras.getInt(EXTRA_OPTION));
        long remainingMillis = extras.getLong(EXTRA_REMAINING_MILLIS);
        if (option.isEndOfEpisode()) {
            if (remainingMillis != SleepTimerState.NO_DEADLINE_MILLIS) {
                throw new IllegalArgumentException("Invalid end-of-episode status.");
            }
        } else if (remainingMillis < 0L
                || remainingMillis > option.getDurationMillis()) {
            throw new IllegalArgumentException("Invalid sleep-timer duration status.");
        }
        return new Status(option, remainingMillis);
    }

    public static final class Status {
        private static final Status INACTIVE = new Status(null, 0L);

        private final SleepTimerOption option;
        private final long remainingMillis;

        private Status(SleepTimerOption option, long remainingMillis) {
            this.option = option;
            this.remainingMillis = remainingMillis;
        }

        private static Status inactive() {
            return INACTIVE;
        }

        public boolean isActive() {
            return option != null;
        }

        public SleepTimerOption getOption() {
            if (option == null) {
                throw new IllegalStateException("Inactive sleep timer has no option.");
            }
            return option;
        }

        public long getRemainingMillis() {
            return remainingMillis;
        }
    }
}
