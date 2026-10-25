package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable validated Podcasting 2.0 JSON chapter document. */
public final class PodcastChapterDocument {
    private final String version;
    private final List<PodcastChapter> chapters;

    PodcastChapterDocument(String version, List<PodcastChapter> chapters) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(chapters, "chapters");
        if (version.isEmpty()
                || version.length() > SecurityLimits.MAX_CHAPTER_VERSION_CHARS
                || chapters.size() > SecurityLimits.MAX_CHAPTERS) {
            throw new IllegalArgumentException("Invalid chapter document.");
        }
        this.version = version;
        this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
    }

    public String getVersion() {
        return version;
    }

    public List<PodcastChapter> getChapters() {
        return chapters;
    }
}
