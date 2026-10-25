package io.github.jnesew.plainpoddroid.catalog;

import io.github.jnesew.plainpoddroid.core.catalog.PodcastContext;
import io.github.jnesew.plainpoddroid.core.catalog.DownloadedEpisode;
import io.github.jnesew.plainpoddroid.core.catalog.DownloadedEpisodePage;
import io.github.jnesew.plainpoddroid.core.catalog.DownloadedEpisodeStore;
import io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore;
import io.github.jnesew.plainpoddroid.core.catalog.LibraryEpisodePage;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisodePage;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisodeSummary;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowserStore;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogPublishTime;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSummary;
import io.github.jnesew.plainpoddroid.core.catalog.storage.CatalogSql;
import io.github.jnesew.plainpoddroid.core.chapter.EpisodeChapterCatalog;
import io.github.jnesew.plainpoddroid.core.chapter.EpisodeChapterTarget;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadCatalog;
import io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadTarget;
import io.github.jnesew.plainpoddroid.core.download.AutomaticDownloadSettingsStore;
import io.github.jnesew.plainpoddroid.core.download.AutomaticDownloadQueue;
import io.github.jnesew.plainpoddroid.core.download.CompletedDownloadRetention;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeActionException;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeActionStore;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeStateMutation;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.FeedValidators;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.opml.OpmlDocument;
import io.github.jnesew.plainpoddroid.core.opml.OpmlImportPlan;
import io.github.jnesew.plainpoddroid.core.opml.OpmlMigrationStore;
import io.github.jnesew.plainpoddroid.core.playback.RemoteEpisodeCatalog;
import io.github.jnesew.plainpoddroid.core.playback.RemoteEpisode;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackProgressStore;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackProgressUpdate;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueEntry;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueException;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueMutation;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueStore;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshCandidate;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshFailure;
import io.github.jnesew.plainpoddroid.core.refresh.ScheduledRefreshStateStore;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionStore;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRemovalStore;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshTarget;

