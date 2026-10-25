package io.github.jnesew.plainpoddroid.core.feed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FeedDocument {
    public enum Format {
        RSS,
        ATOM
    }

    private final Format format;
    private final String title;
    private final String author;
    private final String notes;
    private final String websiteUrl;
    private final String artworkUrl;
    private final List<String> categories;
    private final List<Episode> episodes;

    FeedDocument(
            Format format,
            String title,
            String author,
            String notes,
            String websiteUrl,
            String artworkUrl,
            List<String> categories,
            List<Episode> episodes) {
        this.format = format;
        this.title = emptyIfNull(title);
        this.author = emptyIfNull(author);
        this.notes = emptyIfNull(notes);
        this.websiteUrl = emptyIfNull(websiteUrl);
        this.artworkUrl = emptyIfNull(artworkUrl);
        this.categories = immutableCopy(categories);
        this.episodes = immutableCopy(episodes);
    }

    public Format getFormat() {
        return format;
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

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public String getArtworkUrl() {
        return artworkUrl;
    }

    public List<String> getCategories() {
        return categories;
    }

    public List<Episode> getEpisodes() {
        return episodes;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public static final class Episode {
        private final String title;
        private final String identifier;
        private final String author;
        private final String notes;
        private final String websiteUrl;
        private final String published;
        private final String duration;
        private final String chapterUrl;
        private final String chapterMimeType;
        private final List<String> categories;
        private final List<Enclosure> enclosures;

        Episode(
                String title,
                String identifier,
                String author,
                String notes,
                String websiteUrl,
                String published,
                String duration,
                String chapterUrl,
                String chapterMimeType,
                List<String> categories,
                List<Enclosure> enclosures) {
            this.title = emptyIfNull(title);
            this.identifier = emptyIfNull(identifier);
            this.author = emptyIfNull(author);
            this.notes = emptyIfNull(notes);
            this.websiteUrl = emptyIfNull(websiteUrl);
            this.published = emptyIfNull(published);
            this.duration = emptyIfNull(duration);
            this.chapterUrl = emptyIfNull(chapterUrl);
            this.chapterMimeType = emptyIfNull(chapterMimeType);
            this.categories = immutableCopy(categories);
            this.enclosures = immutableCopy(enclosures);
        }

        public String getTitle() {
            return title;
        }

        public String getIdentifier() {
            return identifier;
        }

        public String getAuthor() {
            return author;
        }

        public String getNotes() {
            return notes;
        }

        public String getWebsiteUrl() {
            return websiteUrl;
        }

        public String getPublished() {
            return published;
        }

        public String getDuration() {
            return duration;
        }

        public String getChapterUrl() {
            return chapterUrl;
        }

        public String getChapterMimeType() {
            return chapterMimeType;
        }

        public List<String> getCategories() {
            return categories;
        }

        public List<Enclosure> getEnclosures() {
            return enclosures;
        }
    }

    public static final class Enclosure {
        private final String url;
        private final String mimeType;
        private final long declaredLength;

        Enclosure(String url, String mimeType, long declaredLength) {
            this.url = emptyIfNull(url);
            this.mimeType = emptyIfNull(mimeType);
            this.declaredLength = declaredLength;
        }

        public String getUrl() {
            return url;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getDeclaredLength() {
            return declaredLength;
        }
    }
}
