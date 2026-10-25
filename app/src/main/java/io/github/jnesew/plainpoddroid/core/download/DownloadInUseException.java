package io.github.jnesew.plainpoddroid.core.download;

import java.io.IOException;

/** A reader/writer lease is a temporary use conflict, not a storage failure. */
public final class DownloadInUseException extends IOException {
    private static final long serialVersionUID = 1L;
    private final boolean playback;
    DownloadInUseException(boolean playback) {
        super(playback ? "Download is in use by playback" : "Download is being written");
        this.playback = playback;
    }
    public boolean isPlayback() { return playback; }
}
