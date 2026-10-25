package io.github.jnesew.plainpoddroid.core.playback;

import java.net.URI;
import java.util.Objects;

public final class PlaybackSource {
    public enum Kind {
        VERIFIED_LOCAL,
        DOWNLOAD_THEN_LOCAL,
        REMOTE_HTTPS
    }

    private final String mediaId;
    private final URI uri;
    private final Kind kind;
    private final String mimeType;
    private final String title;
    private final String author;
    private final long playbackPositionMillis;

    public PlaybackSource(
            String mediaId,
            URI uri,
            Kind kind,
            String mimeType,
            String title,
            String author,
            long playbackPositionMillis) {
        this.mediaId = Objects.requireNonNull(mediaId, "mediaId");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.title = Objects.requireNonNull(title, "title");
        this.author = Objects.requireNonNull(author, "author");
        this.playbackPositionMillis = playbackPositionMillis;
    }

    public String getMediaId() {
        return mediaId;
    }

    public URI getUri() {
        return uri;
    }

    public Kind getKind() {
        return kind;
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
}
