package io.github.jnesew.plainpoddroid.core.playback;
import java.io.IOException;

/** Null speed inherits the default; episode ownership is resolved locally. */
public interface PodcastPlaybackSettingsStore {
    PlaybackSpeedSetting findSpeedForEpisode(String mediaId) throws IOException;
    PlaybackSpeedSetting getPodcastSpeed(long subscriptionId) throws IOException;
    void setPodcastSpeed(long subscriptionId, PlaybackSpeedSetting speed) throws IOException;
}
