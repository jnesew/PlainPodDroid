package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.Objects;
import java.util.regex.Pattern;

public final class CatalogEpisodeSummary {
    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    private final String published;
    private final PodcastContext podcast;
    private final String mediaId;
    private final String title;
    private final String author;
    private final long durationMillis;
    private final long playbackPositionMillis;
    private final boolean played;
    private final boolean favorite;
    private final boolean queued;
    private final boolean downloaded;
    private final boolean stale;

    public CatalogEpisodeSummary(
            String mediaId,
            String title,
            String author,
            long durationMillis,
            long playbackPositionMillis,
            boolean played,
            boolean favorite,
            boolean queued,
            boolean downloaded,
            boolean stale) {
        this(mediaId, title, author, durationMillis, playbackPositionMillis,
                played, favorite, queued, downloaded, stale, "", null);
    }

    public CatalogEpisodeSummary(
            String mediaId,
            String title,
            String author,
            long durationMillis,
            long playbackPositionMillis,
            boolean played,
            boolean favorite,
            boolean queued,
            boolean downloaded,
            boolean stale,
            String published,
            PodcastContext podcast) {
        this.podcast = podcast;
        this.published = bounded(published, SecurityLimits.MAX_IDENTIFIER_CHARS, "published");
        Objects.requireNonNull(mediaId, "mediaId");
        this.title = bounded(title, SecurityLimits.MAX_EPISODE_TITLE_CHARS, "title");
        this.author = bounded(author, SecurityLimits.MAX_AUTHOR_CHARS, "author");
        if (!MEDIA_ID.matcher(mediaId).matches()
                || durationMillis < -1L
                || durationMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || playbackPositionMillis < 0L
                || playbackPositionMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS) {
            throw new IllegalArgumentException("Invalid episode summary.");
        }
        this.mediaId = mediaId;
        this.durationMillis = durationMillis;
        this.playbackPositionMillis = playbackPositionMillis;
        this.played = played;
        this.favorite = favorite;
        this.queued = queued;
        this.downloaded = downloaded;
        this.stale = stale;
    }

    /** Null only when older callers lack parent metadata; database projections always supply it. */
    public PodcastContext getPodcast() { return podcast; }

    public String getPublished() { return published; }

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

    public boolean isFavorite() {
        return favorite;
    }

    public boolean isQueued() {
        return queued;
    }

    public boolean isDownloaded() {
        return downloaded;
    }

    public boolean isStale() {
        return stale;
    }

    private static String bounded(String value, int limit, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > limit) {
            throw new IllegalArgumentException("Invalid " + name + ".");
        }
        return value;
    }
}
