package io.github.jnesew.plainpoddroid.core.catalog.storage;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CatalogSql {
    public static final String CREATE_MANUAL_DOWNLOADS = """
            CREATE TABLE manual_download_requests (
                request_id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id TEXT NOT NULL UNIQUE REFERENCES episodes(media_id) ON DELETE CASCADE,
                attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0 AND typeof(attempt) = 'integer'),
                state INTEGER NOT NULL DEFAULT 0 CHECK (state IN (0,1,2)),
                failure TEXT CHECK ((state = 2 AND failure IS NOT NULL AND failure IN (
                    'INVALID_SELECTION','EPISODE_NOT_FOUND','ALREADY_ACTIVE','ALREADY_DOWNLOADED',
                    'CANCELLED','FETCH_FAILED','LOGIN_REQUIRED','ACCESS_DENIED','HTTP_STATUS',
                    'INVALID_REDIRECT','TOO_MANY_REDIRECTS','REDIRECT_LOOP','UNSUPPORTED_RESPONSE',
                    'INVALID_LENGTH','QUOTA_EXCEEDED','TARGET_CHANGED','OFFLINE_MODE','PLAYBACK_IN_USE','STORAGE_FAILED'))
                    OR (state != 2 AND failure IS NULL))
            )
            """;
    public static final String CREATE_MANUAL_DOWNLOAD_INDEX =
            "CREATE INDEX manual_download_fifo ON manual_download_requests(state,request_id)";
    public static final String LIST_MANUAL_DOWNLOADS = """
            SELECT r.request_id,r.media_id,r.attempt,r.state,r.failure,e.title,s.title
            FROM manual_download_requests r JOIN episodes e ON e.media_id=r.media_id
            JOIN subscriptions s ON s.id=e.subscription_id ORDER BY r.request_id LIMIT ?
            """;
    public static final String CLAIM_MANUAL_DOWNLOAD = """
            UPDATE manual_download_requests SET state=1,attempt=attempt+1
            WHERE request_id=(SELECT request_id FROM manual_download_requests WHERE state=0 ORDER BY request_id LIMIT 1)
            AND NOT EXISTS (SELECT 1 FROM manual_download_requests WHERE state=1)
            """;
    public static final String RECOVER_MANUAL_DOWNLOADS =
            "UPDATE manual_download_requests SET state=0,failure=NULL WHERE state=1";
    public static final String RUNNABLE_MANUAL_DOWNLOAD = """
            SELECT 1 FROM manual_download_requests r JOIN episodes e ON e.media_id=r.media_id
            JOIN subscriptions s ON s.id=e.subscription_id
            WHERE r.request_id=? AND r.media_id=? AND r.attempt=? AND r.state=1 AND s.is_subscribed=1 LIMIT 1
            """;
    public static final String COMPLETE_MANUAL_DOWNLOAD =
            "DELETE FROM manual_download_requests WHERE request_id=? AND media_id=? AND attempt=? AND state=1";
    public static final String FAIL_MANUAL_DOWNLOAD =
            "UPDATE manual_download_requests SET state=2,failure=? WHERE request_id=? AND media_id=? AND attempt=? AND state=1";
    public static final String DEFER_MANUAL_DOWNLOAD =
            "UPDATE manual_download_requests SET state=0,failure=NULL WHERE request_id=? AND media_id=? AND attempt=? AND state=1";

    private static final String PLAYED_BATCH_SCOPE = "WHERE subscription_id = ? AND played = 0 "
            + "AND first_seen_generation <= ? AND (CAST(? AS INTEGER) = 0 OR (published_at > 0 AND published_at < ?))";
    public static final String COUNT_PODCAST_PLAYED_BATCH = "SELECT COUNT(*) FROM episodes " + PLAYED_BATCH_SCOPE;
    public static final String MARK_PODCAST_PLAYED_BATCH = "UPDATE episodes SET played = 1, updated_at = ? " + PLAYED_BATCH_SCOPE;
    public static final String CREATE_PODCAST_PLAYBACK_SETTINGS = """
            CREATE TABLE podcast_playback_settings (
                subscription_id INTEGER PRIMARY KEY REFERENCES subscriptions(id) ON DELETE CASCADE,
                speed_percent INTEGER NOT NULL CHECK (speed_percent IN (50,75,100,125,150,175,200,250,300))
            )
            """;
    public static final String FIND_EPISODE_SPEED = """
            SELECT p.speed_percent FROM episodes e
            LEFT JOIN podcast_playback_settings p ON p.subscription_id = e.subscription_id
            WHERE e.media_id = ? LIMIT 1
            """;
    public static final String CREATE_AUDIO_OWNER_INDEX =
            "CREATE INDEX episodes_audio_owner ON episodes(audio_url, subscription_id)";
    public static final String CREATE_SUBSCRIPTION_LOGINS = """
            CREATE TABLE subscription_logins (
                subscription_id INTEGER PRIMARY KEY REFERENCES subscriptions(id) ON DELETE CASCADE,
                login_data BLOB NOT NULL CHECK (length(login_data) BETWEEN 29 AND 16384),
                feed_blocked INTEGER NOT NULL DEFAULT 0 CHECK (feed_blocked IN (0, 1)),
                audio_blocked INTEGER NOT NULL DEFAULT 0 CHECK (audio_blocked IN (0, 1))
            )
            """;
    public static final String FIND_FEED_LOGIN = """
            SELECT s.id, s.subscription_url, l.login_data, l.feed_blocked, l.audio_blocked
            FROM subscriptions s LEFT JOIN subscription_logins l ON l.subscription_id = s.id
            WHERE s.is_subscribed = 1 AND s.subscription_url = ? LIMIT 1
            """;
    public static final String FIND_AUDIO_LOGIN = """
            SELECT DISTINCT s.id, s.subscription_url, l.login_data, l.feed_blocked, l.audio_blocked
            FROM episodes e JOIN subscriptions s ON s.id = e.subscription_id
            LEFT JOIN subscription_logins l ON l.subscription_id = s.id
            WHERE s.is_subscribed = 1 AND e.audio_url = ? LIMIT 2
            """;
    public static final String CREATE_SUBSCRIPTIONS = String.format(Locale.ROOT, """
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
                seen_generation INTEGER NOT NULL DEFAULT 0 CHECK (seen_generation >= 0),
                has_episode_baseline INTEGER NOT NULL DEFAULT 0 CHECK (has_episode_baseline IN (0, 1)),
                episode_order INTEGER NOT NULL DEFAULT 0 CHECK (episode_order IN (0, 1)),
                created_at INTEGER NOT NULL CHECK (created_at >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
                CHECK (length(subscription_url) BETWEEN 1 AND %d),
                CHECK (length(final_feed_url) BETWEEN 1 AND %d),
                CHECK (length(title) <= %d),
                CHECK (length(author) <= %d),
                CHECK (length(notes) <= %d),
                CHECK (length(website_url) <= %d),
                CHECK (length(artwork_url) <= %d),
                CHECK (length(categories) <= %d),
                CHECK (length(etag) <= %d),
                CHECK (length(last_modified) <= %d),
                CHECK (length(last_error) <= %d),
                CHECK (refresh_failure_count BETWEEN 0 AND %d),
                CHECK (last_refresh_attempt_at >= 0),
                CHECK ((refresh_failure_count = 0 AND last_refresh_attempt_at = 0)
                    OR (refresh_failure_count > 0 AND last_refresh_attempt_at > 0))
            )
            """,
                    SecurityLimits.MAX_URL_CHARS,
                    SecurityLimits.MAX_URL_CHARS,
                    SecurityLimits.MAX_FEED_TITLE_CHARS,
                    SecurityLimits.MAX_AUTHOR_CHARS,
                    SecurityLimits.MAX_PLAIN_NOTES_CHARS,
                    SecurityLimits.MAX_URL_CHARS,
                    SecurityLimits.MAX_URL_CHARS,
                    SecurityLimits.MAX_STORED_CATEGORY_LIST_CHARS,
                    SecurityLimits.MAX_ETAG_CHARS,
                    SecurityLimits.MAX_LAST_MODIFIED_CHARS,
                    SecurityLimits.MAX_REDACTED_ERROR_CHARS,
                    SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES);

    public static final String MIGRATE_SUBSCRIPTIONS_ADD_REFRESH_FAILURE_COUNT =
            "ALTER TABLE subscriptions ADD COLUMN "
                    + "refresh_failure_count INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (refresh_failure_count BETWEEN 0 AND "
                    + SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES + ")";
    public static final String MIGRATE_SUBSCRIPTIONS_ADD_LAST_REFRESH_ATTEMPT =
            "ALTER TABLE subscriptions ADD COLUMN "
                    + "last_refresh_attempt_at INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (last_refresh_attempt_at >= 0)";
    public static final String MIGRATE_EPISODES_ADD_CHAPTER_URL =
            "ALTER TABLE episodes ADD COLUMN chapter_url TEXT NOT NULL DEFAULT '' "
                    + "CHECK (length(chapter_url) <= " + SecurityLimits.MAX_URL_CHARS + ")";
    public static final String MIGRATE_SUBSCRIPTIONS_ADD_SUBSCRIBED =
            "ALTER TABLE subscriptions ADD COLUMN is_subscribed INTEGER NOT NULL DEFAULT 1 "
                    + "CHECK (is_subscribed IN (0, 1))";
    public static final String MIGRATE_EPISODES_ADD_PUBLISHED_AT =
            "ALTER TABLE episodes ADD COLUMN published_at INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (published_at >= 0)";
    public static final String MIGRATE_SUBSCRIPTIONS_ADD_AUTO_DOWNLOAD =
            "ALTER TABLE subscriptions ADD COLUMN auto_download INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (auto_download IN (0, 1))";
    public static final String MIGRATE_EPISODES_ADD_AUTO_DOWNLOAD_PENDING =
            "ALTER TABLE episodes ADD COLUMN auto_download_pending INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (auto_download_pending IN (0, 1))";
    public static final String MIGRATE_EPISODES_ADD_AUTO_DOWNLOAD_ATTEMPTS =
            "ALTER TABLE episodes ADD COLUMN auto_download_attempts INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (auto_download_attempts BETWEEN 0 AND "
                    + SecurityLimits.MAX_AUTOMATIC_DOWNLOAD_ATTEMPTS + ")";
    public static final String LIST_EPISODES_FOR_DATE_BACKFILL =
            "SELECT media_id, published FROM episodes LIMIT ?";
    public static final String UPDATE_EPISODE_PUBLISHED_AT =
            "UPDATE episodes SET published_at = ? WHERE media_id = ?";

    public static final String CREATE_EPISODES = String.format(Locale.ROOT, """
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
                first_seen_generation INTEGER NOT NULL DEFAULT 1 CHECK (first_seen_generation >= 1),
                is_stale INTEGER NOT NULL DEFAULT 0 CHECK (is_stale IN (0, 1)),
                playback_position_ms INTEGER NOT NULL DEFAULT 0,
                last_listened_at INTEGER NOT NULL DEFAULT 0 CHECK (last_listened_at >= 0),
                played INTEGER NOT NULL DEFAULT 0 CHECK (played IN (0, 1)),
                favorite INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
                queued INTEGER NOT NULL DEFAULT 0 CHECK (queued IN (0, 1)),
                downloaded INTEGER NOT NULL DEFAULT 0 CHECK (downloaded IN (0, 1)),
                download_completed_at INTEGER NOT NULL DEFAULT 0 CHECK (download_completed_at >= 0),
                download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes BETWEEN 0 AND %d),
                auto_download_pending INTEGER NOT NULL DEFAULT 0
                    CHECK (auto_download_pending IN (0, 1)),
                auto_download_attempts INTEGER NOT NULL DEFAULT 0
                    CHECK (auto_download_attempts BETWEEN 0 AND %d),
                created_at INTEGER NOT NULL CHECK (created_at >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
                CHECK (length(media_id) = 72),
                CHECK (media_id GLOB 'episode-*'),
                CHECK (substr(media_id, 9) NOT GLOB '*[^0-9a-f]*'),
                CHECK (length(audio_url) BETWEEN 1 AND %d),
                CHECK (length(mime_type) BETWEEN 1 AND %d),
                CHECK (declared_length BETWEEN -1 AND %d),
                CHECK (length(title) <= %d),
                CHECK (length(author) <= %d),
                CHECK (length(notes) <= %d),
                CHECK (length(website_url) <= %d),
                CHECK (length(chapter_url) <= %d),
                CHECK (length(published) <= %d),
                CHECK (duration_ms BETWEEN -1 AND %d),
                CHECK (length(categories) <= %d),
                CHECK (source_order BETWEEN 0 AND %d),
                CHECK (playback_position_ms BETWEEN 0 AND %d)
            ) WITHOUT ROWID
            """,
                    SecurityLimits.MAX_DOWNLOAD_BYTES,
                    SecurityLimits.MAX_AUTOMATIC_DOWNLOAD_ATTEMPTS,
                    SecurityLimits.MAX_URL_CHARS,
                    SecurityLimits.MAX_MIME_TYPE_CHARS,
                    SecurityLimits.MAX_DOWNLOAD_BYTES,
                    SecurityLimits.MAX_EPISODE_TITLE_CHARS,
                    SecurityLimits.MAX_AUTHOR_CHARS,
                    SecurityLimits.MAX_PLAIN_NOTES_CHARS,
                    SecurityLimits.MAX_URL_CHARS,
                    SecurityLimits.MAX_URL_CHARS,
                    SecurityLimits.MAX_IDENTIFIER_CHARS,
                    SecurityLimits.MAX_MEDIA_DURATION_MILLIS,
                    SecurityLimits.MAX_STORED_CATEGORY_LIST_CHARS,
                    SecurityLimits.MAX_EPISODES_PER_FEED - 1,
                    SecurityLimits.MAX_MEDIA_DURATION_MILLIS);

    public static final String CREATE_EPISODE_SUBSCRIPTION_INDEX =
            "CREATE INDEX episodes_by_subscription "
                    + "ON episodes(subscription_id, is_stale, source_order, media_id)";
    public static final String CREATE_PRUNE_INDEX =
            "CREATE INDEX episodes_for_pruning "
                    + "ON episodes(is_stale, played, favorite, queued, downloaded, updated_at)";

    public static final String CREATE_DOWNLOAD_TIME_INDEX =
            "CREATE INDEX episodes_by_download_time "
                    + "ON episodes(downloaded, download_completed_at DESC, media_id)";
    public static final String MIGRATE_EPISODES_ADD_DOWNLOAD_TIME =
            "ALTER TABLE episodes ADD COLUMN download_completed_at "
                    + "INTEGER NOT NULL DEFAULT 0 CHECK (download_completed_at >= 0)";
    public static final String MIGRATE_EPISODES_ADD_DOWNLOAD_BYTES =
            "ALTER TABLE episodes ADD COLUMN download_bytes INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (download_bytes BETWEEN 0 AND " + SecurityLimits.MAX_DOWNLOAD_BYTES + ")";

    public static final String CREATE_EPISODE_QUEUE = String.format(Locale.ROOT, """
            CREATE TABLE episode_queue (
                media_id TEXT PRIMARY KEY
                    REFERENCES episodes(media_id) ON DELETE CASCADE,
                position INTEGER NOT NULL CHECK (position BETWEEN 0 AND %d),
                enqueued_at INTEGER NOT NULL CHECK (enqueued_at >= 0)
            ) WITHOUT ROWID
            """, SecurityLimits.MAX_QUEUE_ENTRIES);

    public static final String CREATE_EPISODE_QUEUE_POSITION_INDEX =
            "CREATE INDEX episode_queue_by_position "
                    + "ON episode_queue(position, media_id)";

    public static final String LIST_SUBSCRIPTIONS = """
            SELECT s.id, s.title, s.author, s.updated_at,
                   SUM(CASE WHEN e.is_stale = 0 THEN 1 ELSE 0 END) AS episode_count,
                   SUM(CASE WHEN e.downloaded = 1 THEN 1 ELSE 0 END) AS downloaded_count,
                   SUM(CASE WHEN e.is_stale = 0 AND e.played = 0
                       THEN 1 ELSE 0 END) AS unplayed_count,
                   s.is_subscribed, s.refresh_failure_count, s.last_refresh_attempt_at,
                   s.artwork_url, s.auto_download, s.subscription_url,
                   SUM(CASE WHEN e.is_stale = 0 AND e.first_seen_generation > s.seen_generation
                       THEN 1 ELSE 0 END) AS new_count,
                   MAX(CASE WHEN e.is_stale = 0 THEN e.published_at ELSE 0 END) AS latest_episode_at,
                   s.episode_order
            FROM subscriptions AS s
            LEFT JOIN episodes AS e ON e.subscription_id = s.id
            GROUP BY s.id, s.title, s.author, s.updated_at, s.is_subscribed,
                     s.refresh_failure_count, s.last_refresh_attempt_at,
                     s.artwork_url, s.auto_download, s.subscription_url, s.episode_order, s.seen_generation
            ORDER BY latest_episode_at DESC, s.title COLLATE NOCASE ASC, s.id ASC
            LIMIT ?
            """;

    private static final String EPISODE_ROW_COLUMNS = """
                   e.media_id, e.title, e.author, e.duration_ms,
                   e.playback_position_ms, e.played, e.favorite,
                   CASE WHEN q.media_id IS NULL THEN 0 ELSE 1 END AS queued,
                   e.downloaded, e.is_stale, e.published,
                   s.id, s.title, s.artwork_url, s.is_subscribed
            """;
    private static final String EPISODE_ROW_JOINS = """
            FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            LEFT JOIN episode_queue AS q ON q.media_id = e.media_id
            """;
    private static final String LIST_EPISODES_BASE =
            "SELECT " + EPISODE_ROW_COLUMNS + EPISODE_ROW_JOINS + """
            WHERE e.subscription_id = ?
              AND (? = '' OR instr(lower(e.title), lower(?)) > 0)
            """;

    public static final String LIST_DOWNLOADED_EPISODES =
            "SELECT " + EPISODE_ROW_COLUMNS
                    + ", e.download_completed_at, e.download_bytes\n" + EPISODE_ROW_JOINS + """
            WHERE e.downloaded = 1
            ORDER BY e.download_completed_at DESC, e.media_id ASC
            LIMIT ? OFFSET ?
            """;

    public static final String LIST_EPISODES = listEpisodesSql(CatalogBrowseQuery.Filter.ALL);

    public static final String LIST_FAVORITE_EPISODES = "SELECT " + EPISODE_ROW_COLUMNS
            + EPISODE_ROW_JOINS + "WHERE e.favorite = 1 ORDER BY e.updated_at DESC, e.media_id ASC LIMIT ? OFFSET ?";
    public static final String LIST_LISTENING_HISTORY = "SELECT " + EPISODE_ROW_COLUMNS
            + ", e.last_listened_at\n" + EPISODE_ROW_JOINS
            + "WHERE e.last_listened_at > 0 ORDER BY e.last_listened_at DESC, e.media_id ASC LIMIT ? OFFSET ?";
    public static final String LIST_IN_PROGRESS_EPISODES = "SELECT " + EPISODE_ROW_COLUMNS
            + EPISODE_ROW_JOINS + "WHERE e.playback_position_ms > 0 AND e.played = 0 "
            + "ORDER BY e.last_listened_at DESC, e.media_id ASC LIMIT ? OFFSET ?";
    public static final String LIST_NEW_EPISODES = "SELECT " + EPISODE_ROW_COLUMNS + EPISODE_ROW_JOINS
            + "WHERE s.is_subscribed = 1 AND e.is_stale = 0 AND e.first_seen_generation > s.seen_generation "
            + "ORDER BY e.published_at DESC, e.source_order ASC, e.media_id ASC LIMIT ? OFFSET ?";
    public static final String RECORD_LISTENING = "UPDATE episodes SET last_listened_at = ? "
            + "WHERE media_id = ? AND last_listened_at < ?";
    public static final String CREATE_LISTENING_TIME_INDEX =
            "CREATE INDEX episodes_by_listening_time ON episodes(last_listened_at DESC, media_id)";

    /** Only a trusted enum chooses SQL text; the search itself remains a bound value. */
    public static String listEpisodesSql(CatalogBrowseQuery.Filter filter) {
        return listEpisodesSql(filter, CatalogBrowseQuery.Order.NEWEST_FIRST);
    }

    public static String listEpisodesSql(CatalogBrowseQuery.Filter filter, CatalogBrowseQuery.Order order) {
        String condition = switch (filter) {
            case ALL -> "";
            case UNPLAYED -> " AND e.played = 0";
            case DOWNLOADED -> " AND e.downloaded = 1";
            case QUEUED -> " AND q.media_id IS NOT NULL";
            case COMPLETED -> " AND e.played = 1";
        };
        return LIST_EPISODES_BASE + condition
                + " ORDER BY e.is_stale ASC, CASE WHEN e.published_at = 0 THEN 1 ELSE 0 END ASC, e.published_at "
                + (order == CatalogBrowseQuery.Order.OLDEST_FIRST ? "ASC," : "DESC,")
                + " e.source_order ASC, e.media_id ASC"
                + " LIMIT ? OFFSET ?";
    }

    public static final String LIST_EPISODE_QUEUE = """
            SELECT q.media_id, e.title, e.author, e.duration_ms,
                   e.playback_position_ms, e.played, e.downloaded, e.is_stale,
                   q.position, s.id, s.title, s.artwork_url, s.is_subscribed
            FROM episode_queue AS q
            INNER JOIN episodes AS e ON e.media_id = q.media_id
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            ORDER BY q.position ASC, q.media_id ASC
            LIMIT ?
            """;

    public static final String FIND_EPISODE_QUEUE_POSITION = """
            SELECT position FROM episode_queue
            WHERE media_id = ?
            LIMIT 1
            """;

    public static final String LIST_EPISODE_QUEUE_POSITIONS = """
            SELECT position, media_id FROM episode_queue
            ORDER BY position ASC, media_id ASC
            LIMIT ?
            """;

    public static final String FIND_SUBSCRIPTION_REFRESH_TARGET = """
            SELECT subscription_url, final_feed_url, etag, last_modified, generation
            FROM subscriptions
            WHERE id = ? AND is_subscribed = 1
            LIMIT 1
            """;

    public static final String LIST_SCHEDULED_REFRESH_CANDIDATES = """
            SELECT id, updated_at, refresh_failure_count, last_refresh_attempt_at
            FROM subscriptions
            WHERE is_subscribed = 1
            ORDER BY id ASC
            LIMIT ?
            """;

    public static final String FIND_EPISODE_DOWNLOAD_TARGET = """
            SELECT e.audio_url, e.mime_type, e.declared_length, e.downloaded
            FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            WHERE e.media_id = ? AND s.is_subscribed = 1
            LIMIT 1
            """;

    public static final String FIND_EPISODE_DELETION_TARGET = """
            SELECT audio_url, mime_type, declared_length, downloaded
            FROM episodes WHERE media_id = ? LIMIT 1
            """;

    public static final String FIND_EPISODE_CHAPTER_TARGET = """
            SELECT e.media_id, e.chapter_url FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            WHERE e.media_id = ? AND e.chapter_url <> '' AND s.is_subscribed = 1
            LIMIT 1
            """;

    public static final String FIND_EPISODE_DETAILS = """
            SELECT e.media_id, e.title, e.author, e.notes, e.website_url,
                   e.published, e.duration_ms, e.playback_position_ms,
                   e.played, e.favorite,
                   CASE WHEN q.media_id IS NULL THEN 0 ELSE 1 END AS queued,
                   e.downloaded, e.is_stale,
                   CASE WHEN e.chapter_url = '' THEN 0 ELSE 1 END AS has_chapters,
                   s.id, s.title, s.artwork_url, s.is_subscribed
            FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            LEFT JOIN episode_queue AS q ON q.media_id = e.media_id
            WHERE e.media_id = ?
            LIMIT 1
            """;

    public static final String INSERT_SUBSCRIPTION = """
            INSERT INTO subscriptions (
                subscription_url, final_feed_url, title, author, notes,
                website_url, artwork_url, categories, etag, last_modified,
                generation, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            """;

    public static final String UPDATE_SUBSCRIPTION = """
            UPDATE subscriptions SET
                final_feed_url = ?, title = ?, author = ?, notes = ?,
                website_url = ?, artwork_url = ?, categories = ?,
                etag = ?, last_modified = ?, generation = generation + 1,
                is_subscribed = 1,
                updated_at = ?, last_error = '', refresh_failure_count = 0,
                last_refresh_attempt_at = 0
            WHERE id = ?
            """;

    public static final String SET_SUBSCRIPTION_AUTO_DOWNLOAD = """
            UPDATE subscriptions SET auto_download = ?
            WHERE id = ? AND is_subscribed = 1
            """;

    public static final String CLEAR_SUBSCRIPTION_AUTO_DOWNLOAD_PENDING =
            "UPDATE episodes SET auto_download_pending = 0 "
                    + "WHERE subscription_id = ? AND auto_download_pending = 1";
    public static final String CLEAR_STALE_AUTO_DOWNLOAD_PENDING =
            "UPDATE episodes SET auto_download_pending = 0 "
                    + "WHERE subscription_id = ? AND is_stale = 1";
    public static final String LIST_AUTOMATIC_DOWNLOAD_CANDIDATES = """
            SELECT e.media_id FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            WHERE e.auto_download_pending = 1 AND e.downloaded = 0
                AND e.auto_download_attempts < 3
                AND e.is_stale = 0 AND s.is_subscribed = 1 AND s.auto_download = 1
            ORDER BY e.created_at ASC, e.media_id ASC LIMIT ?
            """;
    public static final String FIND_AUTOMATIC_DOWNLOAD_CANDIDATE = """
            SELECT 1 FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            WHERE e.media_id = ? AND e.auto_download_pending = 1
                AND e.auto_download_attempts < 3
                AND e.downloaded = 0 AND e.is_stale = 0
                AND s.is_subscribed = 1 AND s.auto_download = 1 LIMIT 1
            """;
    public static final String CLEAR_AUTOMATIC_DOWNLOAD_CANDIDATE = """
            UPDATE episodes SET auto_download_pending = 0 WHERE media_id = ?
            """;
    public static final String RECORD_AUTOMATIC_DOWNLOAD_FAILURE = """
            UPDATE episodes SET auto_download_attempts = auto_download_attempts + 1,
                auto_download_pending = CASE WHEN auto_download_attempts >= 2
                    THEN 0 ELSE auto_download_pending END
            WHERE media_id = ? AND auto_download_pending = 1
                AND auto_download_attempts < 3
            """;

    public static final String RECORD_UNMODIFIED_SUBSCRIPTION = """
            UPDATE subscriptions SET
                etag = ?, last_modified = ?, updated_at = ?, last_error = '',
                refresh_failure_count = 0, last_refresh_attempt_at = 0
            WHERE id = ? AND is_subscribed = 1
                AND subscription_url = ? AND final_feed_url = ? AND generation = ?
            """;

    public static final String RECORD_SCHEDULED_REFRESH_FAILURE = """
            UPDATE subscriptions SET
                refresh_failure_count = ?, last_refresh_attempt_at = ?, last_error = ?
            WHERE id = ? AND is_subscribed = 1
                AND updated_at = ? AND refresh_failure_count = ?
                AND last_refresh_attempt_at = ?
            """;

    public static final String ARCHIVE_SUBSCRIPTION =
            "UPDATE subscriptions SET is_subscribed = 0, auto_download = 0, "
                    + "etag = '', last_modified = '', "
                    + "last_error = '', refresh_failure_count = 0, "
                    + "last_refresh_attempt_at = 0, updated_at = ? "
                    + "WHERE id = ? AND is_subscribed = 1";

    public static final String LIST_ARCHIVED_MEDIA_IDS = """
            SELECT e.media_id FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            WHERE s.id = ? AND s.is_subscribed = 0
            ORDER BY e.media_id ASC LIMIT ?
            """;

    public static final String DELETE_ARCHIVED_QUEUE_ENTRIES = """
            DELETE FROM episode_queue WHERE media_id IN (
                SELECT media_id FROM episodes WHERE subscription_id = ?)
            """;

    public static final String DELETE_ARCHIVED_SUBSCRIPTION =
            "DELETE FROM subscriptions WHERE id = ? AND is_subscribed = 0";

    public static final String MARK_EPISODE_DOWNLOAD_COMPLETE = """
            UPDATE episodes SET downloaded = 1, auto_download_pending = 0,
                download_completed_at = ?, download_bytes = ?, updated_at = ?
            WHERE media_id = ? AND audio_url = ? AND mime_type = ?
                AND declared_length = ?
                AND EXISTS (SELECT 1 FROM subscriptions AS s
                    WHERE s.id = episodes.subscription_id AND s.is_subscribed = 1)
            """;

    public static final String MARK_EPISODE_DOWNLOAD_ABSENT = """
            UPDATE episodes SET downloaded = 0, download_completed_at = 0,
                download_bytes = 0, updated_at = ?
            WHERE media_id = ?
            """;

    public static final String FIND_COMPLETED_DOWNLOAD_FOR_RETENTION = """
            SELECT 1 FROM episodes AS e
            INNER JOIN subscriptions AS s ON s.id = e.subscription_id
            WHERE e.media_id = ? AND e.downloaded = 1 AND e.played = 1
                AND e.favorite = 0 AND s.is_subscribed = 1
                AND NOT EXISTS (SELECT 1 FROM episode_queue AS q
                    WHERE q.media_id = e.media_id)
            LIMIT 1
            """;

    public static final String MARK_RETAINED_DOWNLOAD_ABSENT = """
            UPDATE episodes SET downloaded = 0, download_completed_at = 0,
                download_bytes = 0, updated_at = ?
            WHERE media_id = ? AND downloaded = 1 AND played = 1 AND favorite = 0
                AND EXISTS (SELECT 1 FROM subscriptions AS s
                    WHERE s.id = episodes.subscription_id AND s.is_subscribed = 1)
                AND NOT EXISTS (SELECT 1 FROM episode_queue AS q
                    WHERE q.media_id = episodes.media_id)
            """;

    public static final String SET_EPISODE_PLAYED = """
            UPDATE episodes SET
                played = ?,
                playback_position_ms = CASE
                    WHEN ? = 0 THEN 0 ELSE playback_position_ms END,
                updated_at = ?
            WHERE media_id = ?
                AND (played != ? OR (? = 0 AND playback_position_ms != 0))
            """;

    public static final String SAVE_PLAYBACK_PROGRESS = """
            UPDATE episodes SET
                playback_position_ms = ?,
                played = CASE WHEN ? = 1 THEN 1 ELSE played END,
                updated_at = ?
            WHERE media_id = ?
                AND (playback_position_ms != ? OR (? = 1 AND played = 0))
            """;

    public static final String SET_EPISODE_FAVORITE = """
            UPDATE episodes SET favorite = ?, updated_at = ?
            WHERE media_id = ? AND favorite != ?
            """;

    public static final String INSERT_EPISODE_QUEUE = """
            INSERT INTO episode_queue (media_id, position, enqueued_at)
            VALUES (?, ?, ?)
            """;

    public static final String DELETE_EPISODE_QUEUE =
            "DELETE FROM episode_queue WHERE media_id = ?";

    public static final String SHIFT_EPISODE_QUEUE_FOR_FIRST =
            "UPDATE episode_queue SET position = position + 1";

    public static final String SHIFT_EPISODE_QUEUE_AFTER_REMOVE =
            "UPDATE episode_queue SET position = position - 1 WHERE position > ?";

    public static final String PARK_EPISODE_QUEUE_ENTRY =
            "UPDATE episode_queue SET position = " + SecurityLimits.MAX_QUEUE_ENTRIES
                    + " WHERE media_id = ?";

    public static final String SHIFT_EPISODE_QUEUE_LATER = """
            UPDATE episode_queue SET position = position - 1
            WHERE position > ? AND position <= ?
            """;

    public static final String SHIFT_EPISODE_QUEUE_EARLIER = """
            UPDATE episode_queue SET position = position + 1
            WHERE position >= ? AND position < ?
            """;

    public static final String SET_EPISODE_QUEUE_POSITION =
            "UPDATE episode_queue SET position = ? WHERE media_id = ?";

    public static final String MARK_MISSING_EPISODES_STALE =
            "UPDATE episodes SET is_stale = 1 WHERE subscription_id = ? "
            + "AND last_seen_generation <> ? AND is_stale = 0";

    public static final String INSERT_EPISODE = """
            INSERT INTO episodes (
                media_id, subscription_id, audio_url, mime_type, declared_length,
                title, author, notes, website_url, published, published_at,
                duration_ms, chapter_url,
                categories, source_order, last_seen_generation, first_seen_generation, is_stale,
                auto_download_pending,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """;

    public static final String UPDATE_EPISODE = """
            UPDATE episodes SET
                audio_url = ?, mime_type = ?, declared_length = ?, title = ?,
                author = ?, notes = ?, website_url = ?, published = ?, published_at = ?,
                duration_ms = ?, chapter_url = ?, categories = ?, source_order = ?,
                last_seen_generation = ?, is_stale = 0, updated_at = ?
            WHERE media_id = ? AND subscription_id = ?
            """;

    public static final String PRUNE_SUBSCRIPTION = """
            DELETE FROM episodes WHERE media_id IN (
                SELECT media_id FROM episodes
                WHERE subscription_id = ? AND is_stale = 1 AND played = 1
                    AND favorite = 0 AND downloaded = 0
                    AND NOT EXISTS (
                        SELECT 1 FROM episode_queue AS q
                        WHERE q.media_id = episodes.media_id
                    )
                    AND NOT EXISTS (SELECT 1 FROM manual_download_requests r WHERE r.media_id=episodes.media_id)
                ORDER BY updated_at ASC, media_id ASC
                LIMIT ?
            )
            """;

    public static final String PRUNE_GLOBAL = """
            DELETE FROM episodes WHERE media_id IN (
                SELECT media_id FROM episodes
                WHERE is_stale = 1 AND played = 1
                    AND favorite = 0 AND downloaded = 0
                    AND NOT EXISTS (
                        SELECT 1 FROM episode_queue AS q
                        WHERE q.media_id = episodes.media_id
                    )
                    AND NOT EXISTS (SELECT 1 FROM manual_download_requests r WHERE r.media_id=episodes.media_id)
                ORDER BY updated_at ASC, media_id ASC
                LIMIT ?
            )
            """;

    private CatalogSql() {
        throw new AssertionError("No instances");
    }

    public static List<String> createStatements() {
        return Collections.unmodifiableList(Arrays.asList(
                CREATE_SUBSCRIPTIONS,
                CREATE_EPISODES,
                CREATE_EPISODE_SUBSCRIPTION_INDEX,
                CREATE_PRUNE_INDEX,
                CREATE_EPISODE_QUEUE,
                CREATE_EPISODE_QUEUE_POSITION_INDEX,
                CREATE_DOWNLOAD_TIME_INDEX,
                CREATE_SUBSCRIPTION_LOGINS, CREATE_AUDIO_OWNER_INDEX, CREATE_LISTENING_TIME_INDEX,
                CREATE_PODCAST_PLAYBACK_SETTINGS, CREATE_MANUAL_DOWNLOADS, CREATE_MANUAL_DOWNLOAD_INDEX));
    }

    public static List<String> migrateVersion1To2Statements() {
        return Collections.unmodifiableList(Arrays.asList(
                MIGRATE_SUBSCRIPTIONS_ADD_REFRESH_FAILURE_COUNT,
                MIGRATE_SUBSCRIPTIONS_ADD_LAST_REFRESH_ATTEMPT));
    }

    public static List<String> migrateVersion2To3Statements() {
        return Collections.unmodifiableList(Arrays.asList(
                CREATE_EPISODE_QUEUE,
                CREATE_EPISODE_QUEUE_POSITION_INDEX));
    }

    public static List<String> migrateVersion3To4Statements() {
        return Collections.singletonList(MIGRATE_EPISODES_ADD_CHAPTER_URL);
    }

    public static List<String> migrateVersion4To5Statements() {
        return Collections.singletonList(MIGRATE_SUBSCRIPTIONS_ADD_SUBSCRIBED);
    }

    public static List<String> migrateVersion5To6Statements() {
        return Collections.singletonList(MIGRATE_EPISODES_ADD_PUBLISHED_AT);
    }

    public static List<String> migrateVersion6To7Statements() {
        return Collections.singletonList(MIGRATE_SUBSCRIPTIONS_ADD_AUTO_DOWNLOAD);
    }

    public static List<String> migrateVersion7To8Statements() {
        return Collections.singletonList(MIGRATE_EPISODES_ADD_AUTO_DOWNLOAD_PENDING);
    }

    public static List<String> migrateVersion8To9Statements() {
        return Collections.singletonList(MIGRATE_EPISODES_ADD_AUTO_DOWNLOAD_ATTEMPTS);
    }

    public static List<String> migrateVersion10To11Statements() {
        return Collections.unmodifiableList(Arrays.asList(CREATE_SUBSCRIPTION_LOGINS, CREATE_AUDIO_OWNER_INDEX));
    }

    public static final String MARK_SUBSCRIPTION_VIEWED = """
            UPDATE subscriptions SET seen_generation = MAX(seen_generation, MIN(generation, ?))
            WHERE id = ? AND has_episode_baseline = 1
            """;
    public static final String ESTABLISH_SEEN_BASELINE = """
            UPDATE subscriptions SET seen_generation = generation, has_episode_baseline = 1
            WHERE id = ? AND has_episode_baseline = 0
            """;
    public static final String RESET_SEEN_BASELINE = """
            UPDATE subscriptions SET seen_generation = generation, has_episode_baseline = 1 WHERE id = ?
            """;
    public static final String SET_EPISODE_ORDER = "UPDATE subscriptions SET episode_order = ? WHERE id = ?";

    public static List<String> migrateVersion11To12Statements() {
        return List.of(
                "ALTER TABLE subscriptions ADD COLUMN seen_generation INTEGER NOT NULL DEFAULT 0 CHECK (seen_generation >= 0)",
                "ALTER TABLE subscriptions ADD COLUMN has_episode_baseline INTEGER NOT NULL DEFAULT 0 CHECK (has_episode_baseline IN (0, 1))",
                "ALTER TABLE subscriptions ADD COLUMN episode_order INTEGER NOT NULL DEFAULT 0 CHECK (episode_order IN (0, 1))",
                "ALTER TABLE episodes ADD COLUMN first_seen_generation INTEGER NOT NULL DEFAULT 1 CHECK (first_seen_generation >= 1)",
                "UPDATE subscriptions SET seen_generation = generation, has_episode_baseline = CASE WHEN EXISTS "
                        + "(SELECT 1 FROM episodes WHERE subscription_id = subscriptions.id) THEN 1 ELSE 0 END");
    }

    public static List<String> migrateVersion12To13Statements() {
        return List.of("ALTER TABLE episodes ADD COLUMN last_listened_at INTEGER NOT NULL DEFAULT 0 CHECK (last_listened_at >= 0)",
                CREATE_LISTENING_TIME_INDEX);
    }

    public static List<String> migrateVersion14To15Statements() {
        return List.of(CREATE_MANUAL_DOWNLOADS,CREATE_MANUAL_DOWNLOAD_INDEX);
    }

    public static List<String> migrateVersion13To14Statements() {
        return List.of(CREATE_PODCAST_PLAYBACK_SETTINGS);
    }

    public static List<String> migrateVersion9To10Statements() {
        return Collections.unmodifiableList(Arrays.asList(
                MIGRATE_EPISODES_ADD_DOWNLOAD_TIME,
                MIGRATE_EPISODES_ADD_DOWNLOAD_BYTES,
                CREATE_DOWNLOAD_TIME_INDEX));
    }
}
