package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.download.DownloadLookup;
import io.github.jnesew.plainpoddroid.core.download.VerifiedDownloadStore;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueEntry;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueStore;
import java.io.IOException;

/** Read-only local status: no acquisition, playback command or durable autoplay intent. */
public final class PlaybackQueueStatusReader {
    public enum Availability { EMPTY, DOWNLOADED, DOWNLOAD_FIRST, STREAM, OFFLINE_BLOCKED, UNAVAILABLE }
    public record Snapshot(int remaining, String nextTitle, Availability availability, boolean preparing) { }
    private final EpisodeQueueStore queue;
    private final VerifiedDownloadStore downloads;
    private final PlaybackPrivacyPolicy privacy;

    public PlaybackQueueStatusReader(EpisodeQueueStore queue, VerifiedDownloadStore downloads,
            PlaybackPrivacyPolicy privacy) {
        this.queue = queue; this.downloads = downloads; this.privacy = privacy;
    }

    public Snapshot read(String currentId, boolean buffering) throws IOException {
        EpisodeQueueEntry head = null;
        int remaining = 0;
        for (var entry : queue.listQueue()) {
            if (entry.getMediaId().equals(currentId)) continue;
            remaining++;
            if (head == null) head = entry;
        }
        boolean offline = privacy.isNeverStreamAudioEnabled();
        boolean downloadFirst = privacy.isDownloadBeforePlayingEnabled();
        boolean preparing = buffering && !currentId.isEmpty() && !offline && downloadFirst
                && downloads.lookup(currentId).getState() != DownloadLookup.State.COMPLETE;
        if (head == null) return new Snapshot(remaining, "", Availability.EMPTY, preparing);
        boolean complete = downloads.lookup(head.getMediaId()).getState() == DownloadLookup.State.COMPLETE;
        Availability availability = complete ? Availability.DOWNLOADED : offline ? Availability.OFFLINE_BLOCKED
                : head.getPodcast() != null && !head.getPodcast().subscribed() ? Availability.UNAVAILABLE
                : downloadFirst ? Availability.DOWNLOAD_FIRST : Availability.STREAM;
        return new Snapshot(remaining, head.getTitle(), availability, preparing);
    }
}
