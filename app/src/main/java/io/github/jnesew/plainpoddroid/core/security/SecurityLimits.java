package io.github.jnesew.plainpoddroid.core.security;

public final class SecurityLimits {
    public static final int MAX_URL_CHARS = 4_096;
    public static final int MAX_HOST_CHARS = 253;
    public static final int MAX_REDIRECTS = 5;
    // Audio commonly traverses several publisher/measurement services before its CDN.
    public static final int MAX_MEDIA_REDIRECTS = 12;

    public static final int FEED_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int FEED_RESPONSE_HEADER_TIMEOUT_SECONDS = 15;
    public static final int FEED_READ_TIMEOUT_SECONDS = 20;
    public static final int FEED_CALL_TIMEOUT_SECONDS = 30;
    public static final int MAX_CONCURRENT_NETWORK_CALLS = 4;
    public static final int MAX_CONCURRENT_FEED_CALLS = 2;
    public static final int MAX_CONCURRENT_MEDIA_CALLS = 2;
    public static final int MEDIA_RESPONSE_HEADER_TIMEOUT_SECONDS = 20;
    public static final int MEDIA_READ_TIMEOUT_SECONDS = 30;

    // The wire ceiling also applies when the server returns uncompressed XML.
    public static final long MAX_FEED_COMPRESSED_BYTES = 32L * 1024L * 1024L;
    public static final long MAX_FEED_EXPANDED_BYTES = 32L * 1024L * 1024L;
    public static final int MAX_FEED_EXPANSION_RATIO = 20;
    public static final long FEED_RATIO_GRACE_BYTES = 4L * 1024L;
    public static final int MAX_XML_DEPTH = 64;
    public static final int MAX_XML_ATTRIBUTES = 64;
    public static final int MAX_XML_ATTRIBUTE_CHARS = 4_096;
    public static final int MAX_XML_AGGREGATE_TEXT_CHARS = 24 * 1024 * 1024;
    public static final int MAX_EPISODES_PER_FEED = 10_000;
    public static final int MAX_APPLE_LOOKUP_BYTES = 256 * 1024;
    public static final int MAX_APPLE_LOOKUP_JSON_DEPTH = 16;
    public static final int MAX_APPLE_LOOKUP_JSON_TOKENS = 5_000;
    public static final int MAX_FEED_TITLE_CHARS = 512;
    public static final int MAX_EPISODE_TITLE_CHARS = 1_024;
    public static final int MAX_AUTHOR_CHARS = 256;
    public static final int MAX_IDENTIFIER_CHARS = 2_048;
    public static final int MAX_DESCRIPTION_SOURCE_CHARS = 512 * 1024;
    public static final int MAX_PLAIN_NOTES_CHARS = 128 * 1024;
    public static final int MAX_CATEGORIES_PER_ITEM = 64;
    public static final int MAX_CATEGORY_CHARS = 128;
    public static final int MAX_ENCLOSURES_PER_EPISODE = 8;
    public static final int MAX_STORED_CATEGORY_LIST_CHARS =
            MAX_CATEGORIES_PER_ITEM * (MAX_CATEGORY_CHARS + 1);
    public static final int MAX_MIME_TYPE_CHARS = 127;

    public static final long MAX_OPML_BYTES = 2L * 1024L * 1024L;
    public static final int MAX_OPML_DEPTH = 16;
    public static final int MAX_OPML_OUTLINES = 10_000;
    public static final int MAX_OPML_CANDIDATES = 5_000;
    public static final int MAX_OPML_LABEL_CHARS = 512;
    public static final int MAX_SUBSCRIPTIONS = 1_000;
    public static final int MAX_EPISODES_PER_SUBSCRIPTION = 10_000;
    public static final int MAX_GLOBAL_EPISODES = 100_000;
    public static final int CATALOG_BROWSE_PAGE_SIZE = 50;
    public static final int MAX_CATALOG_BROWSE_PAGE_SIZE = 200;
    public static final int CATALOG_BROWSE_VISIBLE_EPISODE_LIMIT = 200;
    public static final int MAX_ETAG_CHARS = 512;
    public static final int MAX_LAST_MODIFIED_CHARS = 512;
    public static final int MAX_REDACTED_ERROR_CHARS = 1_024;
    public static final int MAX_QUEUE_ENTRIES = 2_000;
    public static final int QUEUE_BROWSE_PAGE_SIZE = 25;
    public static final int MAX_PENDING_SUBSCRIPTION_PREVIEWS = 8;
    public static final long SUBSCRIPTION_PREVIEW_TTL_MILLIS = 10L * 60L * 1_000L;
    public static final long MIN_REFRESH_INTERVAL_MILLIS = 15L * 60L * 1_000L;
    public static final long MAX_REFRESH_BACKOFF_MILLIS = 24L * 60L * 60L * 1_000L;
    public static final int MAX_CONSECUTIVE_REFRESH_FAILURES = 31;

