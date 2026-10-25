package io.github.jnesew.plainpoddroid.core.playback;

import java.util.List;

public final class PlaybackQueuePolicy {
    private PlaybackQueuePolicy() {
        throw new AssertionError("No instances");
    }

    public static boolean mustStopAndClear(
            boolean neverStreamAudioEnabled,
            List<PlaybackSource.Kind> queueKinds) {
        return mustStopAndClear(neverStreamAudioEnabled, false, queueKinds);
    }

    public static boolean mustStopAndClear(boolean offline, boolean downloadBeforePlaying,
            List<PlaybackSource.Kind> queueKinds) {
        if (!offline && !downloadBeforePlaying) {
            return false;
        }
        for (PlaybackSource.Kind kind : queueKinds) {
            if (kind != PlaybackSource.Kind.VERIFIED_LOCAL
                    && (offline || kind != PlaybackSource.Kind.DOWNLOAD_THEN_LOCAL)) {
                return true;
            }
        }
        return false;
    }
}
