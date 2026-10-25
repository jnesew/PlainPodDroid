package io.github.jnesew.plainpoddroid.core.queue;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.catalog.PodcastContext;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded, URL-free episode metadata exposed by the durable queue boundary. */
public final class EpisodeQueueEntry {
    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    private final PodcastContext podcast;
    private final String mediaId;
    private final String title;
    private final String author;
    private final long durationMillis;
    private final long playbackPositionMillis;
    private final boolean played;
    private final boolean downloaded;
    private final boolean stale;
    private final int position;

    public EpisodeQueueEntry(
            String mediaId,
            String title,
            String author,
            long durationMillis,
            long playbackPositionMillis,
            boolean played,
            boolean downloaded,
            boolean stale,
            int position) {
        this(mediaId, title, author, durationMillis, playbackPositionMillis,
                played, downloaded, stale, position, null);
    }

    public EpisodeQueueEntry(
            String mediaId,
            String title,
            String author,
            long durationMillis,
            long playbackPositionMillis,
            boolean played,
            boolean downloaded,
            boolean stale,
            int position,
            PodcastContext podcast) {
        this.podcast = podcast;
        Objects.requireNonNull(mediaId, "mediaId");
        this.title = bounded(title, SecurityLimits.MAX_EPISODE_TITLE_CHARS, "title");
        this.author = bounded(author, SecurityLimits.MAX_AUTHOR_CHARS, "author");
        if (!MEDIA_ID.matcher(mediaId).matches()
                || durationMillis < -1L
                || durationMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || playbackPositionMillis < 0L
                || playbackPositionMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || position < 0
                || position >= SecurityLimits.MAX_QUEUE_ENTRIES) {
            throw new IllegalArgumentException("Invalid queue entry.");
        }
        this.mediaId = mediaId;
        this.durationMillis = durationMillis;
        this.playbackPositionMillis = playbackPositionMillis;
        this.played = played;
        this.downloaded = downloaded;
        this.stale = stale;
        this.position = position;
    }

    /** Null only when older callers lack parent metadata; database projections always supply it. */
    public PodcastContext getPodcast() { return podcast; }

    public String getMediaId() {
        return mediaId;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public long getPlaybackPositionMillis() {
        return playbackPositionMillis;
    }

    public boolean isPlayed() {
        return played;
    }

    public boolean isDownloaded() {
        return downloaded;
    }

    public boolean isStale() {
        return stale;
    }

    public int getPosition() {
        return position;
    }

    private static String bounded(String value, int limit, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > limit) {
            throw new IllegalArgumentException("Invalid " + name + ".");
        }
        return value;
    }
}
