package io.github.jnesew.plainpoddroid.catalog;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery;
import io.github.jnesew.plainpoddroid.core.playback.PlaybackProgressUpdate;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueException;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.auth.FeedLoginException;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;


/** Real Android adapter tests, isolated from the application's normal database. */
@SuppressWarnings("deprecation")
public final class CatalogDatabaseTest extends InstrumentationTestCase {
    private Context context;
    private EpisodeCatalogDatabase catalog;
    private static final String NAME = "plainpoddroid-catalog.db";

    @Override protected void setUp() throws Exception {
        super.setUp();
        String prefix = "instrumentation-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(getInstrumentation().getTargetContext()) {
            @Override public File getDatabasePath(String name) {
                return super.getDatabasePath(prefix + name);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory) {
                return super.openOrCreateDatabase(prefix + name, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return super.openOrCreateDatabase(prefix + name, mode, factory, handler);
            }
            @Override public boolean deleteDatabase(String name) {
                return super.deleteDatabase(prefix + name);
            }
        };
        catalog = new EpisodeCatalogDatabase(context);
    }

    @Override protected void tearDown() throws Exception {
        catalog.close(); context.deleteDatabase(NAME); super.tearDown();
    }

    public void testCursorMappingAndUserStateMutations() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase();
        seedSubscription(db, 1); seedEpisode(db, "a", 1);
        String id = id("a");
        catalog.setFavorite(id, true);
        catalog.savePlaybackProgress(new PlaybackProgressUpdate(id, 60_000, true, 20));
        EpisodeDetails details = catalog.findDetails(id);
        assertEquals("Episode a", details.getTitle());
        assertEquals(60_000L, details.getPlaybackPositionMillis());
        assertTrue(details.isPlayed()); assertTrue(details.isFavorite());
        assertEquals("Podcast 1", details.getPodcast().title());
        assertEquals("Podcast 1", catalog.listEpisodes(1, 0, 10, CatalogBrowseQuery.DEFAULT)
                .getEpisodes().get(0).getPodcast().title());
    }

    public void testFailedQueueReorderRollsBackThenSuccessfulMoveWorks() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase();
        seedSubscription(db, 1); seedEpisode(db, "a", 1); seedEpisode(db, "b", 1);
        catalog.append(id("a")); catalog.append(id("b"));
        db.execSQL("CREATE TEMP TRIGGER reject_move BEFORE UPDATE OF position ON episode_queue "
                + "WHEN NEW.position=0 AND NEW.media_id='" + id("b") + "' "
                + "BEGIN SELECT RAISE(ABORT,'Injected failure'); END");
        try { catalog.move(id("b"), 0); fail("Expected injected failure"); }
        catch (EpisodeQueueException expected) { }
        assertEquals(id("a"), catalog.listQueue().get(0).getMediaId());
        assertEquals(1, catalog.listQueue().get(1).getPosition());
        assertEquals("Podcast 1", catalog.listQueue().get(0).getPodcast().title());
        db.execSQL("DROP TRIGGER reject_move");
        catalog.move(id("b"), 0);
        assertEquals(id("b"), catalog.listQueue().get(0).getMediaId());
        assertEquals(1, catalog.listQueue().get(1).getPosition());
    }

    public void testOpeningFrozenVersionNinePreservesUserState() throws Exception {
        catalog.close();
        try (SQLiteDatabase db = context.openOrCreateDatabase(NAME, 0, null);
                var input = getInstrumentation().getContext().getAssets().open("catalog-v9.sql")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String schema = new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^--.*$", "");
            for (String statement : schema.split(";")) {
                if (!statement.trim().isEmpty()) db.execSQL(statement);
            }
            seedSubscription(db, 1); seedEpisode(db, "a", 1);
            db.execSQL("UPDATE episodes SET favorite=1,played=1,downloaded=1,playback_position_ms=60000");
            db.execSQL("INSERT INTO episode_queue VALUES(?,?,?)", new Object[] {id("a"), 0, 1});
        }
        catalog = new EpisodeCatalogDatabase(context);
        EpisodeDetails details = catalog.findDetails(id("a"));
        assertTrue(details.isFavorite()); assertTrue(details.isPlayed());
        assertTrue(details.isDownloaded()); assertEquals(60_000L, details.getPlaybackPositionMillis());
        assertEquals(id("a"), catalog.listQueue().get(0).getMediaId());
        assertEquals(15, catalog.getReadableDatabase().getVersion());
        assertEquals(0L, catalog.listDownloadedEpisodes(0, 10).episodes().get(0).completedAtMillis());
        assertEquals(0L, catalog.listDownloadedEpisodes(0, 10).episodes().get(0).downloadedBytes());
    }

    public void testExistingZeroSizeEnclosureCanDownloadWithoutRefreshOrMigration() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase();
        seedSubscription(db, 1); seedEpisode(db, "a", 1);
        db.execSQL("UPDATE episodes SET declared_length=0");
        var target = catalog.findEpisodeDownloadTarget(id("a"));
        assertEquals(0L, target.getDeclaredLength());
        assertTrue(catalog.markEpisodeDownloadComplete(target, 5L));
        assertEquals(5L, catalog.listDownloadedEpisodes(0, 10).episodes().get(0).downloadedBytes());
        db.execSQL("UPDATE episodes SET declared_length=1");
        assertFalse(catalog.markEpisodeDownloadComplete(target, 7L));
    }

    public void testDownloadProjectionUsesCompletionOrderAndMeasuredBytes() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase();
        seedSubscription(db, 1); seedSubscription(db, 2);
        seedEpisode(db, "a", 1); seedEpisode(db, "b", 2); seedEpisode(db, "c", 1);
        assertTrue(catalog.markEpisodeDownloadComplete(catalog.findEpisodeDownloadTarget(id("a")), 5));
        db.execSQL("UPDATE episodes SET download_completed_at=10 WHERE media_id=?", new Object[] {id("a")});
        assertTrue(catalog.markEpisodeDownloadComplete(catalog.findEpisodeDownloadTarget(id("b")), 7));
        db.execSQL("UPDATE episodes SET downloaded=1 WHERE media_id=?", new Object[] {id("c")});
        db.execSQL("UPDATE subscriptions SET is_subscribed=0 WHERE id=2");
        catalog.savePlaybackProgress(new PlaybackProgressUpdate(id("a"), 60_000, false, Long.MAX_VALUE));
        catalog.setFavorite(id("a"), true);
        var first = catalog.listDownloadedEpisodes(0, 1);
        assertTrue(first.hasMore());
        assertEquals(id("b"), first.episodes().get(0).episode().getMediaId());
        assertEquals(7L, first.episodes().get(0).downloadedBytes());
        assertFalse(first.episodes().get(0).episode().getPodcast().subscribed());
        assertEquals("Podcast 2", first.episodes().get(0).episode().getPodcast().title());
        assertEquals(id("a"), catalog.listDownloadedEpisodes(1, 1).episodes().get(0).episode().getMediaId());
        catalog.markEpisodeDownloadAbsent(id("a"));
        assertEquals(2, catalog.listDownloadedEpisodes(0, 10).episodes().size());
        try { catalog.listDownloadedEpisodes(-1, 1); fail("Invalid offset was accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    static String id(String letter) {
        StringBuilder value = new StringBuilder("episode-");
        for (int i = 0; i < 64; i++) value.append(letter);
        return value.toString();
    }

    public void testGlobalFavoritesAndPlayedProjectionIncludesArchivedParent() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();
        seedSubscription(db,1);seedSubscription(db,2);seedEpisode(db,"a",1);seedEpisode(db,"b",2);
        db.execSQL("UPDATE subscriptions SET is_subscribed=0 WHERE id=2");
        db.execSQL("UPDATE episodes SET favorite=1,played=1 WHERE media_id=?",new Object[]{id("b")});
        catalog.setFavorite(id("a"),true);
        var first=catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.FAVORITES,0,1);
        assertTrue(first.hasMore());
        var second=catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.FAVORITES,1,1);
        assertFalse(second.hasMore());assertFalse(first.episodes().get(0).getMediaId().equals(second.episodes().get(0).getMediaId()));
        catalog.recordListening(id("b"), 100);
        var played=catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.HISTORY,0,10);
        assertEquals(id("b"),played.episodes().get(0).getMediaId());
        assertFalse(played.episodes().get(0).getPodcast().subscribed());
        assertEquals("Podcast 2",played.episodes().get(0).getPodcast().title());
    }

    public void testAuthenticatedConfirmationCommitsBothCatalogAndCiphertextOrNeither() throws Exception {
        var owner = CanonicalHttpsUrl.parse("https://pod.example/1");
        String xml = "<rss><channel><title>Test</title><item><guid>one</guid><title>Episode</title>"
                + "<enclosure url=\"https://media.example/one.mp3\" type=\"audio/mpeg\" /></item></channel></rss>";
        var acquisition = new io.github.jnesew.plainpoddroid.core.network.BoundedFeedAcquirer((url, headers) ->
                new io.github.jnesew.plainpoddroid.core.network.FeedTransport.Exchange() {
                    public int getStatusCode() { return 200; }
                    public String getHeader(String name) { return null; }
                    public java.io.InputStream getBody() { return new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)); }
                    public void close() { }
                }).acquire(owner.toString());
        var snapshot = new io.github.jnesew.plainpoddroid.core.catalog.EpisodeCatalogMapper().map(acquisition);
        var wrong = new BasicFeedLogin(
                CanonicalHttpsUrl.parse("https://other.example/rss"), "user", "pass", null);
        try { catalog.addConfirmedSubscription(snapshot, io.github.jnesew.plainpoddroid.core.network.FeedValidators.none(), wrong);
            fail("Wrong credential owner must roll back confirmation"); }
        catch (CatalogStorageException expected) { }
        try (var cursor = catalog.getReadableDatabase().rawQuery("SELECT count(*) FROM subscriptions", null)) {
            assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0));
        }
        var correct = new BasicFeedLogin(owner, "user", "pass", null);
        long id = catalog.addConfirmedSubscription(snapshot, io.github.jnesew.plainpoddroid.core.network.FeedValidators.none(), correct);
        assertTrue(correct.sameLogin(catalog.getPodcastLogin(id)));
    }

    public void testLoginPresentationUsesOpaqueStateWithoutDecryptingAndForgetsArchivedChallenges() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedEpisode(db,"a",1);seedSubscription(db,2);
        var none=io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState.NONE;
        var saved=io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState.SAVED;
        var required=io.github.jnesew.plainpoddroid.core.auth.PodcastLoginState.REQUIRED;
        assertEquals(none,catalog.getPodcastLoginState(1));
        catalog.noteBasicChallenge(CanonicalHttpsUrl.parse("https://media.example/a.mp3"),true);
        assertEquals(required,catalog.getPodcastLoginState(1));assertEquals(none,catalog.getPodcastLoginState(2));
        catalog.savePodcastLogin(1,new BasicFeedLogin(CanonicalHttpsUrl.parse("https://pod.example/1"),"user","password",null));
        assertEquals(saved,catalog.getPodcastLoginState(1));
        // Malformed ciphertext must still expose Manage login without attempting decryption during menu reads.
        db.execSQL("UPDATE subscription_logins SET login_data=? WHERE subscription_id=1",new Object[]{new byte[29]});
        assertEquals(saved,catalog.getPodcastLoginState(1));
        catalog.noteBasicChallenge(CanonicalHttpsUrl.parse("https://pod.example/1"),false);assertEquals(required,catalog.getPodcastLoginState(1));
        catalog.archiveSubscription(1);assertEquals(none,catalog.getPodcastLoginState(1));
        db.execSQL("UPDATE subscriptions SET is_subscribed=1 WHERE id=1");assertEquals(none,catalog.getPodcastLoginState(1));
    }

    private io.github.jnesew.plainpoddroid.core.catalog.CatalogSnapshot snapshot(String items) throws Exception {
        byte[] xml=("<rss><channel><title>Test</title>"+items+"</channel></rss>").getBytes(StandardCharsets.UTF_8);
        var feed=new io.github.jnesew.plainpoddroid.core.network.BoundedFeedAcquirer((url,headers)->
            new io.github.jnesew.plainpoddroid.core.network.FeedTransport.Exchange(){
                public int getStatusCode(){return 200;}public String getHeader(String name){return null;}
                public java.io.InputStream getBody(){return new java.io.ByteArrayInputStream(xml);}public void close(){}
            }).acquire("https://pod.example/refresh");
        return new io.github.jnesew.plainpoddroid.core.catalog.EpisodeCatalogMapper().map(feed);
    }
    private static String item(String id,String title){
        return "<item><guid>"+id+"</guid><title>"+title+"</title><enclosure type=\"audio/mpeg\" url=\"https://media.example/"+id+".mp3\" /></item>";
    }
    public void testRefreshUpdatesOldMetadataWithoutRedundantStaleWritesAndRetainsMissingItems() throws Exception {
        var validators=io.github.jnesew.plainpoddroid.core.network.FeedValidators.none();
        var first=snapshot(item("one","Old title")+item("two","Second"));
        long podcast=catalog.addConfirmedSubscription(first,validators);String one=first.getEpisodes().get(0).getMediaId();
        String two=first.getEpisodes().get(1).getMediaId();catalog.setFavorite(one,true);
        catalog.savePlaybackProgress(new PlaybackProgressUpdate(one,60000,true,20));
        var db=catalog.getWritableDatabase();
        db.execSQL("CREATE TEMP TRIGGER reject_redundant_stale BEFORE UPDATE OF is_stale ON episodes WHEN NEW.media_id='"+one
                +"' AND NEW.is_stale=1 BEGIN SELECT RAISE(ABORT,'Redundant stale write'); END");
        catalog.refreshExistingSubscription(catalog.findSubscriptionRefreshTarget(podcast),snapshot(item("one","Corrected title")+item("three","New")),validators);
        assertEquals("Corrected title",catalog.findDetails(one).getTitle());assertTrue(catalog.findDetails(one).isFavorite());
        assertTrue(catalog.findDetails(one).isPlayed());assertEquals(60000L,catalog.findDetails(one).getPlaybackPositionMillis());
        try(var row=db.rawQuery("SELECT is_stale,first_seen_generation FROM episodes WHERE media_id=?",new String[]{two})){
            assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));assertEquals(1,row.getInt(1));
        }
        catalog.refreshExistingSubscription(catalog.findSubscriptionRefreshTarget(podcast),snapshot(item("one","Corrected title")+item("two","Returned")),validators);
        // Missing unplayed episodes remain visible; only current-feed membership is two.
        assertEquals(3,catalog.listEpisodes(podcast,0,10,CatalogBrowseQuery.DEFAULT).getEpisodes().size());
        try(var row=db.rawQuery("SELECT COUNT(*) FROM episodes WHERE subscription_id=? AND is_stale=0",
                new String[]{Long.toString(podcast)})){assertTrue(row.moveToFirst());assertEquals(2,row.getInt(0));}
        assertEquals("Returned",catalog.findDetails(two).getTitle());
        db.execSQL("DROP TRIGGER reject_redundant_stale");
        catalog.refreshExistingSubscription(catalog.findSubscriptionRefreshTarget(podcast),snapshot(""),validators);
        assertEquals(3,catalog.listEpisodes(podcast,0,10,CatalogBrowseQuery.DEFAULT).getEpisodes().size());
        try(var row=db.rawQuery("SELECT COUNT(*) FROM episodes WHERE subscription_id=? AND is_stale=0",
                new String[]{Long.toString(podcast)})){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
        assertTrue(catalog.findDetails(one).isFavorite());
    }
    public void testRetiredRefreshCannotOverwriteResubscribedPodcastOrItsValidators() throws Exception {
        var first=snapshot(item("one","Original"));
        var oldValidators=io.github.jnesew.plainpoddroid.core.network.FeedValidators.of(first.getFinalFeedUrl(),"\"old\"","");
        long podcast=catalog.addConfirmedSubscription(first,oldValidators);
        var retired=catalog.findSubscriptionRefreshTarget(podcast);assertEquals(1L,retired.getGeneration());
        assertTrue(catalog.archiveSubscription(podcast));
        var current=snapshot(item("one","Resubscribed"));
        var currentValidators=io.github.jnesew.plainpoddroid.core.network.FeedValidators.of(current.getFinalFeedUrl(),"\"new\"","");
        assertEquals(podcast,catalog.addConfirmedSubscription(current,currentValidators));
        try{catalog.refreshExistingSubscription(retired,snapshot(item("one","Late response")),oldValidators);fail("Retired body committed");}
        catch(CatalogStorageException expected){}
        try{catalog.recordUnmodifiedSubscription(retired,oldValidators);fail("Retired 304 committed");}
        catch(CatalogStorageException expected){}
        assertEquals("Resubscribed",catalog.findDetails(first.getEpisodes().get(0).getMediaId()).getTitle());
        var target=catalog.findSubscriptionRefreshTarget(podcast);assertEquals(2L,target.getGeneration());
        assertEquals("\"new\"",target.getValidators().getEtag());
    }

    public void testFailedRefreshRollsBackMetadataMembershipAndGeneration() throws Exception {
        var validators=io.github.jnesew.plainpoddroid.core.network.FeedValidators.none();
        var first=snapshot(item("one","First")+item("two","Second"));long podcast=catalog.addConfirmedSubscription(first,validators);
        String one=first.getEpisodes().get(0).getMediaId();String two=first.getEpisodes().get(1).getMediaId();
        var db=catalog.getWritableDatabase();db.execSQL("CREATE TEMP TRIGGER fail_second BEFORE UPDATE OF title ON episodes "
                +"WHEN NEW.title='Reject' BEGIN SELECT RAISE(ABORT,'Injected failure'); END");
        try{catalog.refreshExistingSubscription(catalog.findSubscriptionRefreshTarget(podcast),snapshot(item("one","Changed")+item("two","Reject")),validators);fail("Expected rollback");}
        catch(CatalogStorageException expected){}
        assertEquals("First",catalog.findDetails(one).getTitle());assertEquals("Second",catalog.findDetails(two).getTitle());
        assertEquals(2,catalog.listEpisodes(podcast,0,10,CatalogBrowseQuery.DEFAULT).getEpisodes().size());
        try(var row=db.rawQuery("SELECT generation FROM subscriptions WHERE id=?",new String[]{Long.toString(podcast)})){
            assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));
        }
    }

    public void testEncryptedLoginSurvivesRestartAndSuspendsOnlyRejectedCurrentCredentials() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase(); seedSubscription(db, 1); seedEpisode(db, "a", 1);
        var owner = CanonicalHttpsUrl.parse("https://pod.example/1");
        var audio = CanonicalHttpsUrl.parse("https://media.example/a.mp3");
        var login = new BasicFeedLogin(owner, "test-user", "test-password",
                CanonicalHttpsUrl.parse("https://media.example/"));
        catalog.savePodcastLogin(1, login);
        try (var row = db.rawQuery("SELECT login_data FROM subscription_logins", null)) {
            assertTrue(row.moveToFirst());
            assertFalse(new String(row.getBlob(0), StandardCharsets.ISO_8859_1).contains("test-password"));
        }
        catalog.close(); catalog = new EpisodeCatalogDatabase(context);
        assertTrue(login.sameLogin(catalog.findFeedLogin(owner)));
        assertTrue(login.sameLogin(catalog.findAudioLogin(audio)));
        catalog.rejectLogin(audio, true, login);
        try { catalog.findAudioLogin(audio); fail("Rejected audio login must be suspended"); }
        catch (FeedLoginException expected) { }
        assertNotNull(catalog.findFeedLogin(owner));
        var replacement = new BasicFeedLogin(owner, "test-user", "replacement", login.audioScope());
        catalog.savePodcastLogin(1, replacement);
        catalog.rejectLogin(audio, true, login); // Late failure cannot suspend changed credentials.
        assertTrue(replacement.sameLogin(catalog.findAudioLogin(audio)));
        catalog.archiveSubscription(1);
        assertNull(catalog.findFeedLogin(owner)); assertNull(catalog.getPodcastLogin(1));
        try (var row = catalog.getReadableDatabase().rawQuery("SELECT count(*) FROM subscription_logins", null)) {
            assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0));
        }
    }

    public void testNativeKeyLossAndTamperedCiphertextRequireExplicitLoginUpdate() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase(); seedSubscription(db, 1);
        var owner = CanonicalHttpsUrl.parse("https://pod.example/1");
        var login = new BasicFeedLogin(owner, "user", "password", null);
        catalog.savePodcastLogin(1, login);
        byte[] stored;
        try (var row = db.rawQuery("SELECT login_data FROM subscription_logins WHERE subscription_id=1", null)) {
            assertTrue(row.moveToFirst()); stored = row.getBlob(0);
        }
        byte[] changed = stored.clone(); changed[changed.length-1] ^= 1;
        db.execSQL("UPDATE subscription_logins SET login_data=? WHERE subscription_id=1", new Object[] {changed});
        try { catalog.getPodcastLogin(1); fail("Tampering must fail"); }
        catch (FeedLoginException expected) { }
        db.execSQL("UPDATE subscription_logins SET login_data=? WHERE subscription_id=1", new Object[] {stored});
        var keys = new io.github.jnesew.plainpoddroid.auth.AndroidFeedLoginKeys(); keys.delete(owner);
        try { catalog.getPodcastLogin(1); fail("Key loss must not regenerate a key on read"); }
        catch (FeedLoginException expected) { }
        catalog.savePodcastLogin(1, login); assertTrue(login.sameLogin(catalog.findFeedLogin(owner)));
        catalog.savePodcastLogin(1, null); assertNull(catalog.getPodcastLogin(1));
        try { keys.get(owner, false); fail("Removing Login must delete its key after commit"); }
        catch (java.io.IOException expected) { }
    }

    public void testAmbiguousAudioOwnersNeverSelectAnotherSubscriptionsLogin() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase(); seedSubscription(db, 1); seedSubscription(db, 2);
        seedEpisode(db, "a", 1); seedEpisode(db, "b", 2);
        db.execSQL("UPDATE episodes SET audio_url='https://media.example/shared.mp3'");
        var owner = CanonicalHttpsUrl.parse("https://pod.example/1");
        var audio = CanonicalHttpsUrl.parse("https://media.example/shared.mp3");
        assertNull(catalog.findAudioLogin(audio));
        catalog.savePodcastLogin(1, new BasicFeedLogin(owner, "user", "pass", null));
        try { catalog.findAudioLogin(audio); fail("Ambiguous owner must not supply login"); }
        catch (FeedLoginException expected) { }
        catalog.savePodcastLogin(1, null); assertNull(catalog.findAudioLogin(audio));
    }

    public void testViewedGenerationDoesNotClearConcurrentArrivalsOrChangePlayedState() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase();
        seedSubscription(db, 1); seedEpisode(db, "a", 1); seedEpisode(db, "b", 1);
        db.execSQL("UPDATE subscriptions SET generation=2,seen_generation=1,has_episode_baseline=1");
        db.execSQL("UPDATE episodes SET first_seen_generation=2,last_seen_generation=2 WHERE media_id=?", new Object[] {id("b")});
        assertEquals(1, catalog.listSubscriptions().get(0).getNewEpisodeCount());
        var page = catalog.listEpisodes(1, 0, 10, CatalogBrowseQuery.DEFAULT);
        db.execSQL("UPDATE subscriptions SET generation=3"); seedEpisode(db, "c", 1);
        db.execSQL("UPDATE episodes SET first_seen_generation=3,last_seen_generation=3 WHERE media_id=?", new Object[] {id("c")});
        catalog.markSubscriptionViewed(1, page.getSnapshotGeneration());
        assertEquals(1, catalog.listSubscriptions().get(0).getNewEpisodeCount());
        assertEquals(3, catalog.listSubscriptions().get(0).getUnplayedCount());
        catalog.markSubscriptionViewed(1, 1); // Delayed old acknowledgement cannot regress.
        assertEquals(1, catalog.listSubscriptions().get(0).getNewEpisodeCount());
        catalog.close(); catalog = new EpisodeCatalogDatabase(context);
        assertEquals(1, catalog.listSubscriptions().get(0).getNewEpisodeCount());
    }

    public void testEpisodeOrderPersistsAcrossReopenAndDoesNotAffectOtherPodcasts() throws Exception {
        SQLiteDatabase db = catalog.getWritableDatabase();
        seedSubscription(db, 1); seedSubscription(db, 2);
        seedEpisode(db, "a", 1); seedEpisode(db, "b", 1); seedEpisode(db, "c", 1);
        db.execSQL("UPDATE episodes SET published_at=100 WHERE media_id=?", new Object[] {id("a")});
        db.execSQL("UPDATE episodes SET published_at=200 WHERE media_id=?", new Object[] {id("b")});
        assertEquals(id("b"), catalog.listEpisodes(1, 0, 10, CatalogBrowseQuery.DEFAULT).getEpisodes().get(0).getMediaId());
        catalog.setEpisodeOrder(1, CatalogBrowseQuery.Order.OLDEST_FIRST);
        catalog.close(); catalog = new EpisodeCatalogDatabase(context);
        var episodes = catalog.listEpisodes(1, 0, 10, CatalogBrowseQuery.DEFAULT).getEpisodes();
        assertEquals(id("a"), episodes.get(0).getMediaId()); assertEquals(id("c"), episodes.get(2).getMediaId());
        for (var podcast : catalog.listSubscriptions())
            assertEquals(podcast.getSubscriptionId() == 1 ? CatalogBrowseQuery.Order.OLDEST_FIRST : CatalogBrowseQuery.Order.NEWEST_FIRST,
                    podcast.getEpisodeOrder());
    }

    public void testHistoryContainsActualListeningAndSurvivesOtherMutations() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);
        seedEpisode(db,"a",1);seedEpisode(db,"b",1);
        catalog.setPlayed(id("a"),true);
        assertTrue(catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.HISTORY,0,10).episodes().isEmpty());
        catalog.recordListening(id("a"),10);catalog.recordListening(id("b"),20);
        catalog.setFavorite(id("a"),true);catalog.savePlaybackProgress(new PlaybackProgressUpdate(id("a"),1000,false,1000));
        catalog.close();catalog=new EpisodeCatalogDatabase(context);
        var page=catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.HISTORY,0,10);
        assertEquals(id("b"),page.episodes().get(0).getMediaId());assertEquals(Long.valueOf(20),page.listenedAtMillis().get(0));
        assertEquals(Long.valueOf(10),page.listenedAtMillis().get(1));
    }

    public void testNewAndInProgressViewsDoNotChangeSeenOrPlayedState() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedEpisode(db,"a",1);
        db.execSQL("UPDATE subscriptions SET generation=2,seen_generation=1,has_episode_baseline=1");
        db.execSQL("UPDATE episodes SET first_seen_generation=2,playback_position_ms=1000");
        var fresh=catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.NEW_EPISODES,0,10);
        var progress=catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.IN_PROGRESS,0,10);
        assertEquals(id("a"),fresh.episodes().get(0).getMediaId());assertEquals(id("a"),progress.episodes().get(0).getMediaId());
        assertEquals(1,catalog.listSubscriptions().get(0).getNewEpisodeCount());assertFalse(catalog.findDetails(id("a")).isPlayed());
    }

    public void testPodcastSpeedPersistsInheritsAndResolvesActualEpisodeOwner() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedSubscription(db,2);
        seedEpisode(db,"a",1);seedEpisode(db,"b",2);
        assertNull(catalog.findSpeedForEpisode(id("a")));
        catalog.setPodcastSpeed(1,io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting.ONE_AND_HALF);
        catalog.close();catalog=new EpisodeCatalogDatabase(context);
        assertEquals(io.github.jnesew.plainpoddroid.core.playback.PlaybackSpeedSetting.ONE_AND_HALF,catalog.findSpeedForEpisode(id("a")));
        assertNull(catalog.findSpeedForEpisode(id("b")));
        catalog.setPodcastSpeed(1,null);assertNull(catalog.getPodcastSpeed(1));assertNull(catalog.findSpeedForEpisode(id("a")));
    }

    public void testBatchPlayedRollsBackMissingTargetAndResetsOnlyExplicitUnplayedProgress() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);
        seedEpisode(db,"a",1);seedEpisode(db,"b",1);
        db.execSQL("UPDATE episodes SET playback_position_ms=1234,last_listened_at=500,favorite=1");
        var selection=new io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest(java.util.List.of(id("a"),id("c")));
        try { catalog.setPlayedBatch(selection,true);fail("Missing target must roll back selection"); }
        catch(java.io.IOException expected) { }
        assertFalse(catalog.findDetails(id("a")).isPlayed());
        selection=new io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest(java.util.List.of(id("b"),id("a")));
        assertEquals(2,catalog.setPlayedBatch(selection,true));
        assertEquals(1234L,catalog.findDetails(id("a")).getPlaybackPositionMillis());
        assertEquals(2,catalog.setPlayedBatch(selection,false));
        assertEquals(0L,catalog.findDetails(id("a")).getPlaybackPositionMillis());
        assertTrue(catalog.findDetails(id("a")).isFavorite());
        assertEquals(2,catalog.listLibraryEpisodes(io.github.jnesew.plainpoddroid.core.catalog.EpisodeLibraryStore.Filter.HISTORY,0,10).episodes().size());
    }

    public void testBacklogPreviewBoundsLateArrivalsAndPodcastDateScope() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedSubscription(db,2);
        seedEpisode(db,"a",1);seedEpisode(db,"b",1);seedEpisode(db,"c",2);
        db.execSQL("UPDATE episodes SET published_at=100 WHERE media_id=?",new Object[]{id("a")});
        db.execSQL("UPDATE episodes SET published_at=200 WHERE media_id=?",new Object[]{id("b")});
        assertNull(catalog.preparePlayedBatch(1,id("c")));
        var older=catalog.preparePlayedBatch(1,id("b"));assertEquals(1,older.count());
        var all=catalog.preparePlayedBatch(1,null);assertEquals(2,all.count());
        db.execSQL("UPDATE subscriptions SET generation=2 WHERE id=1");seedEpisode(db,"d",1);
        db.execSQL("UPDATE episodes SET published_at=50,first_seen_generation=2 WHERE media_id=?",new Object[]{id("d")});
        assertEquals(1,catalog.markPodcastPlayed(older));assertTrue(catalog.findDetails(id("a")).isPlayed());
        assertFalse(catalog.findDetails(id("b")).isPlayed());
        assertEquals(1,catalog.markPodcastPlayed(all));
        assertFalse(catalog.findDetails(id("d")).isPlayed());assertFalse(catalog.findDetails(id("c")).isPlayed());
    }

    public void testManualDownloadsPersistAcrossReopenAndOldAttemptsCannotFinishRetry() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedEpisode(db,"a",1);seedEpisode(db,"b",1);
        assertEquals(io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.Enqueued.ADDED,catalog.enqueueManualDownload(id("b")));
        catalog.enqueueManualDownload(id("a"));
        assertEquals(io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.Enqueued.EXISTING,catalog.enqueueManualDownload(id("b")));
        var old=catalog.claimManualDownload();assertEquals(id("b"),old.mediaId());assertNull(catalog.claimManualDownload());
        catalog.close();catalog=new EpisodeCatalogDatabase(context);
        assertEquals(io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.State.ACTIVE,catalog.listManualDownloads().get(0).state());
        catalog.recoverInterruptedManualDownloads();var retry=catalog.claimManualDownload();
        assertEquals(old.attempt()+1,retry.attempt());catalog.finishManualDownload(old,null);
        assertTrue(catalog.canRunManualDownload(retry));
        catalog.cancelManualDownload(id("b"));catalog.enqueueManualDownload(id("b"));
        catalog.finishManualDownload(retry,io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException.Reason.FETCH_FAILED);
        assertEquals(2,catalog.listManualDownloads().size());assertEquals(id("a"),catalog.claimManualDownload().mediaId());
    }

    public void testManualRetryFailureRollsBackAndArchiveRevokesExecution() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedEpisode(db,"a",1);
        catalog.enqueueManualDownload(id("a"));var request=catalog.claimManualDownload();
        catalog.finishManualDownload(request,io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException.Reason.ACCESS_DENIED);
        db.execSQL("CREATE TEMP TRIGGER reject_request BEFORE INSERT ON manual_download_requests BEGIN SELECT RAISE(ABORT,'Injected failure'); END");
        try{catalog.enqueueManualDownload(id("a"));fail("Retry failure must roll back");}catch(java.io.IOException expected){}
        assertEquals(request.id(),catalog.listManualDownloads().get(0).request().id());
        assertEquals(io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.State.FAILED,catalog.listManualDownloads().get(0).state());
        db.execSQL("DROP TRIGGER reject_request");catalog.enqueueManualDownload(id("a"));var retry=catalog.claimManualDownload();
        assertTrue(catalog.canRunManualDownload(retry));db.execSQL("UPDATE subscriptions SET is_subscribed=0");
        assertFalse(catalog.canRunManualDownload(retry));catalog.cancelManualDownload(id("a"));
        assertEquals(io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.Enqueued.UNAVAILABLE,catalog.enqueueManualDownload(id("a")));
    }

    public void testManualRequestCapacityCountsFailuresAndCancellationFreesSlot() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedEpisode(db,"a",1);
        ContentValues values=new ContentValues();
        try(var c=db.rawQuery("SELECT * FROM episodes LIMIT 1",null)){assertTrue(c.moveToFirst());android.database.DatabaseUtils.cursorRowToContentValues(c,values);}
        for(int i=0;i<101;i++){
            String identity="episode-"+String.format(java.util.Locale.ROOT,"%064x",i);
            values.put("media_id",identity);db.insertOrThrow("episodes",null,values);
            var result=catalog.enqueueManualDownload(identity);
            assertEquals(i<100?io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.Enqueued.ADDED
                    :io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.Enqueued.FULL,result);
        }
        var first=catalog.claimManualDownload();catalog.finishManualDownload(first,io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException.Reason.QUOTA_EXCEEDED);
        assertEquals(100,catalog.listManualDownloads().size());catalog.enqueueManualDownload(first.mediaId());
        assertEquals(100,catalog.listManualDownloads().size());catalog.cancelManualDownload(first.mediaId());
        assertEquals(99,catalog.listManualDownloads().size());
    }

    public void testManualOfflineClearKeepsFailuresAndCompletedMetadataRemovesStaleRequest() throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedEpisode(db,"a",1);seedEpisode(db,"b",1);
        catalog.enqueueManualDownload(id("a"));catalog.enqueueManualDownload(id("b"));
        var failed=catalog.claimManualDownload();catalog.finishManualDownload(failed,io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadException.Reason.FETCH_FAILED);
        catalog.clearPendingManualDownloads();assertEquals(1,catalog.listManualDownloads().size());
        db.execSQL("UPDATE episodes SET downloaded=1 WHERE media_id=?",new Object[]{id("a")});
        catalog.clearCompletedManualDownloads();assertTrue(catalog.listManualDownloads().isEmpty());
    }

    public void testQueueBatchKeepsVisibleOrderWithPreviouslyQueuedItems()throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);
        for(String letter:java.util.List.of("a","b","c","d"))seedEpisode(db,letter,1);
        catalog.append(id("a"));catalog.append(id("d"));
        var group=new io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest(java.util.List.of(id("b"),id("a"),id("c")));
        assertEquals(3,catalog.queueBatch(group,false));
        assertEquals(java.util.List.of(id("d"),id("b"),id("a"),id("c")),queueIds());
        group=new io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest(java.util.List.of(id("c"),id("a")));
        assertEquals(2,catalog.queueBatch(group,true));
        assertEquals(java.util.List.of(id("c"),id("a"),id("d"),id("b")),queueIds());
    }
    public void testQueueBatchFailureRollsBackEarlierNestedMutation()throws Exception {
        SQLiteDatabase db=catalog.getWritableDatabase();seedSubscription(db,1);seedEpisode(db,"a",1);seedEpisode(db,"b",1);
        catalog.append(id("b"));
        var group=new io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest(java.util.List.of(id("a"),id("c")));
        try{catalog.queueBatch(group,false);fail("Missing target must roll back whole group");}catch(java.io.IOException expected){}
        assertEquals(java.util.List.of(id("b")),queueIds());
    }
    private java.util.List<String> queueIds()throws Exception {
        java.util.List<String> ids=new java.util.ArrayList<>();for(var entry:catalog.listQueue())ids.add(entry.getMediaId());return ids;
    }

    static void seedSubscription(SQLiteDatabase db, long id) {
        ContentValues values = new ContentValues();
        for (String key : new String[] {"title", "author", "notes", "website_url", "artwork_url", "categories"}) {
            values.put(key, "");
        }
        values.put("id", id); values.put("title", "Podcast " + id);
        values.put("subscription_url", "https://pod.example/" + id);
        values.put("final_feed_url", "https://pod.example/" + id);
        values.put("created_at", 1); values.put("updated_at", 1);
        db.insertOrThrow("subscriptions", null, values);
    }

    static void seedEpisode(SQLiteDatabase db, String letter, long podcast) {
        ContentValues values = new ContentValues();
        for (String key : new String[] {"title", "author", "notes", "website_url", "chapter_url", "published", "categories"}) {
            values.put(key, "");
        }
        values.put("media_id", id(letter)); values.put("subscription_id", podcast);
        values.put("audio_url", "https://media.example/" + letter + ".mp3");
        values.put("mime_type", "audio/mpeg"); values.put("declared_length", -1);
        values.put("title", "Episode " + letter); values.put("duration_ms", 120_000);
        values.put("source_order", 0); values.put("last_seen_generation", 1);
        values.put("created_at", 1); values.put("updated_at", 1);
        db.insertOrThrow("episodes", null, values);
    }
}
