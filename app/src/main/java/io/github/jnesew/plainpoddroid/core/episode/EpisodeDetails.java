package io.github.jnesew.plainpoddroid.core.episode;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.catalog.PodcastContext;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded episode details; the optional page URL is never rendered as text. */
public final class EpisodeDetails {
    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    private final PodcastContext podcast;
    private final String mediaId;
    private final String title;
    private final String author;
    private final String notes;
    private final CanonicalHttpsUrl websiteUrl;
    private final String published;
    private final long durationMillis;
    private final long playbackPositionMillis;
    private final boolean played;
    private final boolean favorite;
    private final boolean queued;
    private final boolean downloaded;
    private final boolean stale;
    private final boolean chapterReferenceAvailable;

    public EpisodeDetails(
            String mediaId,
            String title,
            String author,
            String notes,
            CanonicalHttpsUrl websiteUrl,
            String published,
            long durationMillis,
            long playbackPositionMillis,
            boolean played,
            boolean favorite,
            boolean queued,
            boolean downloaded,
            boolean stale,
            boolean chapterReferenceAvailable) {
        this(mediaId, title, author, notes, websiteUrl, published, durationMillis,
                playbackPositionMillis, played, favorite, queued, downloaded, stale,
                chapterReferenceAvailable, null);
    }

    public EpisodeDetails(
            String mediaId,
            String title,
            String author,
            String notes,
            CanonicalHttpsUrl websiteUrl,
            String published,
            long durationMillis,
            long playbackPositionMillis,
            boolean played,
            boolean favorite,
            boolean queued,
            boolean downloaded,
            boolean stale,
            boolean chapterReferenceAvailable,
            PodcastContext podcast) {
        this.podcast = podcast;
        Objects.requireNonNull(mediaId, "mediaId");
        this.title = bounded(title, SecurityLimits.MAX_EPISODE_TITLE_CHARS, "title");
        this.author = bounded(author, SecurityLimits.MAX_AUTHOR_CHARS, "author");
        this.notes = bounded(notes, SecurityLimits.MAX_PLAIN_NOTES_CHARS, "notes");
        this.websiteUrl = websiteUrl;
        this.published = bounded(
                published,
                SecurityLimits.MAX_IDENTIFIER_CHARS,
                "published value");
        if (!MEDIA_ID.matcher(mediaId).matches()
                || durationMillis < -1L
                || durationMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || playbackPositionMillis < 0L
                || playbackPositionMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS) {
            throw new IllegalArgumentException("Invalid episode details.");
        }
        this.mediaId = mediaId;
        this.durationMillis = durationMillis;
        this.playbackPositionMillis = playbackPositionMillis;
        this.played = played;
        this.favorite = favorite;
        this.queued = queued;
        this.downloaded = downloaded;
        this.stale = stale;
        this.chapterReferenceAvailable = chapterReferenceAvailable;
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

    public String getNotes() {
        return notes;
    }

    public CanonicalHttpsUrl getWebsiteUrl() {
        return websiteUrl;
    }

    public String getPublished() {
        return published;
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

    public boolean isChapterReferenceAvailable() {
        return chapterReferenceAvailable;
    }

    public EpisodeDetails withPlayed(boolean nextPlayed) {
        return new EpisodeDetails(
                mediaId,
                title,
                author,
                notes,
                websiteUrl,
                published,
                durationMillis,
                nextPlayed ? playbackPositionMillis : 0L,
                nextPlayed,
                favorite,
                queued,
                downloaded,
                stale,
                chapterReferenceAvailable, podcast);
    }

    public EpisodeDetails withFavorite(boolean nextFavorite) {
        return new EpisodeDetails(
                mediaId,
                title,
                author,
                notes,
                websiteUrl,
                published,
                durationMillis,
                playbackPositionMillis,
                played,
                nextFavorite,
                queued,
                downloaded,
                stale,
                chapterReferenceAvailable, podcast);
    }

    private static String bounded(String value, int limit, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > limit) {
            throw new IllegalArgumentException("Invalid " + name + ".");
        }
        return value;
    }
}
