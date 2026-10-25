package io.github.jnesew.plainpoddroid.core.catalog;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CatalogEpisode {
    public static final long UNKNOWN_DECLARED_LENGTH = -1L;
    public static final long UNKNOWN_DURATION_MILLIS = -1L;

    private final String mediaId;
    private final CanonicalHttpsUrl audioUrl;
    private final String mimeType;
    private final long declaredLength;
    private final String title;
    private final String author;
    private final String notes;
    private final CanonicalHttpsUrl websiteUrl;
    private final CanonicalHttpsUrl chapterUrl;
    private final String published;
    private final long durationMillis;
    private final List<String> categories;
    private final int sourceOrder;

    CatalogEpisode(
            String mediaId,
            CanonicalHttpsUrl audioUrl,
            String mimeType,
            long declaredLength,
            String title,
            String author,
            String notes,
            CanonicalHttpsUrl websiteUrl,
            CanonicalHttpsUrl chapterUrl,
            String published,
            long durationMillis,
            List<String> categories,
            int sourceOrder) {
        this.mediaId = Objects.requireNonNull(mediaId, "mediaId");
        this.audioUrl = Objects.requireNonNull(audioUrl, "audioUrl");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.declaredLength = declaredLength;
        this.title = Objects.requireNonNull(title, "title");
        this.author = Objects.requireNonNull(author, "author");
        this.notes = Objects.requireNonNull(notes, "notes");
        this.websiteUrl = websiteUrl;
        this.chapterUrl = chapterUrl;
        this.published = Objects.requireNonNull(published, "published");
        this.durationMillis = durationMillis;
        this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        this.sourceOrder = sourceOrder;
    }

    public String getMediaId() {
        return mediaId;
    }

    public CanonicalHttpsUrl getAudioUrl() {
        return audioUrl;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getDeclaredLength() {
        return declaredLength;
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

    public CanonicalHttpsUrl getChapterUrl() {
        return chapterUrl;
    }

    public String getPublished() {
        return published;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public List<String> getCategories() {
        return categories;
    }

    public int getSourceOrder() {
        return sourceOrder;
    }
}
