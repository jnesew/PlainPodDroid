-- Frozen schema version 9 from CatalogSql; migration compatibility baseline.
CREATE TABLE subscriptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    subscription_url TEXT NOT NULL UNIQUE,
    final_feed_url TEXT NOT NULL,
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    notes TEXT NOT NULL,
    website_url TEXT NOT NULL,
    artwork_url TEXT NOT NULL,
    categories TEXT NOT NULL,
    etag TEXT NOT NULL DEFAULT '',
    last_modified TEXT NOT NULL DEFAULT '',
    last_error TEXT NOT NULL DEFAULT '',
    refresh_failure_count INTEGER NOT NULL DEFAULT 0,
    last_refresh_attempt_at INTEGER NOT NULL DEFAULT 0,
    is_subscribed INTEGER NOT NULL DEFAULT 1 CHECK (is_subscribed IN (0, 1)),
    auto_download INTEGER NOT NULL DEFAULT 0 CHECK (auto_download IN (0, 1)),
    generation INTEGER NOT NULL DEFAULT 1 CHECK (generation >= 1),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
    CHECK (length(subscription_url) BETWEEN 1 AND 4096),
    CHECK (length(final_feed_url) BETWEEN 1 AND 4096),
    CHECK (length(title) <= 512),
    CHECK (length(author) <= 256),
    CHECK (length(notes) <= 131072),
    CHECK (length(website_url) <= 4096),
    CHECK (length(artwork_url) <= 4096),
    CHECK (length(categories) <= 8256),
    CHECK (length(etag) <= 512),
    CHECK (length(last_modified) <= 512),
    CHECK (length(last_error) <= 1024),
    CHECK (refresh_failure_count BETWEEN 0 AND 31),
    CHECK (last_refresh_attempt_at >= 0),
    CHECK ((refresh_failure_count = 0 AND last_refresh_attempt_at = 0)
        OR (refresh_failure_count > 0 AND last_refresh_attempt_at > 0))
);
CREATE TABLE episodes (
    media_id TEXT PRIMARY KEY,
    subscription_id INTEGER NOT NULL
        REFERENCES subscriptions(id) ON DELETE CASCADE,
    audio_url TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    declared_length INTEGER NOT NULL,
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    notes TEXT NOT NULL,
    website_url TEXT NOT NULL,
    chapter_url TEXT NOT NULL,
    published TEXT NOT NULL,
    published_at INTEGER NOT NULL DEFAULT 0 CHECK (published_at >= 0),
    duration_ms INTEGER NOT NULL,
    categories TEXT NOT NULL,
    source_order INTEGER NOT NULL,
    last_seen_generation INTEGER NOT NULL CHECK (last_seen_generation >= 1),
    is_stale INTEGER NOT NULL DEFAULT 0 CHECK (is_stale IN (0, 1)),
    playback_position_ms INTEGER NOT NULL DEFAULT 0,
    played INTEGER NOT NULL DEFAULT 0 CHECK (played IN (0, 1)),
    favorite INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
    queued INTEGER NOT NULL DEFAULT 0 CHECK (queued IN (0, 1)),
    downloaded INTEGER NOT NULL DEFAULT 0 CHECK (downloaded IN (0, 1)),
    auto_download_pending INTEGER NOT NULL DEFAULT 0
        CHECK (auto_download_pending IN (0, 1)),
    auto_download_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (auto_download_attempts BETWEEN 0 AND 3),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
    CHECK (length(media_id) = 72),
    CHECK (media_id GLOB 'episode-*'),
    CHECK (substr(media_id, 9) NOT GLOB '*[^0-9a-f]*'),
    CHECK (length(audio_url) BETWEEN 1 AND 4096),
    CHECK (length(mime_type) BETWEEN 1 AND 127),
    CHECK (declared_length BETWEEN -1 AND 2147483648),
    CHECK (length(title) <= 1024),
    CHECK (length(author) <= 256),
    CHECK (length(notes) <= 131072),
    CHECK (length(website_url) <= 4096),
    CHECK (length(chapter_url) <= 4096),
    CHECK (length(published) <= 2048),
    CHECK (duration_ms BETWEEN -1 AND 172800000),
    CHECK (length(categories) <= 8256),
    CHECK (source_order BETWEEN 0 AND 1999),
    CHECK (playback_position_ms BETWEEN 0 AND 172800000)
) WITHOUT ROWID;
CREATE INDEX episodes_by_subscription ON episodes(subscription_id, is_stale, source_order, media_id);
CREATE INDEX episodes_for_pruning ON episodes(is_stale, played, favorite, queued, downloaded, updated_at);
CREATE TABLE episode_queue (
    media_id TEXT PRIMARY KEY
        REFERENCES episodes(media_id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 2000),
    enqueued_at INTEGER NOT NULL CHECK (enqueued_at >= 0)
) WITHOUT ROWID;
CREATE INDEX episode_queue_by_position ON episode_queue(position, media_id);
PRAGMA user_version = 9;
