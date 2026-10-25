package io.github.jnesew.plainpoddroid.core.playback;

import java.io.IOException;

/** App-private persistence boundary for bounded playback progress updates. */
public interface PlaybackProgressStore {
    void savePlaybackProgress(PlaybackProgressUpdate update) throws IOException;
    default void recordListening(String mediaId, long listenedAtMillis) throws IOException { }
}