    public static final long MAX_ARTWORK_BYTES = 20L * 1024L * 1024L;
    public static final int MAX_ARTWORK_DIMENSION = 4_096;
    public static final long MAX_ARTWORK_PIXELS = 16_000_000L;

    public static final long MAX_CHAPTER_BYTES = 512L * 1024L;
    public static final int MAX_CHAPTERS = 1_000;
    public static final int MAX_VISIBLE_CHAPTERS = 200;
    public static final int MAX_CHAPTER_TITLE_CHARS = 256;
    public static final int MAX_CHAPTER_VERSION_CHARS = 32;
    public static final int MAX_CHAPTER_JSON_DEPTH = 16;
    public static final int MAX_CHAPTER_JSON_TOKENS = 20_000;
    public static final int MAX_CHAPTER_JSON_KEY_CHARS = 128;
    public static final int MAX_CHAPTER_JSON_NUMBER_CHARS = 64;

    public static final long MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final long MIN_DOWNLOAD_QUOTA_BYTES = 512L * 1024L * 1024L;
    public static final long DEFAULT_DOWNLOAD_QUOTA_BYTES =
            2L * 1024L * 1024L * 1024L;
    public static final long MAX_DOWNLOAD_QUOTA_BYTES = 20L * 1024L * 1024L * 1024L;
    public static final long MAX_DOWNLOAD_ACTIVE_MILLIS = 24L * 60L * 60L * 1_000L;
    public static final long DOWNLOAD_TEMP_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L;
    public static final int MAX_PENDING_MANUAL_DOWNLOADS = 100;
    public static final int MAX_AUTOMATIC_DOWNLOADS_PER_JOB = 3;
    public static final int MAX_PREPARATION_DOWNLOADS = 24;
    public static final int MAX_PREPARATION_COVERS = 8;
    public static final int MAX_AUTOMATIC_DOWNLOAD_ATTEMPTS = 3;
    public static final int MAX_DOWNLOAD_DIRECTORY_ENTRIES =
            MAX_GLOBAL_EPISODES * 4;
    public static final long MAX_MEDIA_DURATION_MILLIS = 48L * 60L * 60L * 1_000L;
    public static final long PLAYBACK_PROGRESS_WRITE_INTERVAL_MILLIS = 15_000L;
    public static final long PLAYBACK_COMPLETION_MINIMUM_POSITION_MILLIS =
            5L * 60L * 1_000L;
    public static final long PLAYBACK_COMPLETION_REMAINING_MILLIS = 2L * 60L * 1_000L;
    public static final int PLAYBACK_COMPLETION_PERCENT = 95;
    public static final int MIN_PLAYBACK_SKIP_SECONDS = 5;
    public static final int MAX_PLAYBACK_SKIP_SECONDS = 60;
    public static final int MIN_PLAYBACK_SPEED_PERCENT = 50;
    public static final int MAX_PLAYBACK_SPEED_PERCENT = 300;
    public static final int MIN_SLEEP_TIMER_MINUTES = 5;
    public static final int MAX_SLEEP_TIMER_MINUTES = 60;
    public static final int MAX_NOTIFICATION_TITLE_CHARS = 256;
    public static final int MAX_NOTIFICATION_SUBTITLE_CHARS = 512;

    private SecurityLimits() {
        throw new AssertionError("No instances");
    }
}
