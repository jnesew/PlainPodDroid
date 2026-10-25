package io.github.jnesew.plainpoddroid.core.playback;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.net.URI;
import java.util.Objects;

/** Bounded catalog metadata used only while resolving an opaque playback request. */
public final class RemoteEpisode {
    private final String mediaId;
    private final URI uri;
    private final String mimeType;
    private final String title;
    private final String author;
    private final long playbackPositionMillis;
    private final boolean remoteAllowed;

    public RemoteEpisode(
            String mediaId,
            URI uri,
            String mimeType,
            String title,
            String author,
            long playbackPositionMillis) {
        this(mediaId, uri, mimeType, title, author, playbackPositionMillis, true);
    }

    public RemoteEpisode(
            String mediaId,
            URI uri,
            String mimeType,
            String title,
            String author,
            long playbackPositionMillis,
            boolean remoteAllowed) {
        this.mediaId = requireBounded(mediaId, SecurityLimits.MAX_IDENTIFIER_CHARS);
        this.uri = Objects.requireNonNull(uri, "uri");
        this.mimeType = requireBounded(mimeType, SecurityLimits.MAX_MIME_TYPE_CHARS);
        this.title = requireBounded(title, SecurityLimits.MAX_EPISODE_TITLE_CHARS);
        this.author = requireBounded(author, SecurityLimits.MAX_AUTHOR_CHARS);
        if (playbackPositionMillis < 0L
                || playbackPositionMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS) {
            throw new IllegalArgumentException("Invalid playback position.");
        }
        this.playbackPositionMillis = playbackPositionMillis;
        this.remoteAllowed = remoteAllowed;
    }

    public String getMediaId() {
        return mediaId;
    }

    public URI getUri() {
        return uri;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public long getPlaybackPositionMillis() {
        return playbackPositionMillis;
    }

    public boolean isRemoteAllowed() {
        return remoteAllowed;
    }

    private static String requireBounded(String value, int maximumLength) {
        Objects.requireNonNull(value, "value");
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException("Playback metadata exceeds its limit.");
        }
        return value;
    }
}
