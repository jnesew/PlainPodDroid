package io.github.jnesew.plainpoddroid.core.playback;

public interface PlaybackPrivacyPolicy {
    boolean isNeverStreamAudioEnabled();

    default boolean isDownloadBeforePlayingEnabled() { return false; }
}
