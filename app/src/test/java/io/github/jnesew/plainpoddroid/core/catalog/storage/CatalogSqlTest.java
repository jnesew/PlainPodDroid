package io.github.jnesew.plainpoddroid.core.catalog.storage;

import java.util.List;
import java.util.Locale;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CatalogSqlTest {
    @Test
    public void retentionCleanupRequiresPlayedUnprotectedActiveDownload() {
        String find = CatalogSql.FIND_COMPLETED_DOWNLOAD_FOR_RETENTION
                .toLowerCase(Locale.ROOT);
        String update = CatalogSql.MARK_RETAINED_DOWNLOAD_ABSENT.toLowerCase(Locale.ROOT);
        for (String sql : List.of(find, update)) {
            assertTrue(sql.contains("media_id = ?"));
            assertTrue(sql.contains("downloaded = 1"));
            assertTrue(sql.contains("played = 1"));
            assertTrue(sql.contains("favorite = 0"));
            assertTrue(sql.contains("s.is_subscribed = 1"));
            assertTrue(sql.contains("not exists (select 1 from episode_queue as q"));
            assertFalse(sql.contains("audio_url"));
        }
        assertTrue(find.contains("limit 1"));
        assertEquals(1, count(find, '?'));
        assertEquals(2, count(update, '?'));
    }

    @Test
    public void schemaIsImmutableAndContainsRequiredIntegrityBoundaries() {
        List<String> statements = CatalogSql.createStatements();

        assertEquals(13, statements.size());
        assertThrows(UnsupportedOperationException.class, () -> statements.add("DROP TABLE x"));
        String subscriptions = CatalogSql.CREATE_SUBSCRIPTIONS.toLowerCase(Locale.ROOT);
        String episodes = CatalogSql.CREATE_EPISODES.toLowerCase(Locale.ROOT);
        assertTrue(subscriptions.contains("subscription_url text not null unique"));
        assertTrue(subscriptions.contains("refresh_failure_count integer not null default 0"));
        assertTrue(subscriptions.contains("last_refresh_attempt_at integer not null default 0"));
        assertTrue(episodes.contains("references subscriptions(id) on delete cascade"));
        assertTrue(episodes.contains("media_id text primary key"));
        assertTrue(episodes.contains("without rowid"));
        assertTrue(episodes.contains("declared_length between -1 and 2147483648"));
        assertTrue(episodes.contains("duration_ms between -1 and 172800000"));
        assertTrue(episodes.contains("chapter_url text not null"));
        assertTrue(episodes.contains("length(chapter_url) <= 4096"));
        String queue = CatalogSql.CREATE_EPISODE_QUEUE.toLowerCase(Locale.ROOT);
        assertTrue(queue.contains("media_id text primary key"));
        assertTrue(queue.contains("references episodes(media_id) on delete cascade"));
        assertTrue(queue.contains("position between 0 and 2000"));
        assertTrue(queue.contains("without rowid"));
    }

    @Test
    public void chapterLookupIsBoundedAndInternalIdOnly() {
        String lookup = CatalogSql.FIND_EPISODE_CHAPTER_TARGET.toLowerCase(Locale.ROOT);
        assertTrue(lookup.contains("select e.media_id, e.chapter_url"));
        assertTrue(lookup.contains("where e.media_id = ? and e.chapter_url <> ''"));
        assertTrue(lookup.contains("s.is_subscribed = 1"));
        assertTrue(lookup.contains("limit 1"));
        assertEquals(1, count(CatalogSql.FIND_EPISODE_CHAPTER_TARGET, '?'));
        assertTrue(CatalogSql.FIND_EPISODE_DETAILS.contains("END AS has_chapters"));
        assertFalse(CatalogSql.LIST_EPISODES.toLowerCase(Locale.ROOT).contains("chapter_url"));
    }

    @Test
    public void publisherUpdateCannotOverwriteUserOwnedState() {
        String update = CatalogSql.UPDATE_EPISODE.toLowerCase(Locale.ROOT);

        assertTrue(update.contains("audio_url = ?"));
        assertTrue(update.contains("last_seen_generation = ?"));
        assertFalse(update.contains("playback_position_ms ="));
        assertFalse(update.contains("played ="));
        assertFalse(update.contains("favorite ="));
        assertFalse(update.contains("queued ="));
        assertFalse(update.contains("downloaded ="));
        assertFalse(update.contains("auto_download_pending ="));
        assertFalse(update.contains("created_at ="));
        assertTrue(update.contains("where media_id = ? and subscription_id = ?"));
        assertTrue(CatalogSql.INSERT_EPISODE
                .toLowerCase(Locale.ROOT)
                .startsWith("insert into"));
    }

    @Test
    public void pruningTargetsOnlyStalePlayedUnprotectedEpisodes() {
        for (String sql : List.of(CatalogSql.PRUNE_SUBSCRIPTION, CatalogSql.PRUNE_GLOBAL)) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            assertTrue(normalized.contains("is_stale = 1"));
            assertTrue(normalized.contains("played = 1"));
            assertTrue(normalized.contains("favorite = 0"));
            assertTrue(normalized.contains("not exists"));
            assertTrue(normalized.contains("from episode_queue as q"));
            assertTrue(normalized.contains("q.media_id = episodes.media_id"));
            assertTrue(normalized.contains("downloaded = 0"));
            assertTrue(normalized.contains("order by updated_at asc, media_id asc"));
            assertTrue(normalized.contains("limit ?"));
        }
    }

    @Test
    public void allMutationTemplatesUseBoundParameters() {
        assertEquals(12, count(CatalogSql.INSERT_SUBSCRIPTION, '?'));
        assertEquals(11, count(CatalogSql.UPDATE_SUBSCRIPTION, '?'));
        assertEquals(7, count(CatalogSql.RECORD_UNMODIFIED_SUBSCRIPTION, '?'));
        assertEquals(7, count(CatalogSql.RECORD_SCHEDULED_REFRESH_FAILURE, '?'));
        assertEquals(20, count(CatalogSql.INSERT_EPISODE, '?'));
        assertEquals(17, count(CatalogSql.UPDATE_EPISODE, '?'));
        assertEquals(2, count(CatalogSql.PRUNE_SUBSCRIPTION, '?'));
        assertEquals(1, count(CatalogSql.PRUNE_GLOBAL, '?'));
        assertEquals(3, count(CatalogSql.INSERT_EPISODE_QUEUE, '?'));
        assertEquals(1, count(CatalogSql.DELETE_EPISODE_QUEUE, '?'));
        assertEquals(2, count(CatalogSql.SHIFT_EPISODE_QUEUE_LATER, '?'));
        assertEquals(2, count(CatalogSql.SHIFT_EPISODE_QUEUE_EARLIER, '?'));
        assertEquals(2, count(CatalogSql.SET_EPISODE_QUEUE_POSITION, '?'));
        assertEquals(6, count(CatalogSql.SET_EPISODE_PLAYED, '?'));
        assertEquals(6, count(CatalogSql.SAVE_PLAYBACK_PROGRESS, '?'));
        assertEquals(4, count(CatalogSql.SET_EPISODE_FAVORITE, '?'));
    }

    @Test
    public void versionThreeMigrationAddsOnlyBoundedOptionalChapterUrl() {
        List<String> statements = CatalogSql.migrateVersion3To4Statements();

        assertEquals(1, statements.size());
        String migration = statements.get(0).toLowerCase(Locale.ROOT);
        assertTrue(migration.startsWith("alter table episodes add column chapter_url"));
        assertTrue(migration.contains("text not null default ''"));
        assertTrue(migration.contains("length(chapter_url) <= 4096"));
        assertThrows(UnsupportedOperationException.class, () -> statements.add("drop table x"));
    }

    @Test
    public void browseQueriesAreBoundedAndOnlyExposeRequestedPodcastSource() {
        String subscriptions = CatalogSql.LIST_SUBSCRIPTIONS.toLowerCase(Locale.ROOT);
        String episodes = CatalogSql.LIST_EPISODES.toLowerCase(Locale.ROOT);

        assertTrue(subscriptions.contains("limit ?"));
        assertTrue(subscriptions.contains("s.subscription_url"));
        assertFalse(subscriptions.contains("final_feed_url"));
        assertFalse(subscriptions.contains("notes"));
        assertFalse(subscriptions.contains("last_error"));
        assertEquals(1, count(CatalogSql.LIST_SUBSCRIPTIONS, '?'));
        assertTrue(subscriptions.contains("s.refresh_failure_count"));
        assertTrue(subscriptions.contains("s.last_refresh_attempt_at"));
        assertTrue(subscriptions.contains("s.artwork_url"));
        assertTrue(subscriptions.contains("s.auto_download"));

        assertTrue(episodes.contains("where e.subscription_id = ?"));
        assertTrue(episodes.contains("limit ? offset ?"));
        assertTrue(episodes.contains("left join episode_queue as q"));
        assertTrue(episodes.contains("case when q.media_id is null then 0 else 1 end"));
        assertFalse(episodes.contains("audio_url"));
        assertFalse(episodes.contains("website_url"));
        assertFalse(episodes.contains("notes"));
        assertTrue(episodes.contains("instr(lower(e.title), lower(?)) > 0"));
        assertEquals(5, count(CatalogSql.LIST_EPISODES, '?'));
        for (io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery.Filter filter
                : io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery.Filter.values()) {
            String filtered = CatalogSql.listEpisodesSql(filter).toLowerCase(Locale.ROOT);
            assertEquals(5, count(filtered, '?'));
            assertFalse(filtered.contains("audio_url"));
        }
        assertTrue(CatalogSql.listEpisodesSql(
                io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery.Filter.QUEUED)
                .contains("q.media_id IS NOT NULL"));
    }

    @Test
    public void dateMigrationAddsOnlyDerivedSortKeyWithoutChangingUserState() {
        List<String> statements = CatalogSql.migrateVersion5To6Statements();
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).contains("published_at INTEGER NOT NULL DEFAULT 0"));
        assertTrue(CatalogSql.CREATE_EPISODES.contains("published_at INTEGER NOT NULL"));
        assertEquals(1, count(CatalogSql.LIST_EPISODES_FOR_DATE_BACKFILL, '?'));
        assertEquals(2, count(CatalogSql.UPDATE_EPISODE_PUBLISHED_AT, '?'));
        assertFalse(CatalogSql.UPDATE_EPISODE_PUBLISHED_AT.contains("played ="));
        assertThrows(UnsupportedOperationException.class,
                () -> statements.add("DROP TABLE episodes"));
    }

    @Test
    public void automaticDownloadOptInDefaultsOffAndUpdatesOnlyActiveSubscription() {
        String create = CatalogSql.CREATE_SUBSCRIPTIONS.toLowerCase(Locale.ROOT);
        assertTrue(create.contains("auto_download integer not null default 0"));
        List<String> migrations = CatalogSql.migrateVersion6To7Statements();
        assertEquals(1, migrations.size());
        assertTrue(migrations.get(0).contains("DEFAULT 0"));
        assertTrue(migrations.get(0).contains("CHECK (auto_download IN (0, 1))"));
        String update = CatalogSql.SET_SUBSCRIPTION_AUTO_DOWNLOAD.toLowerCase(Locale.ROOT);
        assertTrue(update.contains("where id = ? and is_subscribed = 1"));
        assertFalse(update.contains("generation ="));
        assertEquals(2, count(update, '?'));
    }

    @Test
    public void automaticCandidatesExcludeArchivedStaleAndAlreadyDownloadedRows() {
        String list = CatalogSql.LIST_AUTOMATIC_DOWNLOAD_CANDIDATES.toLowerCase(Locale.ROOT);
        assertTrue(list.contains("e.auto_download_pending = 1"));
        assertTrue(list.contains("e.auto_download_attempts < 3"));
        assertTrue(list.contains("e.downloaded = 0"));
        assertTrue(list.contains("e.is_stale = 0"));
        assertTrue(list.contains("s.is_subscribed = 1"));
        assertTrue(list.contains("s.auto_download = 1"));
        assertTrue(list.contains("limit ?"));
        assertFalse(list.contains("audio_url"));
        assertEquals(1, count(list, '?'));
        assertEquals(1, count(CatalogSql.FIND_AUTOMATIC_DOWNLOAD_CANDIDATE, '?'));
        assertEquals(1, count(CatalogSql.CLEAR_AUTOMATIC_DOWNLOAD_CANDIDATE, '?'));
        assertEquals(1, count(CatalogSql.RECORD_AUTOMATIC_DOWNLOAD_FAILURE, '?'));
        assertTrue(CatalogSql.RECORD_AUTOMATIC_DOWNLOAD_FAILURE.contains(
                "auto_download_pending = CASE"));
        assertTrue(CatalogSql.CREATE_EPISODES.contains(
                "auto_download_attempts INTEGER NOT NULL DEFAULT 0"));
        assertEquals(1, CatalogSql.migrateVersion8To9Statements().size());
    }

    @Test
    public void pendingNewEpisodeMarkerIsDerivedAndClearedOnOptOutOrCompletion() {
        String episodes = CatalogSql.CREATE_EPISODES.toLowerCase(Locale.ROOT);
        assertTrue(episodes.contains("auto_download_pending integer not null default 0"));
        List<String> migration = CatalogSql.migrateVersion7To8Statements();
        assertEquals(1, migration.size());
        assertTrue(migration.get(0).contains("DEFAULT 0"));
        assertTrue(CatalogSql.INSERT_EPISODE.contains("auto_download_pending"));
        assertFalse(CatalogSql.UPDATE_EPISODE.contains("auto_download_pending"));
        assertTrue(CatalogSql.MARK_EPISODE_DOWNLOAD_COMPLETE
                .contains("auto_download_pending = 0"));
        assertTrue(CatalogSql.ARCHIVE_SUBSCRIPTION.contains("auto_download = 0"));
        assertTrue(CatalogSql.CLEAR_SUBSCRIPTION_AUTO_DOWNLOAD_PENDING
                .contains("subscription_id = ?"));
        assertTrue(CatalogSql.CLEAR_STALE_AUTO_DOWNLOAD_PENDING
                .contains("is_stale = 1"));
    }

    @Test
    public void refreshTargetLookupIsInternalIdOnlyAndParameterized() {
        String lookup = CatalogSql.FIND_SUBSCRIPTION_REFRESH_TARGET
                .toLowerCase(Locale.ROOT);

        assertTrue(lookup.contains(
                "select subscription_url, final_feed_url, etag, last_modified"));
        assertTrue(lookup.contains("where id = ?"));
        assertTrue(lookup.contains("limit 1"));
        assertEquals(1, count(CatalogSql.FIND_SUBSCRIPTION_REFRESH_TARGET, '?'));

        String unmodified = CatalogSql.RECORD_UNMODIFIED_SUBSCRIPTION
                .toLowerCase(Locale.ROOT);
        assertTrue(unmodified.contains("etag = ?"));
        assertTrue(unmodified.contains("last_modified = ?"));
        assertFalse(unmodified.substring(0,unmodified.indexOf("where")).contains("generation"));
        assertTrue(unmodified.contains("and generation = ?"));
        assertTrue(unmodified.contains(
                "where id = ? and is_subscribed = 1"));
        assertTrue(unmodified.contains(
                "and subscription_url = ? and final_feed_url = ?"));
    }

    @Test
    public void scheduledRefreshStateUsesOnlyInternalIdsAndCompareAndSetMutation() {
        String list = CatalogSql.LIST_SCHEDULED_REFRESH_CANDIDATES
                .toLowerCase(Locale.ROOT);
        assertTrue(list.contains(
                "select id, updated_at, refresh_failure_count, last_refresh_attempt_at"));
        assertTrue(list.contains("order by id asc"));
        assertTrue(list.contains("where is_subscribed = 1"));
        assertTrue(list.contains("limit ?"));
        assertFalse(list.contains("subscription_url"));
        assertFalse(list.contains("final_feed_url"));
        assertEquals(1, count(CatalogSql.LIST_SCHEDULED_REFRESH_CANDIDATES, '?'));

        String failure = CatalogSql.RECORD_SCHEDULED_REFRESH_FAILURE
                .toLowerCase(Locale.ROOT);
        assertTrue(failure.contains("last_error = ?"));
        assertTrue(failure.contains(
                "where id = ? and is_subscribed = 1"));
        assertTrue(failure.contains(
                "and updated_at = ? and refresh_failure_count = ?"));
        assertTrue(failure.contains("and last_refresh_attempt_at = ?"));
        assertFalse(failure.contains("subscription_url"));
    }

    @Test
    public void versionOneMigrationIsMinimalImmutableAndBounded() {
        List<String> migration = CatalogSql.migrateVersion1To2Statements();

        assertEquals(2, migration.size());
        assertThrows(UnsupportedOperationException.class, () -> migration.add("DROP TABLE x"));
        assertTrue(migration.get(0).toLowerCase(Locale.ROOT)
                .contains("add column refresh_failure_count integer not null default 0"));
        assertTrue(migration.get(1).toLowerCase(Locale.ROOT)
                .contains("add column last_refresh_attempt_at integer not null default 0"));
        assertFalse(String.join(" ", migration).toLowerCase(Locale.ROOT).contains("drop "));
    }

    @Test
    public void versionTwoMigrationAddsOnlyDurableQueueState() {
        List<String> migration = CatalogSql.migrateVersion2To3Statements();

        assertEquals(2, migration.size());
        assertThrows(UnsupportedOperationException.class, () -> migration.add("DROP TABLE x"));
        assertEquals(CatalogSql.CREATE_EPISODE_QUEUE, migration.get(0));
        assertEquals(CatalogSql.CREATE_EPISODE_QUEUE_POSITION_INDEX, migration.get(1));
        assertFalse(String.join(" ", migration).toLowerCase(Locale.ROOT).contains("drop "));
    }

    @Test
    public void queueQueriesAreBoundedOrderedAndUrlFree() {
        String list = CatalogSql.LIST_EPISODE_QUEUE.toLowerCase(Locale.ROOT);
        assertTrue(list.contains("inner join episodes as e"));
        assertTrue(list.contains("order by q.position asc, q.media_id asc"));
        assertTrue(list.contains("limit ?"));
        assertFalse(list.contains("audio_url"));
        assertFalse(list.contains("website_url"));
        assertFalse(list.contains("notes"));
        assertEquals(1, count(CatalogSql.LIST_EPISODE_QUEUE, '?'));

        String positions = CatalogSql.LIST_EPISODE_QUEUE_POSITIONS
                .toLowerCase(Locale.ROOT);
        assertTrue(positions.contains("order by position asc, media_id asc"));
        assertTrue(positions.contains("limit ?"));
        assertEquals(1, count(CatalogSql.LIST_EPISODE_QUEUE_POSITIONS, '?'));
    }

    @Test
    public void downloadTargetAndMutationsAreInternalIdOnlyAndCompareAndSet() {
        String lookup = CatalogSql.FIND_EPISODE_DOWNLOAD_TARGET
                .toLowerCase(Locale.ROOT);
        assertTrue(lookup.contains(
                "select e.audio_url, e.mime_type, e.declared_length, e.downloaded"));
        assertTrue(lookup.contains("where e.media_id = ? and s.is_subscribed = 1"));
        assertEquals(1, count(CatalogSql.FIND_EPISODE_DOWNLOAD_TARGET, '?'));

        String complete = CatalogSql.MARK_EPISODE_DOWNLOAD_COMPLETE
                .toLowerCase(Locale.ROOT);
        assertTrue(complete.contains("downloaded = 1"));
        assertTrue(complete.contains(
                "where media_id = ? and audio_url = ? and mime_type = ?"));
        assertTrue(complete.contains("and declared_length = ?"));
        assertTrue(complete.contains("s.is_subscribed = 1"));
        assertEquals(7, count(CatalogSql.MARK_EPISODE_DOWNLOAD_COMPLETE, '?'));

        String absent = CatalogSql.MARK_EPISODE_DOWNLOAD_ABSENT
                .toLowerCase(Locale.ROOT);
        assertTrue(absent.contains("downloaded = 0"));
        assertTrue(absent.contains("where media_id = ?"));
        assertEquals(2, count(CatalogSql.MARK_EPISODE_DOWNLOAD_ABSENT, '?'));
    }

    @Test
    public void episodeDetailsAreInternalIdOnlyAndExcludePrivateMediaTargets() {
        String details = CatalogSql.FIND_EPISODE_DETAILS.toLowerCase(Locale.ROOT);

        assertTrue(details.contains("select e.media_id, e.title, e.author, e.notes"));
        assertTrue(details.contains("e.website_url"));
        assertTrue(details.contains("left join episode_queue as q"));
        assertTrue(details.contains(
                "case when q.media_id is null then 0 else 1 end as queued"));
        assertTrue(details.contains("where e.media_id = ?"));
        assertTrue(details.contains("limit 1"));
        assertFalse(details.contains("audio_url"));
        assertFalse(details.contains("subscription_url"));
        assertFalse(details.contains("final_feed_url"));
        assertEquals(1, count(CatalogSql.FIND_EPISODE_DETAILS, '?'));
    }

    @Test
    public void episodeStateMutationsAreNarrowBoundCompareAndSetUpdates() {
        String played = CatalogSql.SET_EPISODE_PLAYED.toLowerCase(Locale.ROOT);
        assertTrue(played.contains("update episodes set"));
        assertTrue(played.contains("played = ?"));
        assertTrue(played.contains("when ? = 0 then 0 else playback_position_ms end"));
        assertTrue(played.contains("where media_id = ?"));
        assertTrue(played.contains("played != ?"));
        assertTrue(played.contains("playback_position_ms != 0"));
        assertFalse(played.contains("favorite ="));
        assertFalse(played.contains("queued ="));
        assertFalse(played.contains("downloaded ="));
        assertEquals(6, count(CatalogSql.SET_EPISODE_PLAYED, '?'));

        String progress = CatalogSql.SAVE_PLAYBACK_PROGRESS.toLowerCase(Locale.ROOT);
        assertTrue(progress.contains("playback_position_ms = ?"));
        assertTrue(progress.contains("case when ? = 1 then 1 else played end"));
        assertTrue(progress.contains("where media_id = ?"));
        assertTrue(progress.contains("playback_position_ms != ?"));
        assertTrue(progress.contains("? = 1 and played = 0"));
        assertFalse(progress.contains("else 0"));
        assertFalse(progress.contains("favorite ="));
        assertFalse(progress.contains("queued ="));
        assertFalse(progress.contains("downloaded ="));
        assertEquals(6, count(CatalogSql.SAVE_PLAYBACK_PROGRESS, '?'));

        String favorite = CatalogSql.SET_EPISODE_FAVORITE.toLowerCase(Locale.ROOT);
        assertTrue(favorite.contains("update episodes set favorite = ?, updated_at = ?"));
        assertTrue(favorite.contains("where media_id = ? and favorite != ?"));
        assertFalse(favorite.contains("played ="));
        assertFalse(favorite.contains("queued ="));
        assertFalse(favorite.contains("downloaded ="));
        assertEquals(4, count(CatalogSql.SET_EPISODE_FAVORITE, '?'));
    }

    private static int count(String value, char expected) {
        int result = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == expected) {
                result++;
            }
        }
        return result;
    }
}
