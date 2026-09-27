"""SQLite contract checks for Kavita migrations 21–22; no device or user database access."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL_ROOT = ROOT / "data/src/main/sqldelight/tachiyomi"
SCHEMA = (SQL_ROOT / "data/kavita.sq").read_text(encoding="utf-8-sig")
MIGRATION = "\n".join((SQL_ROOT / f"migrations/{n}.sqm").read_text(encoding="utf-8-sig") for n in (21, 22))
QUERIES = dict(re.findall(r"(?m)^(\w+):\s*\n(.*?;)", SCHEMA, flags=re.S))


class KavitaMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("CREATE TABLE existing_user_data (value TEXT);"
                              "INSERT INTO existing_user_data VALUES ('preserved');")
        self.db.executescript(MIGRATION)

    def tearDown(self):
        self.db.close()

    def test_remote_history_is_monotonic_and_does_not_double_count_duration(self):
        schema = (SQL_ROOT / "data/history.sq").read_text(encoding="utf-8-sig")
        query = dict(re.findall(r"(?m)^(\w+):\s*\n(.*?;)", schema, flags=re.S))["upsertRemote"]
        self.db.execute("CREATE TABLE history(chapter_id INTEGER UNIQUE, last_read INTEGER, time_read INTEGER)")
        self.db.execute(query, {"chapterId": 1, "readAt": 100})
        self.db.execute("UPDATE history SET last_read=300, time_read=40 WHERE chapter_id=1")
        self.db.execute(query, {"chapterId": 1, "readAt": 200})
        self.assertEqual((300, 40), self.db.execute("SELECT last_read,time_read FROM history").fetchone())
        self.db.execute(query, {"chapterId": 1, "readAt": 400})
        self.db.execute(query, {"chapterId": 1, "readAt": 400})
        self.assertEqual((400, 40), self.db.execute("SELECT last_read,time_read FROM history").fetchone())

    def test_migration_matches_current_schema_and_preserves_existing_data(self):
        current = sqlite3.connect(":memory:")
        current.executescript(SCHEMA.split("getAnnotations:")[0].replace("import kotlin.Boolean;", "")
                              .replace(" AS Boolean", ""))
        try:
            for table in ("kavita_cache", "kavita_operation", "kavita_annotation"):
                self.assertEqual(current.execute(f"PRAGMA table_info({table})").fetchall(),
                                 self.db.execute(f"PRAGMA table_info({table})").fetchall())
            self.assertEqual([("preserved",)], self.db.execute("SELECT * FROM existing_user_data").fetchall())
        finally:
            current.close()

    def test_account_isolation_empty_cache_and_conditional_acknowledgement(self):
        for account in ("a", "b"):
            self.db.execute(QUERIES["putCache"], (1, account, "shelf/one", "1", "[]", 1, False))
            self.db.execute(QUERIES["putOperation"], (1, account, "progress/2", "{}", 2, True))
        self.db.execute(QUERIES["acknowledge"], (1, "a", "progress/2", 1))
        self.assertEqual(1, self.db.execute(QUERIES["getOperations"], (1, "a")).fetchone()[-1])
        self.db.execute(QUERIES["acknowledge"], (1, "a", "progress/2", 2))
        self.assertEqual(0, self.db.execute(QUERIES["getOperations"], (1, "a")).fetchone()[-1])
        self.assertEqual(1, self.db.execute(QUERIES["getOperations"], (1, "b")).fetchone()[-1])
        self.db.execute(QUERIES["invalidateGroup"], (1, "a", "shelf/%"))
        self.assertEqual(("[]", 1, 1), self.db.execute(QUERIES["getCache"], (1, "a", "shelf/one", "1")).fetchone()[1:])
        self.assertEqual(("[]", 1, 0), self.db.execute(QUERIES["getCache"], (1, "b", "shelf/one", "1")).fetchone()[1:])
        self.db.execute(QUERIES["removeConnectionCache"], (1,))
        self.assertEqual([], self.db.execute(QUERIES["getEntries"], (1, "a", "shelf/one")).fetchall())

    def test_annotation_mapping_is_unique_scoped_and_cleanup_keeps_other_connections(self):
        for connection, account in [(1, "a"), (1, "b"), (2, "a")]:
            self.db.execute(QUERIES["putAnnotation"], (connection, account, "local", 7, 3, "original", 1, True))
        self.db.execute(QUERIES["putAnnotation"], (1, "a", "local", 7, 3, "edited", 2, True))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(QUERIES["putAnnotation"], (1, "a", "other", 7, 3, "collision", 1, True))
        rows = self.db.execute(QUERIES["getAnnotations"], (1, "a", None)).fetchall()
        self.assertEqual([("local", 7, 3, "edited", 2, 1)], rows)
        self.assertEqual([], self.db.execute(QUERIES["getAnnotations"], (1, "a", 8)).fetchall())
        self.db.execute(QUERIES["removeConnectionAnnotations"], (1,))
        self.assertEqual([], self.db.execute(QUERIES["getAnnotations"], (1, "b", None)).fetchall())
        self.assertEqual(1, len(self.db.execute(QUERIES["getAnnotations"], (2, "a", 7)).fetchall()))


if __name__ == "__main__":
    unittest.main()
