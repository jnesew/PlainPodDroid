"""Execute compiled production CatalogSql against standard-library SQLite.

This host gate supplements Android adapter tests; it is not Android execution.
Requires Python 3 and the project's Java 17 toolchain, without extra packages.
"""
import base64
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = Path(__file__).parent / "fixtures" / "catalog-v9.sql"

EXPORTER = r"""
import io.github.jnesew.plainpoddroid.core.catalog.storage.CatalogSql;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
public final class SqlExport {
    static void emit(String key, String sql) {
        System.out.println(key + "\t" + Base64.getEncoder().encodeToString(
                sql.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        emit("LIST_OLDEST", CatalogSql.listEpisodesSql(
                io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery.Filter.ALL,
                io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery.Order.OLDEST_FIRST));
        int i = 0;
        for (String sql : CatalogSql.createStatements()) emit("create_" + i++, sql);
        for (var field : CatalogSql.class.getFields()) {
            if (field.getType() == String.class) emit(field.getName(), (String) field.get(null));
        }
        for (int version = 1; version < 15; version++) {
            try {
                var method = CatalogSql.class.getMethod("migrateVersion" + version
                        + "To" + (version + 1) + "Statements");
                i = 0;
                for (Object sql : (List<?>) method.invoke(null)) {
                    emit("migration_" + version + "_" + i++, (String) sql);
                }
            } catch (NoSuchMethodException absent) { }
        }
    }
}
"""


def production_sql():
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not javac or not java:
        raise RuntimeError("Java 17 javac/java are required; set JAVA_HOME.")
    with tempfile.TemporaryDirectory(prefix="plainpod-sql-") as directory:
        target = Path(directory)
        source = target / "SqlExport.java"
        source.write_text(EXPORTER, encoding="utf-8")
        base = ROOT / "app/src/main/java/io/github/jnesew/plainpoddroid"
        sources = [base / name for name in (
            "core/catalog/storage/CatalogSql.java", "core/catalog/CatalogBrowseQuery.java",
            "core/security/SecurityLimits.java")]
        subprocess.run([javac, "-source", "17", "-target", "17", "-d", str(target),
                        str(source), *map(str, sources)], check=True, capture_output=True)
        result = subprocess.run([java, "-cp", str(target), "SqlExport"], check=True,
                                capture_output=True, text=True)
        return {key: base64.b64decode(value).decode("utf-8")
                for key, value in (line.split("\t", 1) for line in result.stdout.splitlines())}


