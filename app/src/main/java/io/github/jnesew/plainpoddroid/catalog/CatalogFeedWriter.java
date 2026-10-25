package io.github.jnesew.plainpoddroid.catalog;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisode;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogPublishTime;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.catalog.storage.CatalogSql;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import java.util.List;

/** Feed metadata bindings only. The facade owns the lock, transaction, generations and pruning. */
final class CatalogFeedWriter {
    private CatalogFeedWriter() { }

    static long insertSubscription(
            SQLiteDatabase database,
            CatalogSnapshot snapshot,
            FeedValidators validators,
            long now) throws CatalogStorageException {
        SQLiteStatement statement = database.compileStatement(CatalogSql.INSERT_SUBSCRIPTION);
        try {
            int index = 1;
            statement.bindString(index++, snapshot.getSubscriptionUrl().toString());
            statement.bindString(index++, snapshot.getFinalFeedUrl().toString());
            statement.bindString(index++, snapshot.getTitle());
            statement.bindString(index++, snapshot.getAuthor());
            statement.bindString(index++, snapshot.getNotes());
            statement.bindString(index++, optionalUrl(snapshot.getWebsiteUrl()));
            statement.bindString(index++, optionalUrl(snapshot.getArtworkUrl()));
            statement.bindString(index++, joinCategories(snapshot.getCategories()));
            statement.bindString(index++, validators.getEtag());
            statement.bindString(index++, validators.getLastModified());
            statement.bindLong(index++, now);
            statement.bindLong(index, now);
            long id = statement.executeInsert();
            if (id < 1L) {
                throw failure(
                        CatalogStorageException.Reason.STORAGE_FAILURE,
                        "Catalog subscription insert failed.");
            }
            return id;
        } finally {
            statement.close();
        }
    }

    static void updateSubscription(
            SQLiteDatabase database,
            long subscriptionId,
            CatalogSnapshot snapshot,
            FeedValidators validators,
            long now) throws CatalogStorageException {
        SQLiteStatement statement = database.compileStatement(CatalogSql.UPDATE_SUBSCRIPTION);
        try {
            int index = 1;
            statement.bindString(index++, snapshot.getFinalFeedUrl().toString());
            statement.bindString(index++, snapshot.getTitle());
            statement.bindString(index++, snapshot.getAuthor());
            statement.bindString(index++, snapshot.getNotes());
            statement.bindString(index++, optionalUrl(snapshot.getWebsiteUrl()));
            statement.bindString(index++, optionalUrl(snapshot.getArtworkUrl()));
            statement.bindString(index++, joinCategories(snapshot.getCategories()));
            statement.bindString(index++, validators.getEtag());
            statement.bindString(index++, validators.getLastModified());
            statement.bindLong(index++, now);
            statement.bindLong(index, subscriptionId);
            if (statement.executeUpdateDelete() != 1) {
                throw failure(
                        CatalogStorageException.Reason.SUBSCRIPTION_NOT_FOUND,
                        "Subscription does not exist.");
            }
        } finally {
            statement.close();
        }
    }

    static void writeEpisodes(
            SQLiteDatabase database,
            long subscriptionId,
            long generation,
            List<CatalogEpisode> episodes,
            long now,
            boolean autoDownloadNew) {
        SQLiteStatement insert = database.compileStatement(CatalogSql.INSERT_EPISODE);
        try {
            SQLiteStatement update = database.compileStatement(CatalogSql.UPDATE_EPISODE);
            try {
                for (CatalogEpisode episode : episodes) {
                    bindEpisodeUpdate(update, subscriptionId, generation, episode, now);
                    if (update.executeUpdateDelete() == 0) {
                        bindEpisodeInsert(insert, subscriptionId, generation,
                                episode, now, autoDownloadNew);
                        insert.execute();
                    }
                }
            } finally {
                update.close();
            }
        } finally {
            insert.close();
        }
        try (SQLiteStatement missing = database.compileStatement(CatalogSql.MARK_MISSING_EPISODES_STALE)) {
            missing.bindLong(1, subscriptionId);
            missing.bindLong(2, generation);
            missing.executeUpdateDelete();
        }
    }

    private static void bindEpisodeInsert(
            SQLiteStatement statement,
            long subscriptionId,
            long generation,
            CatalogEpisode episode,
            long now,
            boolean autoDownloadNew) {
        statement.clearBindings();
        int index = 1;
        statement.bindString(index++, episode.getMediaId());
        statement.bindLong(index++, subscriptionId);
        statement.bindString(index++, episode.getAudioUrl().toString());
        statement.bindString(index++, episode.getMimeType());
        statement.bindLong(index++, episode.getDeclaredLength());
        statement.bindString(index++, episode.getTitle());
        statement.bindString(index++, episode.getAuthor());
        statement.bindString(index++, episode.getNotes());
        statement.bindString(index++, optionalUrl(episode.getWebsiteUrl()));
        statement.bindString(index++, episode.getPublished());
        statement.bindLong(index++, CatalogPublishTime.epochMillis(episode.getPublished()));
        statement.bindLong(index++, episode.getDurationMillis());
        statement.bindString(index++, optionalUrl(episode.getChapterUrl()));
        statement.bindString(index++, joinCategories(episode.getCategories()));
        statement.bindLong(index++, episode.getSourceOrder());
        statement.bindLong(index++, generation);
        statement.bindLong(index++, generation);
        statement.bindLong(index++, autoDownloadNew ? 1L : 0L);
        statement.bindLong(index++, now);
        statement.bindLong(index, now);
    }

    private static void bindEpisodeUpdate(
            SQLiteStatement statement,
            long subscriptionId,
            long generation,
            CatalogEpisode episode,
            long now) {
        statement.clearBindings();
        int index = 1;
        statement.bindString(index++, episode.getAudioUrl().toString());
        statement.bindString(index++, episode.getMimeType());
        statement.bindLong(index++, episode.getDeclaredLength());
        statement.bindString(index++, episode.getTitle());
        statement.bindString(index++, episode.getAuthor());
        statement.bindString(index++, episode.getNotes());
        statement.bindString(index++, optionalUrl(episode.getWebsiteUrl()));
        statement.bindString(index++, episode.getPublished());
        statement.bindLong(index++, CatalogPublishTime.epochMillis(episode.getPublished()));
        statement.bindLong(index++, episode.getDurationMillis());
        statement.bindString(index++, optionalUrl(episode.getChapterUrl()));
        statement.bindString(index++, joinCategories(episode.getCategories()));
        statement.bindLong(index++, episode.getSourceOrder());
        statement.bindLong(index++, generation);
        statement.bindLong(index++, now);
        statement.bindString(index++, episode.getMediaId());
        statement.bindLong(index, subscriptionId);
    }

    private static String optionalUrl(CanonicalHttpsUrl url) {
        return url == null ? "" : url.toString();
    }

    private static String joinCategories(List<String> categories) {
        return String.join("\n", categories);
    }

    private static CatalogStorageException failure(CatalogStorageException.Reason reason, String message) {
        return new CatalogStorageException(reason, message);
    }
}
