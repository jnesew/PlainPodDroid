package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import java.util.Objects;

/** Explicit UI playback commands. Navigation is deliberately outside this boundary. */
public final class PlaybackActions {
    public enum StartResult { UNAVAILABLE, DOWNLOAD_REQUIRED, STARTED, FAILED }

    public interface Port {
        boolean isConnected();
        boolean hasCurrentItem();
        boolean isPlaying();
        boolean isIdle();
        boolean canSkipBack();
        boolean canSkipForward();
        boolean canSeekCurrent();
        String currentMediaId();
        long durationMillis();
        void setEpisode(String opaqueMediaId, long startPositionMillis);
        void prepare();
        void play();
        void pause();
        void stop();
        void skipBack();
        void skipForward();
        void seekTo(long positionMillis);
    }

    private final Port port;

    public PlaybackActions(Port port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public StartResult playEpisode(EpisodeDetails episode, boolean competingWork,
            boolean neverStream) {
        return start(episode, episode == null || episode.isPlayed()
                ? 0L : episode.getPlaybackPositionMillis(), competingWork, neverStream);
    }

    public StartResult playChapter(EpisodeDetails episode, long startMillis,
            boolean competingWork, boolean neverStream) {
        if (episode == null || startMillis < 0L
                || startMillis > io.github.jnesew.plainpoddroid.core.security.SecurityLimits
                        .MAX_MEDIA_DURATION_MILLIS
                || (episode.getDurationMillis() >= 0L
                        && startMillis >= episode.getDurationMillis())) {
            return StartResult.UNAVAILABLE;
        }
        return start(episode, startMillis, competingWork, neverStream);
    }

    private StartResult start(EpisodeDetails episode, long position,
            boolean competingWork, boolean neverStream) {
        if (episode == null || !port.isConnected() || competingWork) {
            return StartResult.UNAVAILABLE;
        }
        if (neverStream && !episode.isDownloaded()) return StartResult.DOWNLOAD_REQUIRED;
        try {
            port.setEpisode(episode.getMediaId(), position);
            port.prepare();
            port.play();
            return StartResult.STARTED;
        } catch (RuntimeException exception) {
            return StartResult.FAILED;
        }
    }

    public void toggle() {
        if (!port.isConnected() || !port.hasCurrentItem()) return;
        if (port.isPlaying()) port.pause();
        else {
            if (port.isIdle()) port.prepare();
            port.play();
        }
    }

    public void stop() {
        if (port.isConnected() && port.hasCurrentItem()) port.stop();
    }

    public void skipBack() {
        if (port.isConnected() && port.hasCurrentItem() && port.canSkipBack()) port.skipBack();
    }

    public void skipForward() {
        if (port.isConnected() && port.hasCurrentItem() && port.canSkipForward()) {
            port.skipForward();
        }
    }

    /** An absolute seek never selects another episode or starts playback. */
    public boolean seekCurrent(String selectedMediaId, long positionMillis) {
        if (selectedMediaId == null || !selectedMediaId.matches("episode-[0-9a-f]{64}")
                || !port.isConnected() || !port.hasCurrentItem() || !port.canSeekCurrent()
                || !selectedMediaId.equals(port.currentMediaId())) return false;
        long duration = port.durationMillis();
        if (duration <= 0 || duration > io.github.jnesew.plainpoddroid.core.security.SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || positionMillis < 0 || positionMillis > duration) return false;
        try { port.seekTo(positionMillis); return true; }
        catch (RuntimeException ignored) { return false; }
    }
}