def subscription(db, identity=1, active=1):
    db.execute("""INSERT INTO subscriptions(id,subscription_url,final_feed_url,title,author,
        notes,website_url,artwork_url,categories,created_at,updated_at,is_subscribed)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", (identity, f"https://pod.example/{identity}",
        f"https://pod.example/{identity}", f"Podcast {identity}", "Author", "", "", "", "",
        1, 1, active))


def episode(db, letter="a", podcast=1, **state):
    identity = "episode-" + letter * 64
    values = dict(media_id=identity, subscription_id=podcast,
                  audio_url=f"https://media.example/{letter}.mp3", mime_type="audio/mpeg",
                  declared_length=-1, title=f"Episode {letter}", author="Author", notes="",
                  website_url="", chapter_url="", published="", duration_ms=120000,
                  categories="", source_order=0, last_seen_generation=1, created_at=1,
                  updated_at=1)
    values.update(state)
    db.execute(f"INSERT INTO episodes({','.join(values)}) VALUES({','.join('?' for _ in values)})",
               tuple(values.values()))
    return identity


class CatalogSqlExecutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = production_sql()

    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.addCleanup(self.db.close)
        for key, statement in self.sql.items():
            if key.startswith("create_"):
                self.db.execute(statement)

    def request(self, identity):
        return self.db.execute("INSERT INTO manual_download_requests(media_id) VALUES(?)",(identity,)).lastrowid

    def test_refresh_marks_only_missing_current_episodes_and_preserves_user_state(self):
        subscription(self.db); subscription(self.db, 2)
        current = episode(self.db, "a", last_seen_generation=2, played=1, favorite=1,
                          downloaded=1, playback_position_ms=60000)
        missing = episode(self.db, "b", auto_download_pending=1)
        stale = episode(self.db, "c", is_stale=1)
        other = episode(self.db, "d", podcast=2)
        changed = self.db.execute(self.sql["MARK_MISSING_EPISODES_STALE"], (1, 2)).rowcount
        self.assertEqual(1, changed)
        states = dict(self.db.execute("SELECT media_id,is_stale FROM episodes"))
        self.assertEqual({current: 0, missing: 1, stale: 1, other: 0}, states)
        self.db.execute(self.sql["CLEAR_STALE_AUTO_DOWNLOAD_PENDING"], (1,))
        self.assertEqual((1, 1, 1, 60000), self.db.execute(
            "SELECT played,favorite,downloaded,playback_position_ms FROM episodes WHERE media_id=?", (current,)).fetchone())
        self.assertEqual(0, self.db.execute("SELECT auto_download_pending FROM episodes WHERE media_id=?", (missing,)).fetchone()[0])
        self.assertEqual(0, self.db.execute(self.sql["MARK_MISSING_EPISODES_STALE"], (1, 2)).rowcount)

    def test_full_back_catalogue_does_not_get_a_redundant_stale_write(self):
        subscription(self.db)
        # Count actual matched production UPDATEs instead of asserting source text.
        for i in range(1000):
            episode(self.db, "a", media_id="episode-" + format(i, "064x"), last_seen_generation=2)
        before = self.db.total_changes
        self.assertEqual(0, self.db.execute(self.sql["MARK_MISSING_EPISODES_STALE"], (1, 2)).rowcount)
        self.assertEqual(before, self.db.total_changes)

    def test_late_unmodified_refresh_cannot_update_a_new_membership_generation(self):
        subscription(self.db)
        url = "https://pod.example/1"
        target = self.db.execute(self.sql["FIND_SUBSCRIPTION_REFRESH_TARGET"], (1,)).fetchone()
        self.assertEqual(1, target[4])
        self.db.execute("UPDATE subscriptions SET generation=2,etag='newer',updated_at=100")
        args = ('older', '', 10, 1, url, url, target[4])
        self.assertEqual(0, self.db.execute(self.sql["RECORD_UNMODIFIED_SUBSCRIPTION"], args).rowcount)
        self.assertEqual(('newer', 100), self.db.execute("SELECT etag,updated_at FROM subscriptions").fetchone())
        self.assertEqual(1, self.db.execute(self.sql["RECORD_UNMODIFIED_SUBSCRIPTION"], args[:-1] + (2,)).rowcount)

    def test_manual_download_fifo_claims_one_and_failure_requires_explicit_retry(self):
        subscription(self.db); a=episode(self.db,"a"); b=episode(self.db,"b")
        self.request(b); self.request(a)
        self.assertEqual(1,self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"]).rowcount)
        active=self.db.execute("SELECT request_id,media_id,attempt FROM manual_download_requests WHERE state=1").fetchone()
        self.assertEqual(b,active[1]);self.assertEqual(1,active[2])
        self.assertEqual(0,self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"]).rowcount)
        self.db.execute(self.sql["FAIL_MANUAL_DOWNLOAD"],("LOGIN_REQUIRED",*active))
        self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
        self.assertEqual(a,self.db.execute("SELECT media_id FROM manual_download_requests WHERE state=1").fetchone()[0])
        self.assertEqual((2,"LOGIN_REQUIRED"),self.db.execute("SELECT state,failure FROM manual_download_requests WHERE media_id=?",(b,)).fetchone())

    def test_manual_request_attempt_blocks_late_completion_and_cancel_requeue_races(self):
        subscription(self.db); a=episode(self.db); self.request(a)
        self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
        old=self.db.execute("SELECT request_id,media_id,attempt FROM manual_download_requests").fetchone()
        self.db.execute(self.sql["RECOVER_MANUAL_DOWNLOADS"]);self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
        current=self.db.execute("SELECT request_id,media_id,attempt FROM manual_download_requests").fetchone()
        self.assertEqual(old[2]+1,current[2]);self.assertEqual([],self.db.execute(self.sql["RUNNABLE_MANUAL_DOWNLOAD"],old).fetchall())
        self.assertEqual(0,self.db.execute(self.sql["COMPLETE_MANUAL_DOWNLOAD"],old).rowcount)
        self.db.execute("DELETE FROM manual_download_requests"); newer=self.request(a)
        self.assertGreater(newer,current[0]);self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
        self.assertEqual(0,self.db.execute(self.sql["FAIL_MANUAL_DOWNLOAD"],("FETCH_FAILED",*current)).rowcount)
        self.assertEqual((newer,1),self.db.execute("SELECT request_id,state FROM manual_download_requests").fetchone())

    def test_manual_request_state_constraints_and_projection_do_not_accept_raw_diagnostics(self):
        subscription(self.db); a=episode(self.db);self.request(a)
        for statement in ("UPDATE manual_download_requests SET state=3", "UPDATE manual_download_requests SET attempt=-1",
                "UPDATE manual_download_requests SET attempt=1.5", "UPDATE manual_download_requests SET state=2",
                "UPDATE manual_download_requests SET failure='FETCH_FAILED'",
                "UPDATE manual_download_requests SET state=2,failure='https://private.example/secret'"):
            with self.assertRaises(sqlite3.IntegrityError):self.db.execute(statement)
        self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
        columns={c[1] for c in self.db.execute("PRAGMA table_info(manual_download_requests)")}
        self.assertEqual({"request_id","media_id","attempt","state","failure"},columns)
        cursor=self.db.execute(self.sql["LIST_MANUAL_DOWNLOADS"],(101,));self.assertEqual(1,len(cursor.fetchall()))
        self.assertTrue({"audio_url","subscription_url","notes","login_data"}.isdisjoint(c[0] for c in cursor.description))

    def test_manual_request_revalidates_archive_and_explicit_deletion_cascades(self):
        subscription(self.db); a=episode(self.db);self.request(a);self.db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
        active=self.db.execute("SELECT request_id,media_id,attempt FROM manual_download_requests").fetchone()
        self.assertEqual([(1,)],self.db.execute(self.sql["RUNNABLE_MANUAL_DOWNLOAD"],active).fetchall())
        self.db.execute("UPDATE subscriptions SET is_subscribed=0")
        self.assertEqual([],self.db.execute(self.sql["RUNNABLE_MANUAL_DOWNLOAD"],active).fetchall())
        self.db.execute("DELETE FROM subscriptions");self.assertEqual([],self.db.execute("SELECT * FROM manual_download_requests").fetchall())

    def test_automatic_pruning_preserves_pending_or_failed_manual_targets(self):
        subscription(self.db);a=episode(self.db,"a",is_stale=1,played=1);b=episode(self.db,"b",is_stale=1,played=1)
        self.request(a);self.db.execute(self.sql["PRUNE_GLOBAL"],(100,))
        self.assertEqual([(a,)],self.db.execute("SELECT media_id FROM episodes").fetchall())

    def test_manual_active_state_survives_file_reopen_then_can_be_recovered(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"catalog.db"
            with sqlite3.connect(path) as db:
                db.execute("PRAGMA foreign_keys=ON")
                for key,sql in self.sql.items():
                    if key.startswith("create_"):db.execute(sql)
                subscription(db);identity=episode(db)
                db.execute("INSERT INTO manual_download_requests(media_id) VALUES(?)",(identity,))
                db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
            with sqlite3.connect(path) as db:
                self.assertEqual((1,1),db.execute("SELECT state,attempt FROM manual_download_requests").fetchone())
                db.execute(self.sql["RECOVER_MANUAL_DOWNLOADS"]);db.execute(self.sql["CLAIM_MANUAL_DOWNLOAD"])
                self.assertEqual((1,2),db.execute("SELECT state,attempt FROM manual_download_requests").fetchone())

    def test_manual_migration_preserves_catalog_and_has_no_invented_requests(self):
        with sqlite3.connect(":memory:") as db:
            for key,sql in self.sql.items():
                if key.startswith("create_") and sql not in (self.sql["CREATE_MANUAL_DOWNLOADS"],self.sql["CREATE_MANUAL_DOWNLOAD_INDEX"]):db.execute(sql)
            subscription(db);episode(db,played=1,playback_position_ms=1234,last_listened_at=500)
            before=db.execute("SELECT * FROM episodes").fetchall()
            for key,sql in self.sql.items():
                if key.startswith("migration_14_"):db.execute(sql)
            self.assertEqual(before,db.execute("SELECT * FROM episodes").fetchall())
            self.assertEqual([],db.execute("SELECT * FROM manual_download_requests").fetchall())

    def test_backlog_confirmation_excludes_late_arrivals_and_preserves_listening_state(self):
        subscription(self.db, 1); subscription(self.db, 2)
        self.db.execute("UPDATE subscriptions SET generation=2,seen_generation=1,has_episode_baseline=1")
        a=episode(self.db, "a", published_at=100, first_seen_generation=2,
                  playback_position_ms=1234, last_listened_at=500, favorite=1)
        b=episode(self.db, "b", published_at=200, first_seen_generation=2)
        c=episode(self.db, "c", published_at=0, first_seen_generation=1)
        d=episode(self.db, "d", podcast=2, published_at=50)
        scope=(1,2,0,0)
        self.assertEqual(3,self.db.execute(self.sql["COUNT_PODCAST_PLAYED_BATCH"],scope).fetchone()[0])
        self.db.execute("UPDATE subscriptions SET generation=3 WHERE id=1")
        late=episode(self.db, "e", published_at=10, first_seen_generation=3)
        self.assertEqual(3,self.db.execute(self.sql["MARK_PODCAST_PLAYED_BATCH"],(999,*scope)).rowcount)
        states=dict(self.db.execute("SELECT media_id,played FROM episodes"))
        self.assertEqual({a:1,b:1,c:1,d:0,late:0},states)
        self.assertEqual((1234,500,1),self.db.execute("SELECT playback_position_ms,last_listened_at,favorite FROM episodes WHERE media_id=?",(a,)).fetchone())
        self.assertEqual(1,self.db.execute("SELECT seen_generation FROM subscriptions WHERE id=1").fetchone()[0])

    def test_older_backlog_uses_strict_date_cutoff_and_omits_unknown_dates(self):
        subscription(self.db)
        a=episode(self.db, "a", published_at=100)
        b=episode(self.db, "b", published_at=200)
        c=episode(self.db, "c", published_at=0)
        d=episode(self.db, "d", published_at=50, played=1)
        self.assertEqual(1,self.db.execute(self.sql["COUNT_PODCAST_PLAYED_BATCH"],(1,1,200,200)).fetchone()[0])
        self.db.execute(self.sql["MARK_PODCAST_PLAYED_BATCH"],(999,1,1,200,200))
        self.assertEqual({a:1,b:0,c:0,d:1},dict(self.db.execute("SELECT media_id,played FROM episodes")))

    def test_backlog_count_supports_android_text_bound_parameters(self):
        subscription(self.db)
        episode(self.db, "a", published_at=100)
        episode(self.db, "b", published_at=200)
        for before, expected in ((0, 2), (200, 1)):
            args = (1, 1, before, before)
            for bound in (args, tuple(map(str, args))):
                self.assertEqual(expected, self.db.execute(
                    self.sql["COUNT_PODCAST_PLAYED_BATCH"], bound).fetchone()[0])

    def test_speed_migration_does_not_invent_overrides_or_change_user_state(self):
        with sqlite3.connect(":memory:") as db:
            for key,sql in self.sql.items():
                if key.startswith("create_") and sql!=self.sql["CREATE_PODCAST_PLAYBACK_SETTINGS"]: db.execute(sql)
            subscription(db); episode(db, played=1, playback_position_ms=1234, last_listened_at=500)
            before=db.execute("SELECT * FROM episodes").fetchall()
            for key,sql in self.sql.items():
                if key.startswith("migration_13_"): db.execute(sql)
            self.assertEqual([],db.execute("SELECT * FROM podcast_playback_settings").fetchall())
            self.assertEqual(before,db.execute("SELECT * FROM episodes").fetchall())

    def test_fresh_schema_enforces_foreign_keys_and_bounds(self):
        subscription(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            episode(self.db, podcast=99)
        with self.assertRaises(sqlite3.IntegrityError):
            episode(self.db, duration_ms=-2)
        identity = episode(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO episode_queue VALUES(?,?,?)", (identity, -1, 1))

    def test_episode_orders_keep_undated_last_and_break_ties_stably(self):
        subscription(self.db)
        a = episode(self.db, "a", published_at=200, source_order=2)
        b = episode(self.db, "b", published_at=100, source_order=1)
        c = episode(self.db, "c", published_at=200, source_order=0)
        d = episode(self.db, "d", published_at=0, source_order=0)
        args = (1, "", "", 10, 0)
        self.assertEqual([c, a, b, d], [r[0] for r in self.db.execute(self.sql["LIST_EPISODES"], args)])
        self.assertEqual([b, c, a, d], [r[0] for r in self.db.execute(self.sql["LIST_OLDEST"], args)])
        self.db.execute(self.sql["SET_EPISODE_ORDER"], (1, 1))
        self.assertEqual(1, self.db.execute(self.sql["LIST_SUBSCRIPTIONS"], (10,)).fetchone()[15])

    def test_library_order_uses_content_not_refresh_time_and_has_stable_ties(self):
        for identity in (1, 2, 3, 4): subscription(self.db, identity)
        episode(self.db, "a", podcast=1, published_at=100)
        episode(self.db, "b", podcast=2, published_at=200)
        episode(self.db, "c", podcast=3, published_at=200)
        episode(self.db, "d", podcast=1, published_at=999, is_stale=1)
        self.db.execute("UPDATE subscriptions SET updated_at=99999 WHERE id=1")
        rows = self.db.execute(self.sql["LIST_SUBSCRIPTIONS"], (10,)).fetchall()
        self.assertEqual([2, 3, 1, 4], [r[0] for r in rows])
        self.assertEqual([200, 200, 100, 0], [r[14] for r in rows])

    def test_seen_snapshot_clears_only_known_arrivals_without_touching_playback(self):
        subscription(self.db)
        self.db.execute("UPDATE subscriptions SET generation=2,seen_generation=1,has_episode_baseline=1")
        old = episode(self.db, "a", first_seen_generation=1, played=0)
        new = episode(self.db, "b", first_seen_generation=2, last_seen_generation=2, published_at=1)
        rows = lambda: self.db.execute(self.sql["LIST_SUBSCRIPTIONS"], (10,)).fetchone()
        self.assertEqual((2, 1), (rows()[6], rows()[13]))
        self.db.execute("UPDATE subscriptions SET generation=3")
        episode(self.db, "c", first_seen_generation=3, last_seen_generation=3)
        self.db.execute(self.sql["MARK_SUBSCRIPTION_VIEWED"], (2, 1))
        self.assertEqual((3, 1), (rows()[6], rows()[13]))
        self.db.execute(self.sql["MARK_SUBSCRIPTION_VIEWED"], (1, 1))
        self.assertEqual(1, rows()[13])  # Late acknowledgement cannot move backwards.
        self.db.execute("UPDATE episodes SET updated_at=999,last_seen_generation=3 WHERE media_id=?", (new,))
        self.assertEqual(1, rows()[13])  # Metadata refresh cannot make an old episode new.
        self.db.execute(self.sql["MARK_SUBSCRIPTION_VIEWED"], (3, 1))
        self.assertEqual((3, 0), (rows()[6], rows()[13]))
        self.assertEqual(0, self.db.execute("SELECT played FROM episodes WHERE media_id=?", (old,)).fetchone()[0])

    def test_first_refresh_after_import_establishes_a_backlog_baseline(self):
        subscription(self.db)
        self.db.execute("UPDATE subscriptions SET generation=2")
        episode(self.db, "a", first_seen_generation=2)
        self.db.execute(self.sql["ESTABLISH_SEEN_BASELINE"], (1,))
        self.assertEqual(0, self.db.execute(self.sql["LIST_SUBSCRIPTIONS"], (10,)).fetchone()[13])
        self.db.execute("UPDATE subscriptions SET generation=3")
        episode(self.db, "b", first_seen_generation=3)
        self.db.execute(self.sql["ESTABLISH_SEEN_BASELINE"], (1,))
        self.assertEqual(1, self.db.execute(self.sql["LIST_SUBSCRIPTIONS"], (10,)).fetchone()[13])

    def test_browsing_migration_preserves_user_state_and_baselines_existing_episodes(self):
        with sqlite3.connect(":memory:") as old:
            old.executescript(FIXTURE.read_text(encoding="utf-8"))
            subscription(old, 1); subscription(old, 2)
            identity = episode(old, played=1, favorite=1, playback_position_ms=1234)
            old.execute("UPDATE subscriptions SET generation=7 WHERE id=1")
            for version in (9, 10, 11):
                for key, sql in self.sql.items():
                    if key.startswith(f"migration_{version}_"): old.execute(sql)
            self.assertEqual((1, 1, 1234), old.execute("SELECT played,favorite,playback_position_ms FROM episodes").fetchone())
            self.assertEqual([(7, 1, 0), (1, 0, 0)], old.execute("SELECT seen_generation,has_episode_baseline,episode_order FROM subscriptions ORDER BY id").fetchall())
            self.assertEqual(0, old.execute(self.sql["LIST_SUBSCRIPTIONS"], (10,)).fetchone()[13])
            with self.assertRaises(sqlite3.IntegrityError): old.execute("UPDATE subscriptions SET episode_order=2")

    def test_login_migration_preserves_state_and_adds_only_a_private_child_table(self):
        with sqlite3.connect(":memory:") as db:
            db.execute("PRAGMA foreign_keys=ON")
            for key, sql in self.sql.items():
                if key.startswith("create_") and sql not in (self.sql["CREATE_SUBSCRIPTION_LOGINS"], self.sql["CREATE_AUDIO_OWNER_INDEX"]):
                    db.execute(sql)
            subscription(db); identity = episode(db, played=1, favorite=1, playback_position_ms=1234)
            before = db.execute("SELECT * FROM episodes").fetchall()
            for key, sql in self.sql.items():
                if key.startswith("migration_10_"): db.execute(sql)
            self.assertEqual(before, db.execute("SELECT * FROM episodes").fetchall())
            self.assertEqual([], db.execute("SELECT * FROM subscription_logins").fetchall())
            self.assertEqual(identity, db.execute("SELECT media_id FROM episodes").fetchone()[0])

    def test_login_owner_lookup_bounds_ambiguity_archives_and_foreign_key_deletion(self):
        subscription(self.db, 1); subscription(self.db, 2)
        episode(self.db, "a", podcast=1); episode(self.db, "b", podcast=2)
        self.db.execute("UPDATE episodes SET audio_url='https://media.example/shared.mp3'")
        cipher = b"x" * 100
        self.db.execute("INSERT INTO subscription_logins(subscription_id,login_data) VALUES(?,?)", (1, cipher))
        plan = self.db.execute("EXPLAIN QUERY PLAN " + self.sql["FIND_AUDIO_LOGIN"], ("https://media.example/shared.mp3",)).fetchall()
        self.assertTrue(any("episodes_audio_owner" in row[-1] for row in plan))
        rows = self.db.execute(self.sql["FIND_AUDIO_LOGIN"], ("https://media.example/shared.mp3",)).fetchall()
        self.assertEqual(2, len(rows))
        self.assertEqual({1, 2}, {r[0] for r in rows})
        self.db.execute("UPDATE subscriptions SET is_subscribed=0 WHERE id=1")
        self.assertEqual([], self.db.execute(self.sql["FIND_FEED_LOGIN"], ("https://pod.example/1",)).fetchall())
        self.assertEqual([2], [r[0] for r in self.db.execute(self.sql["FIND_AUDIO_LOGIN"], ("https://media.example/shared.mp3",))])
        for blob in (b"x", b"x" * 16385):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("UPDATE subscription_logins SET login_data=?", (blob,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE subscription_logins SET feed_blocked=2")
        self.db.execute("DELETE FROM subscriptions WHERE id=1")
        self.assertEqual([], self.db.execute("SELECT * FROM subscription_logins").fetchall())

    def test_bound_search_is_literal_and_pagination_is_stable(self):
        subscription(self.db)
        first = episode(self.db, "a", title="A ' OR 1=1 --")
        second = episode(self.db, "b", title="B")
        query = self.sql["LIST_EPISODES"]
        self.assertEqual([first], [row[0] for row in self.db.execute(
            query, (1, "' OR 1=1 --", "' OR 1=1 --", 10, 0))])
        self.assertEqual([first], [row[0] for row in self.db.execute(query, (1, "", "", 1, 0))])
        self.assertEqual([second], [row[0] for row in self.db.execute(query, (1, "", "", 1, 1))])

    def test_podcast_information_uses_original_source_including_archives(self):
        subscription(self.db, 1, 0)
        episode(self.db)
        self.db.execute("UPDATE subscriptions SET final_feed_url='https://final.example/rss'")
        row = self.db.execute(self.sql["LIST_SUBSCRIPTIONS"], (10,)).fetchone()
        self.assertEqual(16, len(row))
        self.assertEqual(0, row[7])
        self.assertEqual("https://pod.example/1", row[12])

    def test_progress_and_favorite_updates_preserve_other_user_state(self):
        subscription(self.db)
        identity = episode(self.db, downloaded=1, favorite=1)
        self.db.execute(self.sql["SAVE_PLAYBACK_PROGRESS"], (60000, 1, 20, identity, 60000, 1))
        self.db.execute(self.sql["SET_EPISODE_FAVORITE"], (0, 30, identity, 0))
        self.assertEqual((60000, 1, 0, 1), self.db.execute(
            "SELECT playback_position_ms,played,favorite,downloaded FROM episodes").fetchone())

    def test_queue_transaction_rolls_back_partial_reorder(self):
        subscription(self.db)
        a, b = episode(self.db, "a"), episode(self.db, "b")
        self.db.executemany(self.sql["INSERT_EPISODE_QUEUE"], [(a, 0, 1), (b, 1, 1)])
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute(self.sql["PARK_EPISODE_QUEUE_ENTRY"], (b,))
            self.db.execute(self.sql["SHIFT_EPISODE_QUEUE_EARLIER"], (0, 1))
            self.db.execute(self.sql["SET_EPISODE_QUEUE_POSITION"], (-1, b))
        self.assertEqual([(0, a), (1, b)], self.db.execute(
            self.sql["LIST_EPISODE_QUEUE_POSITIONS"], (10,)).fetchall())

    def test_archived_delete_cascades_without_touching_other_podcast(self):
        subscription(self.db, 1, 0)
        subscription(self.db, 2)
        a, b = episode(self.db, "a"), episode(self.db, "b", podcast=2)
        self.db.executemany(self.sql["INSERT_EPISODE_QUEUE"], [(a, 0, 1), (b, 1, 1)])
        self.db.execute(self.sql["DELETE_ARCHIVED_SUBSCRIPTION"], (1,))
        self.assertEqual([(b,)], self.db.execute("SELECT media_id FROM episodes").fetchall())
        self.assertEqual([(b,)], self.db.execute("SELECT media_id FROM episode_queue").fetchall())

    def test_frozen_v9_fixture_is_executable_and_retains_user_state(self):
        with sqlite3.connect(":memory:") as old:
            old.execute("PRAGMA foreign_keys=ON")
            old.executescript(FIXTURE.read_text(encoding="utf-8"))
            subscription(old)
            identity = episode(old, played=1, favorite=1, downloaded=1, playback_position_ms=60000)
            old.execute(self.sql["INSERT_EPISODE_QUEUE"], (identity, 0, 1))
            self.assertEqual(9, old.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual((1, 1, 1, 60000), old.execute(
                "SELECT played,favorite,downloaded,playback_position_ms FROM episodes").fetchone())

    def complete(self, identity, when, size=5):
        url, mime, declared = self.db.execute(
            "SELECT audio_url,mime_type,declared_length FROM episodes WHERE media_id=?",
            (identity,)).fetchone()
        return self.db.execute(self.sql["MARK_EPISODE_DOWNLOAD_COMPLETE"],
                               (when, size, when, identity, url, mime, declared))

    def test_v9_migration_preserves_state_and_leaves_historical_metadata_unknown(self):
        with sqlite3.connect(":memory:") as old:
            old.execute("PRAGMA foreign_keys=ON")
            old.executescript(FIXTURE.read_text(encoding="utf-8"))
            subscription(old, 1, 0)
            identity = episode(old, played=1, favorite=1, downloaded=1, playback_position_ms=60000)
            old.execute(self.sql["INSERT_EPISODE_QUEUE"], (identity, 0, 1))
            for key, sql in self.sql.items():
                if key.startswith("migration_9_"): old.execute(sql)
            self.assertEqual((1, 1, 1, 60000, 0, 0), old.execute(
                "SELECT played,favorite,downloaded,playback_position_ms,download_completed_at,"
                "download_bytes FROM episodes").fetchone())
            self.assertEqual(0, old.execute("SELECT is_subscribed FROM subscriptions").fetchone()[0])
            self.assertEqual(identity, old.execute("SELECT media_id FROM episode_queue").fetchone()[0])
            old.execute(self.sql["LIST_DOWNLOADED_EPISODES"], (10, 0)).fetchall()
            with self.assertRaises(sqlite3.IntegrityError):
                old.execute("UPDATE episodes SET download_bytes=2147483649")

    def test_failed_migration_transaction_leaves_original_schema_and_data_intact(self):
        with sqlite3.connect(":memory:") as old:
            old.executescript(FIXTURE.read_text(encoding="utf-8"))
            subscription(old); episode(old, favorite=1)
            old.commit(); old.execute("BEGIN")
            with self.assertRaises(RuntimeError), old:
                old.execute(self.sql["migration_9_0"])
                raise RuntimeError("Injected upgrade failure")
            columns = [row[1] for row in old.execute("PRAGMA table_info(episodes)")]
            self.assertNotIn("download_completed_at", columns)
            self.assertEqual(1, old.execute("SELECT favorite FROM episodes").fetchone()[0])

    def test_recent_download_order_ignores_progress_and_favorite_updates(self):
        subscription(self.db, 1); subscription(self.db, 2)
        a = episode(self.db, "a")
        b = episode(self.db, "b", podcast=2, published="Date")
        c = episode(self.db, "c", downloaded=1)
        self.complete(a, 10, 5); self.complete(b, 20, 7)
        self.db.execute("UPDATE subscriptions SET is_subscribed=0 WHERE id=2")
        self.db.execute(self.sql["SAVE_PLAYBACK_PROGRESS"], (60000, 0, 300, a, 60000, 0))
        self.db.execute(self.sql["SET_EPISODE_FAVORITE"], (1, 400, a, 1))
        query = self.sql["LIST_DOWNLOADED_EPISODES"]
        cursor = self.db.execute(query, (10, 0)); rows = cursor.fetchall()
        self.assertEqual([b, a, c], [row[0] for row in rows])
        self.assertEqual(("Date", 2, "Podcast 2", "", 0, 20, 7), rows[0][10:17])
        self.assertEqual([a], [row[0] for row in self.db.execute(query, (1, 1))])
        self.assertTrue({"audio_url", "notes", "subscription_url", "final_feed_url"}.isdisjoint(
            column[0] for column in cursor.description))

    def test_compare_and_set_completion_rejects_changed_target_and_invalid_sizes(self):
        subscription(self.db); identity = episode(self.db)
        original = self.db.execute("SELECT audio_url FROM episodes").fetchone()[0]
        self.db.execute("UPDATE episodes SET audio_url='https://media.example/changed.mp3'")
        updated = self.db.execute(self.sql["MARK_EPISODE_DOWNLOAD_COMPLETE"],
            (10, 5, 10, identity, original, "audio/mpeg", -1)).rowcount
        self.assertEqual(0, updated)
        self.assertEqual((0, 0, 0), self.db.execute(
            "SELECT downloaded,download_completed_at,download_bytes FROM episodes").fetchone())
        with self.assertRaises(sqlite3.IntegrityError): self.complete(identity, 10, -1)
        with self.assertRaises(sqlite3.IntegrityError): self.complete(identity, -1, 5)
        with self.assertRaises(sqlite3.IntegrityError): self.complete(identity, 10, 2147483649)

    def test_zero_rss_size_retains_compare_and_set_and_stores_actual_download_bytes(self):
        subscription(self.db); identity = episode(self.db, declared_length=0)
        target = self.db.execute(self.sql["FIND_EPISODE_DOWNLOAD_TARGET"], (identity,)).fetchone()
        self.assertEqual(0, target[2])
        updated = self.db.execute(self.sql["MARK_EPISODE_DOWNLOAD_COMPLETE"],
            (10, 5, 10, identity, *target[:3])).rowcount
        self.assertEqual(1, updated)
        self.assertEqual((0, 1, 5), self.db.execute(
            "SELECT declared_length,downloaded,download_bytes FROM episodes").fetchone())
        self.db.execute("UPDATE episodes SET declared_length=1")
        self.assertEqual(0, self.db.execute(self.sql["MARK_EPISODE_DOWNLOAD_COMPLETE"],
            (20, 7, 20, identity, *target[:3])).rowcount)
        self.assertEqual((1, 5), self.db.execute(
            "SELECT declared_length,download_bytes FROM episodes").fetchone())

    def test_deletion_clears_completion_metadata_and_retention_respects_favorite(self):
        subscription(self.db); identity = episode(self.db, played=1)
        self.complete(identity, 10)
        self.db.execute(self.sql["MARK_EPISODE_DOWNLOAD_ABSENT"], (20, identity))
        self.assertEqual((0, 0, 0), self.db.execute(
            "SELECT downloaded,download_completed_at,download_bytes FROM episodes").fetchone())
        self.complete(identity, 30)
        self.db.execute("UPDATE episodes SET favorite=1")
        self.assertEqual(0, self.db.execute(self.sql["MARK_RETAINED_DOWNLOAD_ABSENT"], (40, identity)).rowcount)
        self.assertEqual((1, 30, 5), self.db.execute(
            "SELECT downloaded,download_completed_at,download_bytes FROM episodes").fetchone())
        self.db.execute("UPDATE episodes SET favorite=0")
        self.assertEqual(1, self.db.execute(self.sql["MARK_RETAINED_DOWNLOAD_ABSENT"], (50, identity)).rowcount)
        self.assertEqual((0, 0, 0), self.db.execute(
            "SELECT downloaded,download_completed_at,download_bytes FROM episodes").fetchone())

    def test_catalog_details_and_queue_project_the_same_parent_context(self):
        subscription(self.db); identity = episode(self.db, published="Date")
        self.db.execute(self.sql["INSERT_EPISODE_QUEUE"], (identity, 0, 1))
        self.db.execute("UPDATE subscriptions SET artwork_url='https://pod.example/cover.png',is_subscribed=0")
        expected = (1, "Podcast 1", "https://pod.example/cover.png", 0)
        self.assertEqual(expected, self.db.execute(self.sql["LIST_EPISODES"], (1, "", "", 10, 0)).fetchone()[11:15])
        self.assertEqual(expected, self.db.execute(self.sql["FIND_EPISODE_DETAILS"], (identity,)).fetchone()[14:18])
        self.assertEqual(expected, self.db.execute(self.sql["LIST_EPISODE_QUEUE"], (10,)).fetchone()[9:13])

    def test_podcast_speed_is_scoped_allowlisted_and_deleted_with_its_owner(self):
        subscription(self.db,1);subscription(self.db,2)
        a=episode(self.db,"a",podcast=1);b=episode(self.db,"b",podcast=2)
        self.assertIsNone(self.db.execute(self.sql["FIND_EPISODE_SPEED"],(a,)).fetchone()[0])
        self.db.execute("INSERT INTO podcast_playback_settings VALUES(1,150)")
        self.assertEqual(150,self.db.execute(self.sql["FIND_EPISODE_SPEED"],(a,)).fetchone()[0])
        self.assertIsNone(self.db.execute(self.sql["FIND_EPISODE_SPEED"],(b,)).fetchone()[0])
        for value in (0,49,101,301):
            with self.assertRaises(sqlite3.IntegrityError):self.db.execute("UPDATE podcast_playback_settings SET speed_percent=?",(value,))
        self.db.execute("DELETE FROM subscriptions WHERE id=1")
        self.assertEqual([],self.db.execute("SELECT * FROM podcast_playback_settings").fetchall())

    def test_history_records_listening_not_metadata_played_or_seek_updates(self):
        subscription(self.db)
        a = episode(self.db, "a", playback_position_ms=1000)
        b = episode(self.db, "b", played=1)
        self.assertEqual([], self.db.execute(self.sql["LIST_LISTENING_HISTORY"], (10, 0)).fetchall())
        self.db.execute(self.sql["RECORD_LISTENING"], (10, a, 10))
        self.db.execute(self.sql["RECORD_LISTENING"], (20, b, 20))
        self.db.execute("UPDATE episodes SET updated_at=999,favorite=1 WHERE media_id=?", (a,))
        self.db.execute(self.sql["SAVE_PLAYBACK_PROGRESS"], (5000, 0, 1000, a, 5000, 0))
        self.db.execute(self.sql["RECORD_LISTENING"], (5, b, 5))
        rows = self.db.execute(self.sql["LIST_LISTENING_HISTORY"], (10, 0)).fetchall()
        self.assertEqual([b, a], [r[0] for r in rows])
        self.assertEqual([20, 10], [r[15] for r in rows])
        self.db.execute(self.sql["RECORD_LISTENING"], (30, a, 30))
        self.assertEqual(a, self.db.execute(self.sql["LIST_LISTENING_HISTORY"], (1, 0)).fetchone()[0])
        self.assertEqual(1, self.db.execute("SELECT played FROM episodes WHERE media_id=?", (b,)).fetchone()[0])
        plan=self.db.execute("EXPLAIN QUERY PLAN "+self.sql["LIST_LISTENING_HISTORY"], (10,0)).fetchall()
        self.assertTrue(any("episodes_by_listening_time" in row[-1] for row in plan))

    def test_new_episode_view_matches_seen_counts_and_never_acknowledges_by_reading(self):
        subscription(self.db, 1); subscription(self.db, 2, 0)
        self.db.execute("UPDATE subscriptions SET generation=2,seen_generation=1,has_episode_baseline=1")
        a=episode(self.db,"a",first_seen_generation=2,published_at=10,played=1)
        episode(self.db,"b",first_seen_generation=1,published_at=30)
        episode(self.db,"c",first_seen_generation=2,is_stale=1)
        episode(self.db,"d",podcast=2,first_seen_generation=2,published_at=50)
        query=self.sql["LIST_NEW_EPISODES"]
        self.assertEqual([a],[r[0] for r in self.db.execute(query,(10,0))])
        self.assertEqual([a],[r[0] for r in self.db.execute(query,(10,0))])
        self.db.execute(self.sql["MARK_SUBSCRIPTION_VIEWED"],(2,1))
        self.assertEqual([],self.db.execute(query,(10,0)).fetchall())

    def test_in_progress_includes_retained_archives_and_excludes_completed(self):
        subscription(self.db,1);subscription(self.db,2,0)
        a=episode(self.db,"a",playback_position_ms=1000,last_listened_at=10)
        b=episode(self.db,"b",podcast=2,playback_position_ms=2000,last_listened_at=20,is_stale=1)
        episode(self.db,"c",playback_position_ms=1000,played=1,last_listened_at=100)
        episode(self.db,"d",playback_position_ms=0)
        self.assertEqual([b,a],[r[0] for r in self.db.execute(self.sql["LIST_IN_PROGRESS_EPISODES"],(10,0))])

    def test_listening_migration_preserves_progress_and_does_not_invent_history(self):
        with sqlite3.connect(":memory:") as old:
            old.executescript(FIXTURE.read_text(encoding="utf-8"));subscription(old)
            identity=episode(old,played=1,favorite=1,playback_position_ms=1234,updated_at=999)
            for version in (9,10,11,12):
                for key,sql in self.sql.items():
                    if key.startswith(f"migration_{version}_"):old.execute(sql)
            self.assertEqual((1,1,1234,0),old.execute("SELECT played,favorite,playback_position_ms,last_listened_at FROM episodes").fetchone())
            self.assertEqual([],old.execute(self.sql["LIST_LISTENING_HISTORY"],(10,0)).fetchall())
            old.execute(self.sql["RECORD_LISTENING"],(1000,identity,1000))
            self.assertEqual(identity,old.execute(self.sql["LIST_LISTENING_HISTORY"],(1,0)).fetchone()[0])

    def test_global_favorites_and_played_queries_include_archives_and_paginate(self):
        subscription(self.db, 1); subscription(self.db, 2, 0)
        a = episode(self.db, "a", favorite=1, updated_at=10)
        b = episode(self.db, "b", podcast=2, favorite=1, played=1, updated_at=20, last_listened_at=5)
        episode(self.db, "c")
        query = self.sql["LIST_FAVORITE_EPISODES"]
        self.assertEqual([b, a], [r[0] for r in self.db.execute(query, (10, 0))])
        self.assertEqual([a], [r[0] for r in self.db.execute(query, (1, 1))])
        self.assertEqual([b], [r[0] for r in self.db.execute(self.sql["LIST_LISTENING_HISTORY"], (10, 0))])
        self.assertEqual((2, "Podcast 2", "", 0), self.db.execute(query, (1, 0)).fetchone()[11:15])


if __name__ == "__main__":
    unittest.main()
