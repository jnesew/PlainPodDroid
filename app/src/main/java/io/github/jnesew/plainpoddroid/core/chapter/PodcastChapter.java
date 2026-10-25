package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.Objects;

/** Validated, inert Podcasting 2.0 chapter metadata. */
public final class PodcastChapter {
    public static final long UNKNOWN_END_TIME_MILLIS = -1L;

    private final long startTimeMillis;
    private final long endTimeMillis;
    private final String title;
    private final CanonicalHttpsUrl imageUrl;
    private final CanonicalHttpsUrl websiteUrl;
    private final boolean visible;

    PodcastChapter(
            long startTimeMillis,
            long endTimeMillis,
            String title,
            CanonicalHttpsUrl imageUrl,
            CanonicalHttpsUrl websiteUrl,
            boolean visible) {
        Objects.requireNonNull(title, "title");
        if (startTimeMillis < 0L
                || startTimeMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || endTimeMillis < UNKNOWN_END_TIME_MILLIS
                || endTimeMillis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS
                || endTimeMillis != UNKNOWN_END_TIME_MILLIS
                && endTimeMillis < startTimeMillis
                || title.length() > SecurityLimits.MAX_CHAPTER_TITLE_CHARS) {
            throw new IllegalArgumentException("Invalid podcast chapter.");
        }
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.title = title;
        this.imageUrl = imageUrl;
        this.websiteUrl = websiteUrl;
        this.visible = visible;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public String getTitle() {
        return title;
    }

    public CanonicalHttpsUrl getImageUrl() {
        return imageUrl;
    }

    public CanonicalHttpsUrl getWebsiteUrl() {
        return websiteUrl;
    }

    public boolean isVisible() {
        return visible;
    }
}