import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginProvider;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginCipher;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;
import io.github.jnesew.plainpoddroid.auth.AndroidFeedLoginKeys;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class EpisodeCatalogDatabase extends SQLiteOpenHelper
        implements CatalogBrowserStore, DownloadedEpisodeStore, EpisodeLibraryStore,
        RemoteEpisodeCatalog, SubscriptionStore,
        ScheduledRefreshStateStore, EpisodeDownloadCatalog, EpisodeQueueStore,
        EpisodeActionStore, PlaybackProgressStore, EpisodeChapterCatalog,
        OpmlMigrationStore, SubscriptionRemovalStore, AutomaticDownloadSettingsStore,
        AutomaticDownloadQueue, CompletedDownloadRetention, FeedLoginProvider,
        io.github.jnesew.plainpoddroid.core.playback.PodcastPlaybackSettingsStore, ManualDownloadQueue {
    private final AndroidFeedLoginKeys loginKeys = new AndroidFeedLoginKeys();
    private final FeedLoginCipher loginCipher = new FeedLoginCipher(loginKeys);
    private static final String DATABASE_NAME = "plainpoddroid-catalog.db";
    private static final int DATABASE_VERSION = 15;
    private static final Pattern MEDIA_ID = Pattern.compile("episode-[0-9a-f]{64}");

    public EpisodeCatalogDatabase(Context context) {
        super(Objects.requireNonNull(context, "context"), DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    // Session-local evidence, keyed only by opaque catalog ID; no URLs, realm or credentials retained.
    private final java.util.Set<Long> loginChallenges = new java.util.HashSet<>();

    @Override public synchronized void noteBasicChallenge(CanonicalHttpsUrl original, boolean audio) {
        try { LoginRow row=loginRow(original,audio);if(row!=null)loginChallenges.add(row.id()); }
        catch(IOException|RuntimeException ignored){ /* Optional presentation state cannot break acquisition. */ }
    }

    public synchronized io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState getPodcastLoginState(long id) throws IOException {
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT l.login_data IS NOT NULL FROM subscriptions s "
                +"LEFT JOIN subscription_logins l ON l.subscription_id=s.id WHERE s.id=? AND s.is_subscribed=1 LIMIT 1",
                new String[]{Long.toString(id)})){
            if(!cursor.moveToFirst())return io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState.NONE;
            if(loginChallenges.contains(id))return io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState.REQUIRED;
            return cursor.getInt(0)!=0?io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState.SAVED
                    :io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState.NONE;
        }catch(RuntimeException e){throw new IOException("Login state unavailable.");}
    }

    private record LoginRow(long id, CanonicalHttpsUrl owner, byte[] data, boolean blocked) { }

    private LoginRow loginRow(CanonicalHttpsUrl original, boolean audio) throws IOException {
        try (Cursor cursor = getReadableDatabase().rawQuery(audio ? CatalogSql.FIND_AUDIO_LOGIN : CatalogSql.FIND_FEED_LOGIN,
                new String[] {original.toString()})) {
            LoginRow result = null; int count = 0; boolean anyLogin = false;
            while (cursor.moveToNext()) {
                count++;
                byte[] data = cursor.isNull(2) ? null : cursor.getBlob(2);
                anyLogin |= data != null;
                if (result == null) result = new LoginRow(cursor.getLong(0), CanonicalHttpsUrl.parse(cursor.getString(1)),
                        data, cursor.getInt(audio ? 4 : 3) != 0);
            }
            if (count > 1 && anyLogin) throw new FeedLoginException();
            return count == 1 ? result : null;
        } catch (RuntimeException e) { throw new FeedLoginException(); }
    }

    private BasicFeedLogin findLogin(CanonicalHttpsUrl original, boolean audio) throws IOException {
        LoginRow row = loginRow(original, audio);
        if (row == null || row.data == null) return null;
        if (row.blocked) throw new FeedLoginException();
        return loginCipher.decrypt(row.owner, row.data);
    }
    @Override public synchronized BasicFeedLogin findFeedLogin(CanonicalHttpsUrl original) throws IOException {
        return findLogin(original, false);
    }
    @Override public synchronized BasicFeedLogin findAudioLogin(CanonicalHttpsUrl original) throws IOException {
        return findLogin(original, true);
    }
    @Override public synchronized void rejectLogin(CanonicalHttpsUrl original, boolean audio, BasicFeedLogin attempted)
            throws IOException {
        LoginRow row = loginRow(original, audio);
        if (row == null || row.data == null || !loginCipher.decrypt(row.owner, row.data).sameLogin(attempted)) return;
        try (SQLiteStatement statement = writableDatabase().compileStatement("UPDATE subscription_logins SET "
                + (audio ? "audio_blocked" : "feed_blocked") + " = 1 WHERE subscription_id = ? AND login_data = ?")) {
            statement.bindLong(1, row.id); statement.bindBlob(2, row.data); statement.executeUpdateDelete();
        } catch (RuntimeException e) { throw new FeedLoginException(); }
    }

    public synchronized BasicFeedLogin getPodcastLogin(long id) throws IOException {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT s.subscription_url, l.login_data FROM subscriptions s "
                + "LEFT JOIN subscription_logins l ON l.subscription_id=s.id WHERE s.id=? AND s.is_subscribed=1 LIMIT 1",
                new String[] {Long.toString(id)})) {
            if (!cursor.moveToFirst() || cursor.isNull(1)) return null;
            return loginCipher.decrypt(CanonicalHttpsUrl.parse(cursor.getString(0)), cursor.getBlob(1));
        } catch (RuntimeException e) { throw new FeedLoginException(); }
    }

    private void writeLogin(SQLiteDatabase database, long id, CanonicalHttpsUrl owner, BasicFeedLogin login) throws IOException {
        if (login == null) { database.delete("subscription_logins", "subscription_id=?", new String[] {Long.toString(id)}); return; }
        ContentValues values = new ContentValues(); values.put("subscription_id", id);
        values.put("login_data", loginCipher.encrypt(owner, login)); values.put("feed_blocked", 0); values.put("audio_blocked", 0);
        if (database.insertWithOnConflict("subscription_logins", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new FeedLoginException();
    }

    public synchronized void savePodcastLogin(long id, BasicFeedLogin login) throws IOException {
        SQLiteDatabase database = writableDatabase(); boolean started = false; CanonicalHttpsUrl owner = null;
        try {
            database.beginTransaction(); started = true;
            try (Cursor cursor = database.rawQuery("SELECT subscription_url FROM subscriptions WHERE id=? AND is_subscribed=1 LIMIT 1",
                    new String[] {Long.toString(id)})) {
                if (!cursor.moveToFirst()) throw new FeedLoginException();
                owner = CanonicalHttpsUrl.parse(cursor.getString(0));
                writeLogin(database, id, owner, login);
            }
            ContentValues values = new ContentValues(); values.put("refresh_failure_count", 0);
            values.put("last_refresh_attempt_at", 0); values.put("last_error", "");
            database.update("subscriptions", values, "id=?", new String[] {Long.toString(id)});
            database.setTransactionSuccessful();
        } catch (RuntimeException e) { throw new FeedLoginException(); }
        finally { if (started) database.endTransaction(); }
        loginChallenges.remove(id);
        if (login == null && owner != null) eraseLoginKey(owner);
    }

    private void eraseLoginKey(CanonicalHttpsUrl owner) {
        try { loginKeys.delete(owner); } catch (Exception unavailable) {
            // The credential row is already gone. A Keystore outage must not roll back removal.
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        super.onConfigure(database);
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        for (String statement : CatalogSql.createStatements()) {
            database.execSQL(statement);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        int currentVersion = oldVersion;
        if (currentVersion == 1 && newVersion >= 2) {
            for (String statement : CatalogSql.migrateVersion1To2Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 2;
        }
        if (currentVersion == 2 && newVersion >= 3) {
            for (String statement : CatalogSql.migrateVersion2To3Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 3;
        }
        if (currentVersion == 3 && newVersion >= 4) {
            for (String statement : CatalogSql.migrateVersion3To4Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 4;
        }
        if (currentVersion == 4 && newVersion >= 5) {
            for (String statement : CatalogSql.migrateVersion4To5Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 5;
        }
        if (currentVersion == 5 && newVersion >= 6) {
            for (String statement : CatalogSql.migrateVersion5To6Statements()) {
                database.execSQL(statement);
            }
            try (Cursor cursor = database.rawQuery(
                    CatalogSql.LIST_EPISODES_FOR_DATE_BACKFILL,
                    new String[] {Integer.toString(SecurityLimits.MAX_GLOBAL_EPISODES + 1)})) {
                SQLiteStatement update = database.compileStatement(
                        CatalogSql.UPDATE_EPISODE_PUBLISHED_AT);
                try {
                    int count = 0;
                    while (cursor.moveToNext()) {
                        if (++count > SecurityLimits.MAX_GLOBAL_EPISODES) {
                            throw new SQLiteException("Catalog exceeds date migration limit.");
                        }
                        update.clearBindings();
                        update.bindLong(1, CatalogPublishTime.epochMillis(cursor.getString(1)));
                        update.bindString(2, cursor.getString(0));
                        update.executeUpdateDelete();
                    }
                } finally {
                    update.close();
                }
            }
            currentVersion = 6;
        }
        if (currentVersion == 6 && newVersion >= 7) {
            for (String statement : CatalogSql.migrateVersion6To7Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 7;
        }
        if (currentVersion == 7 && newVersion >= 8) {
            for (String statement : CatalogSql.migrateVersion7To8Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 8;
        }
        if (currentVersion == 8 && newVersion >= 9) {
            for (String statement : CatalogSql.migrateVersion8To9Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 9;
        }
        if (currentVersion == 9 && newVersion >= 10) {
            for (String statement : CatalogSql.migrateVersion9To10Statements()) {
                database.execSQL(statement);
            }
            currentVersion = 10;
        }
        if (currentVersion == 10 && newVersion >= 11) {
            for (String statement : CatalogSql.migrateVersion10To11Statements()) database.execSQL(statement);
            currentVersion = 11;
        }
        if (currentVersion == 11 && newVersion >= 12) {
            for (String statement : CatalogSql.migrateVersion11To12Statements()) database.execSQL(statement);
            currentVersion = 12;
        }
        if (currentVersion == 12 && newVersion >= 13) {
            for (String statement : CatalogSql.migrateVersion12To13Statements()) database.execSQL(statement);
            currentVersion = 13;
        }
        if (currentVersion == 13 && newVersion >= 14) {
            for(String statement:CatalogSql.migrateVersion13To14Statements())database.execSQL(statement);
            currentVersion=14;
        }
        if(currentVersion==14&&newVersion>=15){
            for(String statement:CatalogSql.migrateVersion14To15Statements())database.execSQL(statement);
            currentVersion=15;
        }
        if (currentVersion == newVersion) {
            return;
        }
        throw new SQLiteException("No catalog migration exists for these versions.");
    }

    @Override
    public void onDowngrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new SQLiteException("Catalog downgrade is not supported.");
    }

    public synchronized long addConfirmedSubscription(
            CatalogSnapshot snapshot,
            FeedValidators validators)
            throws CatalogStorageException {
        return addConfirmedSubscription(snapshot, validators, null);
    }

    @Override
    public synchronized long addConfirmedSubscription(CatalogSnapshot snapshot,
            FeedValidators validators, BasicFeedLogin login) throws CatalogStorageException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(validators, "validators");
        validateValidatorResource(snapshot, validators);
        validateSnapshotSize(snapshot);
        SQLiteDatabase database = writableDatabase();
        boolean transactionStarted = false;
        try {
            database.beginTransaction();
            transactionStarted = true;
            SubscriptionRecord existing = findSubscription(
                    database, snapshot.getSubscriptionUrl().toString());
            if (existing != null && existing.subscribed) {
                throw failure(
                        CatalogStorageException.Reason.ALREADY_SUBSCRIBED,
                        "Subscription already exists.");
            }
            if (existing == null && count(database, "subscriptions", null, null)
                    >= SecurityLimits.MAX_SUBSCRIPTIONS) {
                throw failure(
                        CatalogStorageException.Reason.SUBSCRIPTION_LIMIT,
                        "Subscription limit was reached.");
            }

            long now = System.currentTimeMillis();
            long subscriptionId;
            long generation;
            if (existing == null) {
                subscriptionId = CatalogFeedWriter.insertSubscription(database, snapshot, validators, now);
                generation = 1L;
            } else {
                subscriptionId = existing.id;
                generation = Math.addExact(existing.generation, 1L);
                CatalogFeedWriter.updateSubscription(database, subscriptionId, snapshot, validators, now);
            }
            if (existing != null) {
                executeSingleLong(database,
                        CatalogSql.CLEAR_SUBSCRIPTION_AUTO_DOWNLOAD_PENDING,
                        subscriptionId);
            }
            CatalogFeedWriter.writeEpisodes(database, subscriptionId, generation,
                    snapshot.getEpisodes(), now, false);
            executeSingleLong(database, CatalogSql.RESET_SEEN_BASELINE, subscriptionId);
            verifyGenerationCount(database, subscriptionId, generation,
                    snapshot.getEpisodes().size());
            executeSingleLong(database, CatalogSql.CLEAR_STALE_AUTO_DOWNLOAD_PENDING,
                    subscriptionId);
            enforceEpisodeLimits(database, subscriptionId);
            try { writeLogin(database, subscriptionId, snapshot.getSubscriptionUrl(), login); }
            catch (IOException e) { throw failure(CatalogStorageException.Reason.STORAGE_FAILURE, "Podcast login could not be saved."); }
            database.setTransactionSuccessful();
            return subscriptionId;
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog write failed.");
        } finally {
            if (transactionStarted) {
                database.endTransaction();
            }
        }
    }

    public synchronized void refreshExistingSubscription(
            SubscriptionRefreshTarget target,
            CatalogSnapshot snapshot,
            FeedValidators validators)
            throws CatalogStorageException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(validators, "validators");
        validateValidatorResource(snapshot, validators);
        validateSnapshotSize(snapshot);
        SQLiteDatabase database = writableDatabase();
        boolean transactionStarted = false;
        try {
            database.beginTransaction();
            transactionStarted = true;
            SubscriptionRecord existing = findSubscription(
                    database,
                    snapshot.getSubscriptionUrl().toString());
            if (existing == null || !existing.subscribed
                    || !target.matches(existing.id,existing.generation,snapshot.getSubscriptionUrl())) {
                throw failure(
                        CatalogStorageException.Reason.SUBSCRIPTION_NOT_FOUND,
                        "Subscription does not exist.");
            }

            long now = System.currentTimeMillis();
            CatalogFeedWriter.updateSubscription(database, existing.id, snapshot, validators, now);
            long generation = Math.addExact(existing.generation, 1L);
            CatalogFeedWriter.writeEpisodes(database, existing.id, generation,
                    snapshot.getEpisodes(), now, existing.autoDownload);
            executeSingleLong(database, CatalogSql.ESTABLISH_SEEN_BASELINE, existing.id);
            verifyGenerationCount(
                    database,
                    existing.id,
                    generation,
                    snapshot.getEpisodes().size());
            executeSingleLong(database, CatalogSql.CLEAR_STALE_AUTO_DOWNLOAD_PENDING,
                    existing.id);
            enforceEpisodeLimits(database, existing.id);
            database.setTransactionSuccessful();
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog refresh failed.");
        } finally {
            if (transactionStarted) {
                database.endTransaction();
            }
        }
    }

    @Override
    public synchronized SubscriptionRefreshTarget findSubscriptionRefreshTarget(
            long subscriptionId)
            throws CatalogStorageException {
        if (subscriptionId < 1L) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.FIND_SUBSCRIPTION_REFRESH_TARGET,
                new String[] {Long.toString(subscriptionId)})) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            CanonicalHttpsUrl subscriptionUrl = CanonicalHttpsUrl.parse(cursor.getString(0));
            CanonicalHttpsUrl finalUrl = CanonicalHttpsUrl.parse(cursor.getString(1));
            FeedValidators validators = FeedValidators.of(
                    finalUrl,
                    cursor.getString(2),
                    cursor.getString(3));
            return new SubscriptionRefreshTarget(subscriptionId, subscriptionUrl, validators, cursor.getLong(4));
        } catch (NetworkPolicyException | SQLiteException | IllegalArgumentException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog refresh lookup failed.");
        }
    }

    @Override
    public synchronized void recordUnmodifiedSubscription(
            SubscriptionRefreshTarget target,
            FeedValidators validators) throws CatalogStorageException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(validators, "validators");
        CanonicalHttpsUrl resourceUrl = validators.getResourceUrl();
        CanonicalHttpsUrl expectedResourceUrl = target.getValidators().getResourceUrl();
        if (validators.isEmpty() || expectedResourceUrl == null
                || !expectedResourceUrl.equals(resourceUrl)) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog validator state was inconsistent.");
        }
        SQLiteStatement statement = writableDatabase().compileStatement(
                CatalogSql.RECORD_UNMODIFIED_SUBSCRIPTION);
        try {
            int index = 1;
            statement.bindString(index++, validators.getEtag());
            statement.bindString(index++, validators.getLastModified());
            statement.bindLong(index++, System.currentTimeMillis());
            statement.bindLong(index++, target.getSubscriptionId());
            statement.bindString(index++, target.getSubscriptionUrl().toString());
            statement.bindString(index++, resourceUrl.toString());
            statement.bindLong(index, target.getGeneration());
            if (statement.executeUpdateDelete() != 1) {
                throw failure(
                        CatalogStorageException.Reason.SUBSCRIPTION_NOT_FOUND,
                        "Subscription does not exist.");
            }
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog refresh state could not be saved.");
        } finally {
            statement.close();
        }
    }

    @Override
    public synchronized List<CatalogSubscriptionSummary> listSubscriptions()
            throws CatalogStorageException {
        List<CatalogSubscriptionSummary> result = new ArrayList<>();
        String limit = Integer.toString(SecurityLimits.MAX_SUBSCRIPTIONS + 1);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.LIST_SUBSCRIPTIONS,
                new String[] {limit})) {
            while (cursor.moveToNext()) {
                if (result.size() >= SecurityLimits.MAX_SUBSCRIPTIONS) {
                    throw failure(
                            CatalogStorageException.Reason.STORAGE_FAILURE,
                            "Catalog subscription limit is inconsistent.");
                }
                result.add(new CatalogSubscriptionSummary(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        checkedCount(cursor.getLong(4)),
                        checkedCount(cursor.getLong(5)),
                        checkedCount(cursor.getLong(6)),
                        cursor.getLong(3),
                        cursor.getInt(7) == 1,
                        cursor.getInt(8),
                        cursor.getLong(9),
                        cursor.getString(10).isEmpty() ? null
                                : CanonicalHttpsUrl.parse(cursor.getString(10)),
                        cursor.getInt(11) == 1,
                        CanonicalHttpsUrl.parse(cursor.getString(12)),
                        checkedCount(cursor.getLong(13)), cursor.getLong(14),
                        CatalogBrowseQuery.Order.values()[cursor.getInt(15)]));
            }
            return Collections.unmodifiableList(result);
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (SQLiteException | IllegalArgumentException
                | NetworkPolicyException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog subscription query failed.");
        }
    }

    @Override
    public synchronized void markSubscriptionViewed(long subscriptionId, long snapshotGeneration)
            throws CatalogStorageException {
        if (subscriptionId < 1L || snapshotGeneration < 1L) throw new IllegalArgumentException("Invalid viewed snapshot.");
        try (SQLiteStatement statement = writableDatabase().compileStatement(CatalogSql.MARK_SUBSCRIPTION_VIEWED)) {
            statement.bindLong(1, snapshotGeneration); statement.bindLong(2, subscriptionId);
            statement.executeUpdateDelete();
        } catch (SQLiteException e) { throw failure(CatalogStorageException.Reason.STORAGE_FAILURE, "Viewing state could not be saved."); }
    }

    public synchronized void setEpisodeOrder(long subscriptionId, CatalogBrowseQuery.Order order)
            throws CatalogStorageException {
        Objects.requireNonNull(order, "order");
        if (subscriptionId < 1L) throw new IllegalArgumentException("Invalid subscription.");
        try (SQLiteStatement statement = writableDatabase().compileStatement(CatalogSql.SET_EPISODE_ORDER)) {
            statement.bindLong(1, order.ordinal()); statement.bindLong(2, subscriptionId);
            if (statement.executeUpdateDelete() != 1) throw failure(CatalogStorageException.Reason.SUBSCRIPTION_NOT_FOUND, "Podcast not found.");
        } catch (SQLiteException e) { throw failure(CatalogStorageException.Reason.STORAGE_FAILURE, "Episode order could not be saved."); }
    }

    @Override
    public synchronized boolean setAutomaticDownload(long subscriptionId, boolean enabled)
            throws CatalogStorageException {
        if (subscriptionId < 1L) {
            throw new IllegalArgumentException("Invalid subscription ID.");
        }
        SQLiteDatabase database = writableDatabase();
        boolean started = false;
        try {
            database.beginTransaction();
            started = true;
            boolean changed;
            try (SQLiteStatement statement = database.compileStatement(
                    CatalogSql.SET_SUBSCRIPTION_AUTO_DOWNLOAD)) {
                statement.bindLong(1, enabled ? 1L : 0L);
                statement.bindLong(2, subscriptionId);
                changed = statement.executeUpdateDelete() == 1;
            }
            if (changed && !enabled) {
                executeSingleLong(database,
                        CatalogSql.CLEAR_SUBSCRIPTION_AUTO_DOWNLOAD_PENDING,
                        subscriptionId);
            }
            database.setTransactionSuccessful();
            return changed;
        } catch (RuntimeException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Automatic download setting could not be saved.");
        } finally {
            if (started) database.endTransaction();
        }
    }

    @Override
    public synchronized List<String> listAutomaticDownloadCandidates(int limit)
            throws CatalogStorageException {
        if (limit < 1 || limit > SecurityLimits.MAX_AUTOMATIC_DOWNLOADS_PER_JOB + 1) {
            throw new IllegalArgumentException("Invalid automatic download candidate limit.");
        }
        List<String> candidates = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.LIST_AUTOMATIC_DOWNLOAD_CANDIDATES,
                new String[] {Integer.toString(limit)})) {
            while (cursor.moveToNext()) {
                candidates.add(cursor.getString(0));
            }
            return Collections.unmodifiableList(candidates);
        } catch (SQLiteException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Automatic download candidates could not be read.");
        }
    }

    @Override
    public synchronized boolean isAutomaticDownloadCandidate(String mediaId)
            throws CatalogStorageException {
        requireAutomaticDownloadMediaId(mediaId);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.FIND_AUTOMATIC_DOWNLOAD_CANDIDATE,
                new String[] {mediaId})) {
            return cursor.moveToFirst();
        } catch (SQLiteException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Automatic download eligibility could not be read.");
        }
    }

    @Override
    public synchronized void clearAutomaticDownloadCandidate(String mediaId)
            throws CatalogStorageException {
        requireAutomaticDownloadMediaId(mediaId);
        try (SQLiteStatement statement = getWritableDatabase().compileStatement(
                CatalogSql.CLEAR_AUTOMATIC_DOWNLOAD_CANDIDATE)) {
            statement.bindString(1, mediaId);
            statement.executeUpdateDelete();
        } catch (SQLiteException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Automatic download state could not be cleared.");
        }
    }

    @Override
    public synchronized void recordAutomaticDownloadFailure(String mediaId)
            throws CatalogStorageException {
        requireAutomaticDownloadMediaId(mediaId);
        try (SQLiteStatement statement = getWritableDatabase().compileStatement(
                CatalogSql.RECORD_AUTOMATIC_DOWNLOAD_FAILURE)) {
            statement.bindString(1, mediaId);
            statement.executeUpdateDelete();
        } catch (SQLiteException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Automatic download attempt could not be saved.");
        }
    }

    @Override
    public synchronized OpmlImportPlan preview(OpmlDocument document)
            throws CatalogStorageException {
        Objects.requireNonNull(document, "document");
        try {
            return OpmlImportPlan.calculate(document,
                    currentSubscriptionUrls(getReadableDatabase()));
        } catch (SQLiteException | NetworkPolicyException | IllegalArgumentException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "OPML preview could not read the catalog.");
        }
    }

    @Override
    public synchronized OpmlImportPlan importConfirmed(OpmlDocument document)
            throws CatalogStorageException {
        Objects.requireNonNull(document, "document");
        SQLiteDatabase database = writableDatabase();
        boolean started = false;
        try {
            database.beginTransaction();
            started = true;
            OpmlImportPlan plan = OpmlImportPlan.calculate(document,
                    currentSubscriptionUrls(database));
            if (plan.exceedsSubscriptionLimit()) {
                throw failure(CatalogStorageException.Reason.SUBSCRIPTION_LIMIT,
                        "OPML import exceeds the subscription limit.");
            }
            long now = System.currentTimeMillis();
            for (OpmlDocument.Feed feed : plan.getAccepted()) {
                ContentValues values = new ContentValues();
                values.put("subscription_url", feed.getUrl().toString());
                values.put("final_feed_url", feed.getUrl().toString());
                values.put("title", feed.getLabel());
                values.put("author", "");
                values.put("notes", "");
                values.put("website_url", "");
                values.put("artwork_url", "");
                values.put("categories", "");
                values.put("created_at", now);
                values.put("updated_at", now);
                database.insertOrThrow("subscriptions", null, values);
            }
            database.setTransactionSuccessful();
            return plan;
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (SQLiteException | NetworkPolicyException | IllegalArgumentException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "OPML import could not be saved.");
        } finally {
            if (started) database.endTransaction();
        }
    }

    @Override
    public synchronized List<OpmlDocument.Feed> listForExport()
            throws CatalogStorageException {
        List<OpmlDocument.Feed> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("subscriptions",
                new String[] {"subscription_url", "title"}, "is_subscribed = 1",
                null, null, null,
                "id ASC", Integer.toString(SecurityLimits.MAX_SUBSCRIPTIONS + 1))) {
            while (cursor.moveToNext()) {
                if (result.size() >= SecurityLimits.MAX_SUBSCRIPTIONS) {
                    throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                            "Catalog subscription limit is inconsistent.");
                }
                result.add(new OpmlDocument.Feed(
                        CanonicalHttpsUrl.parse(cursor.getString(0)), cursor.getString(1)));
            }
            return Collections.unmodifiableList(result);
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (SQLiteException | NetworkPolicyException | IllegalArgumentException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "OPML export could not read the catalog.");
        }
    }

    /** Retains the episode rows, queue, and app-private files as a visible local archive. */
    public synchronized boolean archiveSubscription(long subscriptionId)
            throws CatalogStorageException {
        if (subscriptionId < 1L) {
            throw new IllegalArgumentException("Invalid subscription ID.");
        }
        SQLiteDatabase database = writableDatabase();
        boolean started = false; boolean archived = false; CanonicalHttpsUrl owner = null;
        try {
            database.beginTransaction();
            started = true;
            try (Cursor source = database.rawQuery("SELECT subscription_url FROM subscriptions WHERE id=? LIMIT 1",
                    new String[] {Long.toString(subscriptionId)})) {
                if (source.moveToFirst()) {
                    try { owner = CanonicalHttpsUrl.parse(source.getString(0)); } catch (IOException ignored) { }
                }
            }
            try (SQLiteStatement statement = database.compileStatement(
                    CatalogSql.ARCHIVE_SUBSCRIPTION)) {
                statement.bindLong(1, System.currentTimeMillis());
                statement.bindLong(2, subscriptionId);
                archived = statement.executeUpdateDelete() == 1;
            }
            if (archived) {
                database.delete("subscription_logins", "subscription_id = ?", new String[] {Long.toString(subscriptionId)});
                executeSingleLong(database,
                        CatalogSql.CLEAR_SUBSCRIPTION_AUTO_DOWNLOAD_PENDING,
                        subscriptionId);
            }
            database.setTransactionSuccessful();

        } catch (RuntimeException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Subscription could not be archived.");
        } finally {
            if (started) database.endTransaction();
        }
        if(archived)loginChallenges.remove(subscriptionId);
        if (archived && owner != null) eraseLoginKey(owner);
        return archived;
    }

    @Override
    public synchronized List<String> listArchivedMediaIds(long subscriptionId)
            throws CatalogStorageException {
        if (subscriptionId < 1L) {
            throw new IllegalArgumentException("Invalid subscription ID.");
        }
        SQLiteDatabase database = getReadableDatabase();
        try (Cursor archive = database.query("subscriptions", new String[] {"id"},
                "id = ? AND is_subscribed = 0", new String[] {Long.toString(subscriptionId)},
                null, null, null, "1")) {
            if (!archive.moveToFirst()) {
                return null;
            }
            List<String> mediaIds = new ArrayList<>();
            try (Cursor episodes = database.rawQuery(CatalogSql.LIST_ARCHIVED_MEDIA_IDS,
                    new String[] {Long.toString(subscriptionId),
                            Integer.toString(SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION + 1)})) {
                while (episodes.moveToNext()) {
                    if (mediaIds.size() >= SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION) {
                        throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                                "Archived episode limit was inconsistent.");
                    }
                    String mediaId = episodes.getString(0);
                    if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
                        throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                                "Archived media ID was invalid.");
                    }
                    mediaIds.add(mediaId);
                }
            }
            return Collections.unmodifiableList(mediaIds);
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Archived episodes could not be listed.");
        }
    }

    @Override
    public synchronized boolean deleteArchivedSubscription(long subscriptionId)
            throws CatalogStorageException {
        if (subscriptionId < 1L) {
            throw new IllegalArgumentException("Invalid subscription ID.");
        }
        SQLiteDatabase database = writableDatabase();
        boolean started = false;
        try {
            database.beginTransaction();
            started = true;
            try (Cursor archive = database.query("subscriptions", new String[] {"id"},
                    "id = ? AND is_subscribed = 0", new String[] {Long.toString(subscriptionId)},
                    null, null, null, "1")) {
                if (!archive.moveToFirst()) {
                    return false;
                }
            }
            executeSingleLong(database, CatalogSql.DELETE_ARCHIVED_QUEUE_ENTRIES,
                    subscriptionId);
            List<String> remaining = new ArrayList<>();
            try (Cursor queue = database.rawQuery(CatalogSql.LIST_EPISODE_QUEUE_POSITIONS,
                    new String[] {Integer.toString(SecurityLimits.MAX_QUEUE_ENTRIES + 1)})) {
                // Positions are renumbered below using their ordered media IDs.
                while (queue.moveToNext()) {
                    if (remaining.size() >= SecurityLimits.MAX_QUEUE_ENTRIES) {
                        throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                                "Queue exceeded its entry limit.");
                    }
                    remaining.add(queue.getString(1));
                }
            }
            SQLiteStatement move = database.compileStatement(CatalogSql.SET_EPISODE_QUEUE_POSITION);
            try {
                for (int position = 0; position < remaining.size(); position++) {
                    move.clearBindings();
                    move.bindLong(1, position);
                    move.bindString(2, remaining.get(position));
                    if (move.executeUpdateDelete() != 1) {
                        throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                                "Queue compaction failed.");
                    }
                }
            } finally {
                move.close();
            }
            SQLiteStatement delete = database.compileStatement(
                    CatalogSql.DELETE_ARCHIVED_SUBSCRIPTION);
            try {
                delete.bindLong(1, subscriptionId);
                if (delete.executeUpdateDelete() != 1) {
                    throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                            "Archived subscription changed during removal.");
                }
            } finally {
                delete.close();
            }
            database.setTransactionSuccessful();
            return true;
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Archived subscription could not be removed.");
        } finally {
            if (started) database.endTransaction();
        }
    }

    private static Set<CanonicalHttpsUrl> currentSubscriptionUrls(SQLiteDatabase database)
            throws NetworkPolicyException {
        Set<CanonicalHttpsUrl> urls = new HashSet<>();
        try (Cursor cursor = database.query("subscriptions",
                new String[] {"subscription_url"}, null, null, null, null,
                "id ASC", Integer.toString(SecurityLimits.MAX_SUBSCRIPTIONS + 1))) {
            while (cursor.moveToNext()) {
                if (urls.size() >= SecurityLimits.MAX_SUBSCRIPTIONS) {
                    throw new IllegalArgumentException("Catalog exceeds the subscription limit");
                }
                if (!urls.add(CanonicalHttpsUrl.parse(cursor.getString(0)))) {
                    throw new IllegalArgumentException("Catalog contains duplicate URLs");
                }
            }
        }
        return urls;
    }

    @Override
    public synchronized List<ScheduledRefreshCandidate> listScheduledRefreshCandidates()
            throws CatalogStorageException {
        List<ScheduledRefreshCandidate> result = new ArrayList<>();
        String limit = Integer.toString(SecurityLimits.MAX_SUBSCRIPTIONS + 1);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.LIST_SCHEDULED_REFRESH_CANDIDATES,
                new String[] {limit})) {
            while (cursor.moveToNext()) {
                if (result.size() >= SecurityLimits.MAX_SUBSCRIPTIONS) {
                    throw failure(
                            CatalogStorageException.Reason.STORAGE_FAILURE,
                            "Scheduled refresh candidate limit is inconsistent.");
                }
                result.add(new ScheduledRefreshCandidate(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getInt(2),
                        cursor.getLong(3)));
            }
            return Collections.unmodifiableList(result);
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (SQLiteException | IllegalArgumentException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Scheduled refresh state could not be read.");
        }
    }

    @Override
    public synchronized boolean recordScheduledRefreshFailure(
            ScheduledRefreshCandidate candidate,
            int newConsecutiveFailures,
            long attemptedAtMillis,
            ScheduledRefreshFailure failure) throws CatalogStorageException {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(failure, "failure");
        int expectedFailureCount = Math.min(
                candidate.getConsecutiveFailures() + 1,
                SecurityLimits.MAX_CONSECUTIVE_REFRESH_FAILURES);
        if (newConsecutiveFailures != expectedFailureCount || attemptedAtMillis < 1L) {
            throw new IllegalArgumentException("Invalid scheduled refresh failure update.");
        }
        SQLiteStatement statement = writableDatabase().compileStatement(
                CatalogSql.RECORD_SCHEDULED_REFRESH_FAILURE);
        try {
            int index = 1;
            statement.bindLong(index++, newConsecutiveFailures);
            statement.bindLong(index++, attemptedAtMillis);
            statement.bindString(index++, failure.name());
            statement.bindLong(index++, candidate.getSubscriptionId());
            statement.bindLong(index++, candidate.getUpdatedAtMillis());
            statement.bindLong(index++, candidate.getConsecutiveFailures());
            statement.bindLong(index, candidate.getLastAttemptAtMillis());
            return statement.executeUpdateDelete() == 1;
        } catch (RuntimeException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Scheduled refresh failure could not be saved.");
        } finally {
            statement.close();
        }
    }

    @Override
    public synchronized CatalogEpisodePage listEpisodes(
            long subscriptionId,
            int offset,
            int limit,
            CatalogBrowseQuery query) throws CatalogStorageException {
        Objects.requireNonNull(query, "query");
        if (subscriptionId < 1L
                || offset < 0
                || offset > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION
                || limit < 1
                || limit > SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid catalog browse request.");
        }
        try {
            SQLiteDatabase database = getReadableDatabase();
            long snapshotGeneration;
            CatalogBrowseQuery.Order order;
            try (Cursor settings = database.rawQuery("SELECT generation, episode_order FROM subscriptions WHERE id = ?",
                    new String[] {Long.toString(subscriptionId)})) {
                if (!settings.moveToFirst()) throw failure(CatalogStorageException.Reason.SUBSCRIPTION_NOT_FOUND,
                        "Subscription does not exist.");
                snapshotGeneration = settings.getLong(0);
                order = CatalogBrowseQuery.Order.values()[settings.getInt(1)];
            }
            List<CatalogEpisodeSummary> result = new ArrayList<>();
            boolean hasMore = false;
            try (Cursor cursor = database.rawQuery(
                    CatalogSql.listEpisodesSql(query.getFilter(), order),
                    new String[] {
                            Long.toString(subscriptionId),
                            query.getTitleSearch(),
                            query.getTitleSearch(),
                            Integer.toString(limit + 1),
                            Integer.toString(offset)
                    })) {
                while (cursor.moveToNext()) {
                    if (result.size() == limit) {
                        hasMore = true;
                        break;
                    }
                    result.add(readEpisodeSummary(cursor));
                }
            }
            return new CatalogEpisodePage(subscriptionId, offset, result, hasMore, snapshotGeneration);
        } catch (CatalogStorageException exception) {
            throw exception;
        } catch (SQLiteException | IllegalArgumentException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog episode query failed.");
        }
    }

    private static PodcastContext readPodcast(Cursor cursor, int first) {
        String artwork = cursor.getString(first + 2);
        try {
            return new PodcastContext(cursor.getLong(first), cursor.getString(first + 1),
                    artwork.isEmpty() ? null : CanonicalHttpsUrl.parse(artwork),
                    cursor.getInt(first + 3) == 1);
        } catch (NetworkPolicyException exception) {
            throw new IllegalArgumentException("Invalid podcast artwork metadata.");
        }
    }

    private static CatalogEpisodeSummary readEpisodeSummary(Cursor cursor) {
        return new CatalogEpisodeSummary(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getLong(3), cursor.getLong(4),
                cursor.getInt(5) == 1, cursor.getInt(6) == 1, cursor.getInt(7) == 1,
                cursor.getInt(8) == 1, cursor.getInt(9) == 1,
                cursor.getString(10), readPodcast(cursor, 11));
    }

    @Override
    public synchronized DownloadedEpisodePage listDownloadedEpisodes(int offset, int limit)
            throws CatalogStorageException {
        if (offset < 0 || offset > SecurityLimits.MAX_GLOBAL_EPISODES
                || limit < 1 || limit > SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid downloaded episode request.");
        }
        List<DownloadedEpisode> result = new ArrayList<>();
        boolean hasMore = false;
        try (Cursor cursor = getReadableDatabase().rawQuery(CatalogSql.LIST_DOWNLOADED_EPISODES,
                new String[] {Integer.toString(limit + 1), Integer.toString(offset)})) {
            while (cursor.moveToNext()) {
                if (result.size() == limit) { hasMore = true; break; }
                result.add(new DownloadedEpisode(readEpisodeSummary(cursor),
                        cursor.getLong(15), cursor.getLong(16)));
            }
            return new DownloadedEpisodePage(offset, result, hasMore);
        } catch (SQLiteException | IllegalArgumentException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Downloaded episode query failed.");
        }
    }

    @Override
    public synchronized LibraryEpisodePage listLibraryEpisodes(EpisodeLibraryStore.Filter filter,
            int offset, int limit) throws CatalogStorageException {
        Objects.requireNonNull(filter, "filter");
        if (offset < 0 || offset > SecurityLimits.MAX_GLOBAL_EPISODES
                || limit < 1 || limit > SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid library episode request.");
        }
        String sql = switch (filter) {
            case FAVORITES -> CatalogSql.LIST_FAVORITE_EPISODES;
            case HISTORY -> CatalogSql.LIST_LISTENING_HISTORY;
            case NEW_EPISODES -> CatalogSql.LIST_NEW_EPISODES;
            case IN_PROGRESS -> CatalogSql.LIST_IN_PROGRESS_EPISODES;
        };
        List<CatalogEpisodeSummary> result = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        boolean hasMore = false;
        try (Cursor cursor = getReadableDatabase().rawQuery(sql,
                new String[] {Integer.toString(limit + 1), Integer.toString(offset)})) {
            while (cursor.moveToNext()) {
                if (result.size() == limit) { hasMore = true; break; }
                result.add(readEpisodeSummary(cursor));
                times.add(filter == EpisodeLibraryStore.Filter.HISTORY ? cursor.getLong(15) : 0L);
            }
            return new LibraryEpisodePage(offset, result, hasMore, times);
        } catch (SQLiteException | IllegalArgumentException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Library episode query failed.");
        }
    }

    @Override
    public synchronized EpisodeDownloadTarget findEpisodeDownloadTarget(String mediaId)
            throws CatalogStorageException {
        return findDownloadTarget(mediaId, CatalogSql.FIND_EPISODE_DOWNLOAD_TARGET);
    }

    @Override
    public synchronized EpisodeDownloadTarget findEpisodeDeletionTarget(String mediaId)
            throws CatalogStorageException {
        return findDownloadTarget(mediaId, CatalogSql.FIND_EPISODE_DELETION_TARGET);
    }

    private EpisodeDownloadTarget findDownloadTarget(String mediaId, String sql)
            throws CatalogStorageException {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().rawQuery(
                sql,
                new String[] {mediaId})) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new EpisodeDownloadTarget(
                    mediaId,
                    CanonicalHttpsUrl.parse(cursor.getString(0)),
                    cursor.getString(1),
                    cursor.getLong(2),
                    cursor.getInt(3) == 1);
        } catch (NetworkPolicyException | SQLiteException | IllegalArgumentException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Episode download target could not be read.");
        }
    }

    @Override
    public synchronized boolean markEpisodeDownloadComplete(EpisodeDownloadTarget target, long downloadedBytes)
            throws CatalogStorageException {
        Objects.requireNonNull(target, "target");
        if (downloadedBytes <= 0L || downloadedBytes > SecurityLimits.MAX_DOWNLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid completed download size.");
        }
        SQLiteStatement statement = writableDatabase().compileStatement(
                CatalogSql.MARK_EPISODE_DOWNLOAD_COMPLETE);
        try {
            int index = 1;
            long now = System.currentTimeMillis();
            statement.bindLong(index++, now);
            statement.bindLong(index++, downloadedBytes);
            statement.bindLong(index++, now);
            statement.bindString(index++, target.getMediaId());
            statement.bindString(index++, target.getAudioUrl().toString());
            statement.bindString(index++, target.getMimeType());
            statement.bindLong(index, target.getDeclaredLength());
            return statement.executeUpdateDelete() == 1;
        } catch (RuntimeException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Completed download state could not be saved.");
        } finally {
            statement.close();
        }
    }

    @Override
    public synchronized void markEpisodeDownloadAbsent(String mediaId)
            throws CatalogStorageException {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            throw new IllegalArgumentException("Invalid media ID.");
        }
        SQLiteStatement statement = writableDatabase().compileStatement(
                CatalogSql.MARK_EPISODE_DOWNLOAD_ABSENT);
        try {
            statement.bindLong(1, System.currentTimeMillis());
            statement.bindString(2, mediaId);
            statement.executeUpdateDelete();
        } catch (RuntimeException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Deleted download state could not be saved.");
        } finally {
            statement.close();
        }
    }

    @Override
    public synchronized boolean deleteCompletedDownloadIfEligible(
            String mediaId, CompletedDownloadRetention.FileRemover remover) throws IOException {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            throw new IllegalArgumentException("Invalid media ID.");
        }
        Objects.requireNonNull(remover, "remover");
        SQLiteDatabase database = writableDatabase();
        database.beginTransaction();
        try {
            try (Cursor cursor = database.rawQuery(
                    CatalogSql.FIND_COMPLETED_DOWNLOAD_FOR_RETENTION,
                    new String[] {mediaId})) {
                if (!cursor.moveToFirst()) {
                    database.setTransactionSuccessful();
                    return false;
                }
            }
            // File-first deletion retains retryable catalog state on an I/O failure.
            // Playback always verifies the file and marker, including after partial deletion.
            remover.delete(mediaId);
            try (SQLiteStatement statement = database.compileStatement(
                    CatalogSql.MARK_RETAINED_DOWNLOAD_ABSENT)) {
                statement.bindLong(1, System.currentTimeMillis());
                statement.bindString(2, mediaId);
                if (statement.executeUpdateDelete() != 1) {
                    throw new IOException("Completed download state changed during cleanup.");
                }
            }
            database.setTransactionSuccessful();
            return true;
        } catch (SQLiteException exception) {
            throw failure(CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Completed download cleanup could not be saved.");
        } finally {
            database.endTransaction();
        }
    }

    @Override
    public synchronized EpisodeChapterTarget findChapterTarget(String mediaId) throws IOException {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            throw new IllegalArgumentException("Invalid media ID.");
        }
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.FIND_EPISODE_CHAPTER_TARGET, new String[] {mediaId})) {
            if (!cursor.moveToFirst()) { return null; }
            return new EpisodeChapterTarget(cursor.getString(0),
                    CanonicalHttpsUrl.parse(cursor.getString(1)));
        } catch (NetworkPolicyException | RuntimeException exception) {
            throw new IOException("Chapter reference could not be read.");
        }
    }

    @Override
    public synchronized EpisodeDetails findDetails(String mediaId)
            throws EpisodeActionException {
        requireEpisodeActionMediaId(mediaId);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.FIND_EPISODE_DETAILS,
                new String[] {mediaId})) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            String websiteValue = cursor.getString(4);
            CanonicalHttpsUrl websiteUrl = websiteValue.isEmpty()
                    ? null
                    : CanonicalHttpsUrl.parse(websiteValue);
            return new EpisodeDetails(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    websiteUrl,
                    cursor.getString(5),
                    cursor.getLong(6),
                    cursor.getLong(7),
                    cursor.getInt(8) == 1,
                    cursor.getInt(9) == 1,
                    cursor.getInt(10) == 1,
                    cursor.getInt(11) == 1,
                    cursor.getInt(12) == 1,
                    cursor.getInt(13) == 1, readPodcast(cursor, 14));
        } catch (NetworkPolicyException | RuntimeException exception) {
            throw episodeActionFailure(
                    EpisodeActionException.Reason.STORAGE_FAILURE,
                    "Episode details could not be read.");
        }
    }

    @Override
    public synchronized EpisodeStateMutation setPlayed(String mediaId, boolean played)
            throws EpisodeActionException {
        requireEpisodeActionMediaId(mediaId);
        SQLiteStatement statement;
        try {
            statement = getWritableDatabase().compileStatement(
                    CatalogSql.SET_EPISODE_PLAYED);
        } catch (RuntimeException exception) {
            throw episodeActionFailure(
                    EpisodeActionException.Reason.STORAGE_FAILURE,
                    "Played state could not be prepared.");
        }
        try {
            long value = played ? 1L : 0L;
            statement.bindLong(1, value);
            statement.bindLong(2, value);
            statement.bindLong(3, System.currentTimeMillis());
            statement.bindString(4, mediaId);
            statement.bindLong(5, value);
            statement.bindLong(6, value);
            if (statement.executeUpdateDelete() == 1) {
                return EpisodeStateMutation.UPDATED;
            }
            return existingEpisodeMutationResult(mediaId);
        } catch (EpisodeActionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw episodeActionFailure(
                    EpisodeActionException.Reason.STORAGE_FAILURE,
                    "Played state could not be saved.");
        } finally {
            statement.close();
        }
    }

    @Override
    public synchronized EpisodeStateMutation setFavorite(
            String mediaId,
            boolean favorite) throws EpisodeActionException {
        requireEpisodeActionMediaId(mediaId);
        SQLiteStatement statement;
        try {
            statement = getWritableDatabase().compileStatement(
                    CatalogSql.SET_EPISODE_FAVORITE);
        } catch (RuntimeException exception) {
            throw episodeActionFailure(
                    EpisodeActionException.Reason.STORAGE_FAILURE,
                    "Favorite state could not be prepared.");
        }
        try {
            long value = favorite ? 1L : 0L;
            statement.bindLong(1, value);
            statement.bindLong(2, System.currentTimeMillis());
            statement.bindString(3, mediaId);
            statement.bindLong(4, value);
            if (statement.executeUpdateDelete() == 1) {
                return EpisodeStateMutation.UPDATED;
            }
            return existingEpisodeMutationResult(mediaId);
        } catch (EpisodeActionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw episodeActionFailure(
                    EpisodeActionException.Reason.STORAGE_FAILURE,
                    "Favorite state could not be saved.");
        } finally {
            statement.close();
        }
    }

    @Override
    public synchronized void savePlaybackProgress(PlaybackProgressUpdate update)
            throws IOException {
        Objects.requireNonNull(update, "update");
        SQLiteStatement statement;
        try {
            statement = getWritableDatabase().compileStatement(
                    CatalogSql.SAVE_PLAYBACK_PROGRESS);
        } catch (RuntimeException exception) {
            throw new IOException("Playback progress could not be prepared.");
        }
        try {
            long markPlayed = update.shouldMarkPlayed() ? 1L : 0L;
            statement.bindLong(1, update.getPositionMillis());
            statement.bindLong(2, markPlayed);
            statement.bindLong(3, update.getUpdatedAtMillis());
            statement.bindString(4, update.getMediaId());
            statement.bindLong(5, update.getPositionMillis());
            statement.bindLong(6, markPlayed);
            statement.executeUpdateDelete();
        } catch (RuntimeException exception) {
            throw new IOException("Playback progress could not be saved.");
        } finally {
            statement.close();
        }
    }

    @Override public synchronized io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting
            findSpeedForEpisode(String mediaId) throws IOException {
        requireEpisodeActionMediaId(mediaId);
        try(Cursor cursor=getReadableDatabase().rawQuery(CatalogSql.FIND_EPISODE_SPEED,new String[]{mediaId})) {
            return !cursor.moveToFirst()||cursor.isNull(0)?null:
                    io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting.fromPercent(cursor.getInt(0));
        }catch(RuntimeException e){throw new IOException("Podcast speed could not be read.");}
    }
    @Override public synchronized io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting
            getPodcastSpeed(long subscriptionId) throws IOException {
        if(subscriptionId<1)throw new IllegalArgumentException("Invalid podcast.");
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT speed_percent FROM podcast_playback_settings WHERE subscription_id=?",
                new String[]{Long.toString(subscriptionId)})) {
            return !cursor.moveToFirst()?null:io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting.fromPercent(cursor.getInt(0));
        }catch(RuntimeException e){throw new IOException("Podcast speed could not be read.");}
    }
    @Override public synchronized void setPodcastSpeed(long subscriptionId,
            io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting speed)throws IOException {
        if(subscriptionId<1)throw new IllegalArgumentException("Invalid podcast.");
        try {
            SQLiteDatabase db=writableDatabase();
            if(speed==null){db.delete("podcast_playback_settings","subscription_id=?",new String[]{Long.toString(subscriptionId)});return;}
            ContentValues values=new ContentValues();values.put("subscription_id",subscriptionId);values.put("speed_percent",speed.getPercent());
            if(db.insertWithOnConflict("podcast_playback_settings",null,values,SQLiteDatabase.CONFLICT_REPLACE)==-1)
                throw new IOException("Podcast speed could not be saved.");
        }catch(RuntimeException e){throw new IOException("Podcast speed could not be saved.");}
    }

    @Override public synchronized ManualDownloadQueue.Enqueued enqueueManualDownload(String mediaId)throws IOException {
        ManualDownloadQueue.requireMediaId(mediaId);SQLiteDatabase db=writableDatabase();boolean started=false;
        try {db.beginTransaction();started=true;
            int state=-1;
            try(Cursor c=db.rawQuery("SELECT state FROM manual_download_requests WHERE media_id=?",new String[]{mediaId})){
                if(c.moveToFirst())state=c.getInt(0);
            }
            if(state==0||state==1)return ManualDownloadQueue.Enqueued.EXISTING;
            try(Cursor c=db.rawQuery("SELECT e.downloaded,s.is_subscribed FROM episodes e JOIN subscriptions s ON s.id=e.subscription_id WHERE e.media_id=? LIMIT 1",new String[]{mediaId})){
                if(!c.moveToFirst()||c.getInt(1)!=1)return ManualDownloadQueue.Enqueued.UNAVAILABLE;
                if(c.getInt(0)==1)return ManualDownloadQueue.Enqueued.COMPLETE;
            }
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM manual_download_requests",null)){
                if(!c.moveToFirst())throw new IOException("Downloads could not be read.");
                if(state<0&&c.getLong(0)>=SecurityLimits.MAX_PENDING_MANUAL_DOWNLOADS)return ManualDownloadQueue.Enqueued.FULL;
            }
            if(state==2)db.delete("manual_download_requests","media_id=?",new String[]{mediaId});
            ContentValues values=new ContentValues();values.put("media_id",mediaId);
            db.insertOrThrow("manual_download_requests",null,values);db.setTransactionSuccessful();return ManualDownloadQueue.Enqueued.ADDED;
        } catch(RuntimeException e){throw new IOException("Download request could not be saved.");}
        finally {if(started)db.endTransaction();}
    }
    @Override public synchronized List<ManualDownloadQueue.Entry> listManualDownloads()throws IOException {
        List<ManualDownloadQueue.Entry> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(CatalogSql.LIST_MANUAL_DOWNLOADS,
                new String[]{Integer.toString(SecurityLimits.MAX_PENDING_MANUAL_DOWNLOADS+1)})){
            while(c.moveToNext()){
                if(result.size()>=SecurityLimits.MAX_PENDING_MANUAL_DOWNLOADS)throw new IOException("Download request limit was exceeded.");
                result.add(new ManualDownloadQueue.Entry(new ManualDownloadQueue.Request(c.getLong(0),c.getString(1),c.getLong(2)),
                        ManualDownloadQueue.State.values()[c.getInt(3)],c.isNull(4)?null:EpisodeDownloadException.Reason.valueOf(c.getString(4)),c.getString(5),c.getString(6)));
            }
            return List.copyOf(result);
        }catch(RuntimeException e){throw new IOException("Download requests could not be read.");}
    }
    @Override public synchronized void recoverInterruptedManualDownloads()throws IOException {
        executeManualSql(CatalogSql.RECOVER_MANUAL_DOWNLOADS,new Object[0]);
    }
    @Override public synchronized ManualDownloadQueue.Request claimManualDownload()throws IOException {
        SQLiteDatabase db=writableDatabase();boolean started=false;
        try {db.beginTransaction();started=true;
            try(SQLiteStatement stmt=db.compileStatement(CatalogSql.CLAIM_MANUAL_DOWNLOAD)){
                if(stmt.executeUpdateDelete()==0){db.setTransactionSuccessful();return null;}
            }
            try(Cursor c=db.rawQuery("SELECT request_id,media_id,attempt FROM manual_download_requests WHERE state=1 LIMIT 1",null)){
                if(!c.moveToFirst())throw new IOException("Download request could not be claimed.");
                var result=new ManualDownloadQueue.Request(c.getLong(0),c.getString(1),c.getLong(2));db.setTransactionSuccessful();return result;
            }
        }catch(RuntimeException e){throw new IOException("Download request could not be claimed.");}
        finally {if(started)db.endTransaction();}
    }
    private static String[] manualArgs(ManualDownloadQueue.Request r){
        return new String[]{Long.toString(r.id()),r.mediaId(),Long.toString(r.attempt())};
    }
    @Override public synchronized boolean canRunManualDownload(ManualDownloadQueue.Request r)throws IOException {
        return manualExists(CatalogSql.RUNNABLE_MANUAL_DOWNLOAD,r);
    }
    private boolean manualExists(String sql,ManualDownloadQueue.Request r)throws IOException {
        Objects.requireNonNull(r);
        try(Cursor c=getReadableDatabase().rawQuery(sql,manualArgs(r))){return c.moveToFirst();}
        catch(RuntimeException e){throw new IOException("Download request could not be read.");}
    }
    @Override public synchronized void finishManualDownload(ManualDownloadQueue.Request r,EpisodeDownloadException.Reason failure)throws IOException {
        if(failure==null)executeManualSql(CatalogSql.COMPLETE_MANUAL_DOWNLOAD,manualArgs(r));
        else executeManualSql(CatalogSql.FAIL_MANUAL_DOWNLOAD,new Object[]{failure.name(),r.id(),r.mediaId(),r.attempt()});
    }
    @Override public synchronized void deferManualDownload(ManualDownloadQueue.Request r)throws IOException {
        executeManualSql(CatalogSql.DEFER_MANUAL_DOWNLOAD,manualArgs(r));
    }
    @Override public synchronized void cancelManualDownload(String mediaId)throws IOException {
        ManualDownloadQueue.requireMediaId(mediaId);executeManualSql("DELETE FROM manual_download_requests WHERE media_id=?",new Object[]{mediaId});
    }
    @Override public synchronized void clearCompletedManualDownloads()throws IOException {
        executeManualSql("DELETE FROM manual_download_requests WHERE media_id IN (SELECT media_id FROM episodes WHERE downloaded=1)",new Object[0]);
    }
    @Override public synchronized void clearPendingManualDownloads()throws IOException {
        executeManualSql("DELETE FROM manual_download_requests WHERE state IN (0,1)",new Object[0]);
    }
    @Override public synchronized void failPendingManualDownloads(EpisodeDownloadException.Reason reason)throws IOException {
        executeManualSql("UPDATE manual_download_requests SET state=2,failure=? WHERE state IN (0,1)",new Object[]{Objects.requireNonNull(reason).name()});
    }
    @Override public synchronized boolean hasWaitingManualDownloads()throws IOException {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM manual_download_requests WHERE state=0 LIMIT 1",null)){return c.moveToFirst();}
        catch(RuntimeException e){throw new IOException("Download requests could not be read.");}
    }
    private void executeManualSql(String sql,Object[] args)throws IOException {
        try {writableDatabase().execSQL(sql,args);}catch(RuntimeException e){throw new IOException("Download requests could not be saved.");}
    }

    /** A selected group keeps visible order, including items already in the queue. */
    public synchronized int queueBatch(io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest request,
            boolean playNext)throws IOException {
        Objects.requireNonNull(request);SQLiteDatabase db=writableDatabase();boolean started=false;
        try {db.beginTransaction();started=true;
            var ids=new ArrayList<>(request.mediaIds());
            if(playNext){
                Collections.reverse(ids);for(String id:ids)playNext(id);
            }else{
                int size=validateQueueState(db);
                for(String id:ids){if(append(id)==EpisodeQueueMutation.ADDED)size++;move(id,size-1);}
            }
            // SQLite's outer transaction also rolls back successful nested mutations
            // when a later target is missing, capacity is exhausted or a write fails.
            db.setTransactionSuccessful();return ids.size();
        }catch(RuntimeException e){throw new IOException("Queue could not be saved.");}
        finally{if(started)db.endTransaction();}
    }

    public synchronized int setPlayedBatch(io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest request,
            boolean played)throws IOException {
        Objects.requireNonNull(request);SQLiteDatabase db=writableDatabase();boolean started=false;
        try{db.beginTransaction();started=true;
            for(String id:request.mediaIds())setPlayed(id,played);
            db.setTransactionSuccessful();return request.mediaIds().size();
        }catch(RuntimeException e){throw new IOException("Played state could not be saved.");}
        finally{if(started)db.endTransaction();}
    }

    public synchronized io.github.jnesew.plainpoddroid.core.episode.PodcastPlayedBatch preparePlayedBatch(
            long subscriptionId,String olderThanMediaId)throws IOException {
        if(subscriptionId<1)throw new IllegalArgumentException("Invalid podcast.");
        if(olderThanMediaId!=null)requireEpisodeActionMediaId(olderThanMediaId);
        try{
            SQLiteDatabase db=getReadableDatabase();long generation,before=0;
            try(Cursor c=db.rawQuery("SELECT generation FROM subscriptions WHERE id=?",new String[]{Long.toString(subscriptionId)})){
                if(!c.moveToFirst())throw new IOException("Podcast not found.");generation=c.getLong(0);
            }
            if(olderThanMediaId!=null){
                try(Cursor c=db.rawQuery("SELECT published_at FROM episodes WHERE media_id=? AND subscription_id=?",
                        new String[]{olderThanMediaId,Long.toString(subscriptionId)})){
                    if(!c.moveToFirst()||c.getLong(0)==0)return null;before=c.getLong(0);
                }
            }
            String[] args={Long.toString(subscriptionId),Long.toString(generation),Long.toString(before),Long.toString(before)};
            try(Cursor c=db.rawQuery(CatalogSql.COUNT_PODCAST_PLAYED_BATCH,args)){
                if(!c.moveToFirst())throw new IOException("Episodes could not be counted.");
                return new io.github.jnesew.plainpoddroid.core.episode.PodcastPlayedBatch(subscriptionId,generation,before,checkedCount(c.getLong(0)));
            }
        }catch(RuntimeException e){throw new IOException("Episodes could not be read.");}
    }

    public synchronized int markPodcastPlayed(io.github.jnesew.plainpoddroid.core.episode.PodcastPlayedBatch scope)throws IOException {
        Objects.requireNonNull(scope);
        try(SQLiteStatement s=writableDatabase().compileStatement(CatalogSql.MARK_PODCAST_PLAYED_BATCH)){
            s.bindLong(1,System.currentTimeMillis());s.bindLong(2,scope.subscriptionId());s.bindLong(3,scope.generation());
            s.bindLong(4,scope.beforePublishedAtMillis());s.bindLong(5,scope.beforePublishedAtMillis());return s.executeUpdateDelete();
        }catch(RuntimeException e){throw new IOException("Played state could not be saved.");}
    }

    @Override public synchronized void recordListening(String mediaId, long listenedAtMillis) throws IOException {
        requireEpisodeActionMediaId(mediaId);
        if (listenedAtMillis < 0L) throw new IllegalArgumentException("Invalid listening time.");
        try (SQLiteStatement statement = writableDatabase().compileStatement(CatalogSql.RECORD_LISTENING)) {
            statement.bindLong(1, listenedAtMillis); statement.bindString(2, mediaId);
            statement.bindLong(3, listenedAtMillis); statement.executeUpdateDelete();
        } catch (RuntimeException e) { throw new IOException("Listening history could not be saved."); }
    }

    @Override
    public synchronized List<EpisodeQueueEntry> listQueue() throws EpisodeQueueException {
        List<EpisodeQueueEntry> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                CatalogSql.LIST_EPISODE_QUEUE,
                new String[] {Integer.toString(SecurityLimits.MAX_QUEUE_ENTRIES + 1)})) {
            while (cursor.moveToNext()) {
                if (result.size() >= SecurityLimits.MAX_QUEUE_ENTRIES) {
                    throw queueFailure(
                            EpisodeQueueException.Reason.CORRUPT_STATE,
                            "Queue exceeded its entry limit.");
                }
                EpisodeQueueEntry entry = new EpisodeQueueEntry(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getLong(4),
                        cursor.getInt(5) == 1,
                        cursor.getInt(6) == 1,
                        cursor.getInt(7) == 1,
                        cursor.getInt(8), readPodcast(cursor, 9));
                if (entry.getPosition() != result.size()) {
                    throw queueFailure(
                            EpisodeQueueException.Reason.CORRUPT_STATE,
                            "Queue positions were inconsistent.");
                }
                result.add(entry);
            }
            return Collections.unmodifiableList(result);
        } catch (EpisodeQueueException exception) {
            throw exception;
        } catch (SQLiteException | IllegalArgumentException exception) {
            throw queueFailure(
                    EpisodeQueueException.Reason.STORAGE_FAILURE,
                    "Queue could not be read.");
        }
    }

    @Override
    public synchronized EpisodeQueueMutation append(String mediaId)
            throws EpisodeQueueException {
        requireMediaId(mediaId);
        return mutateQueue((database, queueSize) -> {
            if (findQueuePosition(database, mediaId) != null) {
                return EpisodeQueueMutation.UNCHANGED;
            }
            requireEpisode(database, mediaId);
            if (queueSize >= SecurityLimits.MAX_QUEUE_ENTRIES) {
                throw queueFailure(
                        EpisodeQueueException.Reason.QUEUE_FULL,
                        "Queue entry limit was reached.");
            }
            insertQueueEntry(database, mediaId, queueSize);
            return EpisodeQueueMutation.ADDED;
        });
    }

    @Override
    public synchronized EpisodeQueueMutation playNext(String mediaId)
            throws EpisodeQueueException {
        requireMediaId(mediaId);
        return mutateQueue((database, queueSize) -> {
            Integer currentPosition = findQueuePosition(database, mediaId);
            if (currentPosition != null) {
                if (currentPosition == 0) {
                    return EpisodeQueueMutation.UNCHANGED;
                }
                moveQueueEntry(database, mediaId, currentPosition, 0);
                return EpisodeQueueMutation.MOVED;
            }
            requireEpisode(database, mediaId);
            if (queueSize >= SecurityLimits.MAX_QUEUE_ENTRIES) {
                throw queueFailure(
                        EpisodeQueueException.Reason.QUEUE_FULL,
                        "Queue entry limit was reached.");
            }
            executeQueueUpdate(database, CatalogSql.SHIFT_EPISODE_QUEUE_FOR_FIRST);
            insertQueueEntry(database, mediaId, 0);
            return EpisodeQueueMutation.ADDED;
        });
    }

    @Override
    public synchronized EpisodeQueueMutation remove(String mediaId)
            throws EpisodeQueueException {
        requireMediaId(mediaId);
        return mutateQueue((database, queueSize) -> {
            Integer currentPosition = findQueuePosition(database, mediaId);
            if (currentPosition == null) {
                throw queueFailure(
                        EpisodeQueueException.Reason.NOT_QUEUED,
                        "Episode was not queued.");
            }
            SQLiteStatement delete = database.compileStatement(
                    CatalogSql.DELETE_EPISODE_QUEUE);
            try {
                delete.bindString(1, mediaId);
                if (delete.executeUpdateDelete() != 1) {
                    throw queueFailure(
                            EpisodeQueueException.Reason.CORRUPT_STATE,
                            "Queue delete affected an unexpected number of rows.");
                }
            } finally {
                delete.close();
            }
            executeQueueUpdate(
                    database,
                    CatalogSql.SHIFT_EPISODE_QUEUE_AFTER_REMOVE,
                    currentPosition);
            return EpisodeQueueMutation.REMOVED;
        });
    }

    @Override
    public synchronized EpisodeQueueMutation move(String mediaId, int targetPosition)
            throws EpisodeQueueException {
        requireMediaId(mediaId);
        if (targetPosition < 0 || targetPosition >= SecurityLimits.MAX_QUEUE_ENTRIES) {
            throw queueFailure(
                    EpisodeQueueException.Reason.INVALID_POSITION,
                    "Queue position was invalid.");
        }
        return mutateQueue((database, queueSize) -> {
            Integer currentPosition = findQueuePosition(database, mediaId);
            if (currentPosition == null) {
                throw queueFailure(
                        EpisodeQueueException.Reason.NOT_QUEUED,
                        "Episode was not queued.");
            }
            if (targetPosition >= queueSize) {
                throw queueFailure(
                        EpisodeQueueException.Reason.INVALID_POSITION,
                        "Queue position was outside the current queue.");
            }
            if (currentPosition == targetPosition) {
                return EpisodeQueueMutation.UNCHANGED;
            }
            moveQueueEntry(
                    database,
                    mediaId,
                    currentPosition,
                    targetPosition);
            return EpisodeQueueMutation.MOVED;
        });
    }

    @Override
    public synchronized RemoteEpisode findRemoteEpisode(String mediaId) {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().query(
                "episodes AS e INNER JOIN subscriptions AS s ON s.id = e.subscription_id",
                new String[] {
                        "e.audio_url",
                        "e.mime_type",
                        "e.title",
                        "e.author",
                        "e.playback_position_ms",
                        "s.is_subscribed"
                },
                "e.media_id = ?",
                new String[] {mediaId},
                null,
                null,
                null,
                "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new RemoteEpisode(
                    mediaId,
                    CanonicalHttpsUrl.parse(cursor.getString(0)).toUri(),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getLong(4),
                    cursor.getInt(5) == 1);
        } catch (NetworkPolicyException | SQLiteException | IllegalArgumentException exception) {
            throw new IllegalStateException("Catalog lookup failed.");
        }
    }

    private EpisodeQueueMutation mutateQueue(QueueMutationAction action)
            throws EpisodeQueueException {
        SQLiteDatabase database;
        try {
            database = getWritableDatabase();
        } catch (RuntimeException exception) {
            throw queueFailure(
                    EpisodeQueueException.Reason.STORAGE_FAILURE,
                    "Queue database could not be opened.");
        }
        boolean transactionStarted = false;
        try {
            database.beginTransaction();
            transactionStarted = true;
            int before = validateQueueState(database);
            EpisodeQueueMutation mutation = action.apply(database, before);
            if (mutation == null || mutation == EpisodeQueueMutation.NONE) {
                throw queueFailure(
                        EpisodeQueueException.Reason.CORRUPT_STATE,
                        "Queue mutation returned an invalid result.");
            }
            int after = validateQueueState(database);
            int expectedAfter;
            switch (mutation) {
                case ADDED:
                    expectedAfter = before + 1;
                    break;
                case REMOVED:
                    expectedAfter = before - 1;
                    break;
                case MOVED:
                case UNCHANGED:
                    expectedAfter = before;
                    break;
                case NONE:
                default:
                    throw new AssertionError("Invalid queue mutation.");
            }
            if (after != expectedAfter) {
                throw queueFailure(
                        EpisodeQueueException.Reason.CORRUPT_STATE,
                        "Queue mutation changed an unexpected number of entries.");
            }
            database.setTransactionSuccessful();
            return mutation;
        } catch (EpisodeQueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw queueFailure(
                    EpisodeQueueException.Reason.STORAGE_FAILURE,
                    "Queue mutation failed.");
        } finally {
            if (transactionStarted) {
                database.endTransaction();
            }
        }
    }

    private static int validateQueueState(SQLiteDatabase database)
            throws EpisodeQueueException {
        int expectedPosition = 0;
        try (Cursor cursor = database.rawQuery(
                CatalogSql.LIST_EPISODE_QUEUE_POSITIONS,
                new String[] {Integer.toString(SecurityLimits.MAX_QUEUE_ENTRIES + 1)})) {
            while (cursor.moveToNext()) {
                if (expectedPosition >= SecurityLimits.MAX_QUEUE_ENTRIES
                        || cursor.getInt(0) != expectedPosition) {
                    throw queueFailure(
                            EpisodeQueueException.Reason.CORRUPT_STATE,
                            "Queue positions were inconsistent.");
                }
                expectedPosition++;
            }
            return expectedPosition;
        } catch (EpisodeQueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw queueFailure(
                    EpisodeQueueException.Reason.STORAGE_FAILURE,
                    "Queue state could not be validated.");
        }
    }

    private static Integer findQueuePosition(SQLiteDatabase database, String mediaId) {
        try (Cursor cursor = database.rawQuery(
                CatalogSql.FIND_EPISODE_QUEUE_POSITION,
                new String[] {mediaId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : null;
        }
    }

    private static void requireEpisode(SQLiteDatabase database, String mediaId)
            throws EpisodeQueueException {
        if (count(
                database,
                "episodes",
                "media_id = ?",
                new String[] {mediaId}) != 1L) {
            throw queueFailure(
                    EpisodeQueueException.Reason.EPISODE_NOT_FOUND,
                    "Episode did not exist.");
        }
    }

    private static void insertQueueEntry(
            SQLiteDatabase database,
            String mediaId,
            int position) {
        SQLiteStatement statement = database.compileStatement(
                CatalogSql.INSERT_EPISODE_QUEUE);
        try {
            statement.bindString(1, mediaId);
            statement.bindLong(2, position);
            statement.bindLong(3, System.currentTimeMillis());
            statement.execute();
        } finally {
            statement.close();
        }
    }

    private static void moveQueueEntry(
            SQLiteDatabase database,
            String mediaId,
            int currentPosition,
            int targetPosition) throws EpisodeQueueException {
        SQLiteStatement park = database.compileStatement(
                CatalogSql.PARK_EPISODE_QUEUE_ENTRY);
        try {
            park.bindString(1, mediaId);
            if (park.executeUpdateDelete() != 1) {
                throw queueFailure(
                        EpisodeQueueException.Reason.CORRUPT_STATE,
                        "Queue move could not isolate its target.");
            }
        } finally {
            park.close();
        }
        if (currentPosition < targetPosition) {
            executeQueueUpdate(
                    database,
                    CatalogSql.SHIFT_EPISODE_QUEUE_LATER,
                    currentPosition,
                    targetPosition);
        } else {
            executeQueueUpdate(
                    database,
                    CatalogSql.SHIFT_EPISODE_QUEUE_EARLIER,
                    targetPosition,
                    currentPosition);
        }
        SQLiteStatement place = database.compileStatement(
                CatalogSql.SET_EPISODE_QUEUE_POSITION);
        try {
            place.bindLong(1, targetPosition);
            place.bindString(2, mediaId);
            if (place.executeUpdateDelete() != 1) {
                throw queueFailure(
                        EpisodeQueueException.Reason.CORRUPT_STATE,
                        "Queue move could not place its target.");
            }
        } finally {
            place.close();
        }
    }

    private static void executeQueueUpdate(
            SQLiteDatabase database,
            String sql,
            long... arguments) {
        SQLiteStatement statement = database.compileStatement(sql);
        try {
            for (int index = 0; index < arguments.length; index++) {
                statement.bindLong(index + 1, arguments[index]);
            }
            statement.executeUpdateDelete();
        } finally {
            statement.close();
        }
    }

    private static void requireMediaId(String mediaId) throws EpisodeQueueException {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            throw queueFailure(
                    EpisodeQueueException.Reason.INVALID_MEDIA_ID,
                    "Media ID was invalid.");
        }
    }

    private EpisodeStateMutation existingEpisodeMutationResult(String mediaId)
            throws EpisodeActionException {
        try {
            if (count(
                    getReadableDatabase(),
                    "episodes",
                    "media_id = ?",
                    new String[] {mediaId}) == 1L) {
                return EpisodeStateMutation.UNCHANGED;
            }
            throw episodeActionFailure(
                    EpisodeActionException.Reason.EPISODE_NOT_FOUND,
                    "Episode did not exist.");
        } catch (EpisodeActionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw episodeActionFailure(
                    EpisodeActionException.Reason.STORAGE_FAILURE,
                    "Episode state could not be checked.");
        }
    }

    private static void requireEpisodeActionMediaId(String mediaId)
            throws EpisodeActionException {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            throw episodeActionFailure(
                    EpisodeActionException.Reason.INVALID_MEDIA_ID,
                    "Media ID was invalid.");
        }
    }

    private static void requireAutomaticDownloadMediaId(String mediaId) {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            throw new IllegalArgumentException("Invalid automatic download media ID.");
        }
    }

    private SQLiteDatabase writableDatabase() throws CatalogStorageException {
        try {
            return getWritableDatabase();
        } catch (SQLiteException exception) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog database could not be opened.");
        }
    }

    private static void validateSnapshotSize(CatalogSnapshot snapshot)
            throws CatalogStorageException {
        if (snapshot.getEpisodes().size() > SecurityLimits.MAX_EPISODES_PER_FEED) {
            throw failure(
                    CatalogStorageException.Reason.EPISODE_LIMIT,
                    "Feed episode limit was exceeded.");
        }
    }

    private static void validateValidatorResource(
            CatalogSnapshot snapshot,
            FeedValidators validators) throws CatalogStorageException {
        if (!validators.isEmpty()
                && !snapshot.getFinalFeedUrl().equals(validators.getResourceUrl())) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog validator resource was inconsistent.");
        }
    }

    private static void verifyGenerationCount(
            SQLiteDatabase database,
            long subscriptionId,
            long generation,
            int expected) throws CatalogStorageException {
        long actual = count(
                database,
                "episodes",
                "subscription_id = ? AND last_seen_generation = ? AND is_stale = 0",
                new String[] {
                        Long.toString(subscriptionId),
                        Long.toString(generation)
                });
        if (actual != expected) {
            throw failure(
                    CatalogStorageException.Reason.STORAGE_FAILURE,
                    "Catalog identity conflict was rejected.");
        }
    }

    private static void enforceEpisodeLimits(SQLiteDatabase database, long subscriptionId)
            throws CatalogStorageException {
        long subscriptionCount = count(
                database,
                "episodes",
                "subscription_id = ?",
                new String[] {Long.toString(subscriptionId)});
        if (subscriptionCount > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION) {
            prune(
                    database,
                    CatalogSql.PRUNE_SUBSCRIPTION,
                    subscriptionId,
                    subscriptionCount - SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION);
            subscriptionCount = count(
                    database,
                    "episodes",
                    "subscription_id = ?",
                    new String[] {Long.toString(subscriptionId)});
            if (subscriptionCount > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION) {
                throw failure(
                        CatalogStorageException.Reason.EPISODE_LIMIT,
                        "Per-subscription episode limit was reached.");
            }
        }

        long globalCount = count(database, "episodes", null, null);
        if (globalCount > SecurityLimits.MAX_GLOBAL_EPISODES) {
            prune(
                    database,
                    CatalogSql.PRUNE_GLOBAL,
                    0L,
                    globalCount - SecurityLimits.MAX_GLOBAL_EPISODES);
            if (count(database, "episodes", null, null)
                    > SecurityLimits.MAX_GLOBAL_EPISODES) {
                throw failure(
                        CatalogStorageException.Reason.EPISODE_LIMIT,
                        "Global episode limit was reached.");
            }
        }
    }

    private static void prune(
            SQLiteDatabase database,
            String sql,
            long subscriptionId,
            long requested) {
        if (requested <= 0L) {
            return;
        }
        SQLiteStatement statement = database.compileStatement(sql);
        try {
            int index = 1;
            if (sql.equals(CatalogSql.PRUNE_SUBSCRIPTION)) {
                statement.bindLong(index++, subscriptionId);
            }
            statement.bindLong(index, requested);
            statement.executeUpdateDelete();
        } finally {
            statement.close();
        }
    }

    private static void executeSingleLong(
            SQLiteDatabase database,
            String sql,
            long value) {
        SQLiteStatement statement = database.compileStatement(sql);
        try {
            statement.bindLong(1, value);
            statement.executeUpdateDelete();
        } finally {
            statement.close();
        }
    }

    private static SubscriptionRecord findSubscription(
            SQLiteDatabase database,
            String subscriptionUrl) {
        try (Cursor cursor = database.query(
                "subscriptions",
                new String[] {"id", "generation", "is_subscribed", "auto_download"},
                "subscription_url = ?",
                new String[] {subscriptionUrl},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst()
                    ? new SubscriptionRecord(cursor.getLong(0), cursor.getLong(1),
                            cursor.getInt(2) == 1, cursor.getInt(3) == 1)
                    : null;
        }
    }

    private static boolean containsSubscriptionId(
            SQLiteDatabase database,
            long subscriptionId) {
        try (Cursor cursor = database.query(
                "subscriptions",
                new String[] {"1"},
                "id = ?",
                new String[] {Long.toString(subscriptionId)},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst();
        }
    }

    private static int checkedCount(long value) {
        if (value < 0L || value > SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION) {
            throw new IllegalArgumentException("Invalid catalog count.");
        }
        return (int) value;
    }

    private static long count(
            SQLiteDatabase database,
            String table,
            String selection,
            String[] selectionArguments) {
        try (Cursor cursor = database.query(
                table,
                new String[] {"COUNT(*)"},
                selection,
                selectionArguments,
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                throw new SQLiteException("Count query returned no row.");
            }
            return cursor.getLong(0);
        }
    }

    private static CatalogStorageException failure(
            CatalogStorageException.Reason reason,
            String message) {
        return new CatalogStorageException(reason, message);
    }

    private static EpisodeQueueException queueFailure(
            EpisodeQueueException.Reason reason,
            String message) {
        return new EpisodeQueueException(reason, message);
    }

    private static EpisodeActionException episodeActionFailure(
            EpisodeActionException.Reason reason,
            String message) {
        return new EpisodeActionException(reason, message);
    }

    @FunctionalInterface
    private interface QueueMutationAction {
        EpisodeQueueMutation apply(SQLiteDatabase database, int queueSize)
                throws EpisodeQueueException;
    }

    private static final class SubscriptionRecord {
        private final long id;
        private final long generation;
        private final boolean subscribed;
        private final boolean autoDownload;

        private SubscriptionRecord(long id, long generation, boolean subscribed,
                boolean autoDownload) {
            this.id = id;
            this.generation = generation;
            this.subscribed = subscribed;
            this.autoDownload = autoDownload;
        }
    }
}
