"""Exercise the actual SQLDelight statements using a temporary in-memory SQLite database."""
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "data/src/main/sqldelight/tachiyomi/data/lanraragi.sq"
MIGRATION = ROOT / "data/src/main/sqldelight/tachiyomi/migrations/17.sqm"
MIGRATION18 = ROOT / "data/src/main/sqldelight/tachiyomi/migrations/18.sqm"
TEXT = SCHEMA.read_text(encoding="utf-8-sig")
QUERIES = dict(re.findall(r"(?m)^(\w+):\s*\n(.*?)(?=^\w+:|\Z)", TEXT, re.S | re.M))


class CatalogStorageTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("CREATE TABLE existing_data(value TEXT); INSERT INTO existing_data VALUES ('keep');")
        self.db.executescript(MIGRATION.read_text(encoding="utf-8-sig"))
        if "initial_page" not in [row[1] for row in self.db.execute("PRAGMA table_info(lanraragi_read_state)")]:
            self.db.executescript(MIGRATION18.read_text(encoding="utf-8-sig"))

    def tearDown(self):
        self.db.close()

    def query(self, name, **params):
        return self.db.execute(QUERIES[name], params)

    def entry(self, connection=1, generation=1, value="old"):
        self.query("stageEntry", connectionId=connection, generation=generation, resourceId="archive", payload=value)

    def publish(self, connection=1, generation=1):
        self.query("publish", connectionId=connection, generation=generation, completedAt=100)

    def record(self, connection=1, page=1, unread=0, pending=1):
        self.query("record", connectionId=connection, archiveId="archive", pageIndex=page,
                   totalPages=10, readAt=200, localUnread=unread, pending=pending, initialPage=0)

    def test_reading_migration_preserves_pending_rows_with_or_without_bridge_column(self):
        for bridged in (False, True):
            db = sqlite3.connect(":memory:")
            db.executescript(MIGRATION.read_text(encoding="utf-8-sig"))
            db.execute("INSERT INTO lanraragi_read_state VALUES (9, 'book', 4, 10, 500, 0, 1, 7)")
            if bridged:
                db.execute("ALTER TABLE lanraragi_read_state ADD COLUMN initial_page INTEGER NOT NULL DEFAULT 0")
            db.executescript(MIGRATION18.read_text(encoding="utf-8-sig"))
            self.assertEqual((9, "book", 4, 10, 500, 0, 1, 7, 0),
                             db.execute("SELECT * FROM lanraragi_read_state").fetchone())
            db.close()

    def test_only_published_generation_is_visible(self):
        self.entry()
        self.assertEqual([], self.query("getEntries", connectionId=1).fetchall())
        self.publish()
        self.entry(generation=2, value="partial")
        self.assertEqual([("old",)], self.query("getEntries", connectionId=1).fetchall())
        self.query("abortEntries", connectionId=1, generation=2)
        self.query("abortEntries", connectionId=1, generation=1)
        self.assertEqual([("old",)], self.query("getEntries", connectionId=1).fetchall())

    def test_refresh_cannot_replace_pending_reading(self):
        self.record(page=4)
        self.entry()
        self.publish()
        self.entry(generation=2, value="new")
        self.publish(generation=2)
        self.query("removeOtherEntries", connectionId=1, generation=2)
        self.assertEqual([("new",)], self.query("getEntries", connectionId=1).fetchall())
        self.assertEqual(4, self.query("getReadStates", connectionId=1).fetchone()[2])

    def test_same_ids_are_isolated_between_connections(self):
        for connection in (1, 2):
            self.entry(connection=connection)
            self.publish(connection=connection)
            self.record(connection=connection)
        for statement in ("removeCatalog", "removeMembers", "removeSync", "removeReading"):
            self.query(statement, connectionId=1)
        self.assertEqual([], self.query("getEntries", connectionId=1).fetchall())
        self.assertEqual(1, len(self.query("getEntries", connectionId=2).fetchall()))
        self.assertEqual(1, len(self.query("getReadStates", connectionId=2).fetchall()))

    def test_stale_acknowledgement_preserves_newer_event(self):
        self.record()
        self.record(page=2)
        self.query("acknowledge", connectionId=1, archiveId="archive", revision=1)
        state = self.query("getReadStates", connectionId=1).fetchone()
        self.assertEqual((2, 1, 2), (state[2], state[6], state[7]))
        self.query("acknowledge", connectionId=1, archiveId="archive", revision=2)
        self.assertEqual(0, self.query("getReadStates", connectionId=1).fetchone()[6])

    def test_unread_override_cancels_pending_upload(self):
        self.record(page=9)
        self.record(page=0, unread=1, pending=0)
        state = self.query("getReadStates", connectionId=1).fetchone()
        self.assertEqual((0, 1, 0), (state[2], state[5], state[6]))

    def test_migration_is_idempotent_and_matches_fresh_schema(self):
        self.db.executescript(MIGRATION.read_text(encoding="utf-8-sig"))
        if "initial_page" not in [row[1] for row in self.db.execute("PRAGMA table_info(lanraragi_read_state)")]:
            self.db.executescript(MIGRATION18.read_text(encoding="utf-8-sig"))
        self.assertEqual("keep", self.db.execute("SELECT value FROM existing_data").fetchone()[0])
        fresh = sqlite3.connect(":memory:")
        fresh.executescript(TEXT.split("getEntries:")[0])
        for table in ("lanraragi_catalog", "lanraragi_members", "lanraragi_sync", "lanraragi_read_state"):
            self.assertEqual(fresh.execute(f"PRAGMA table_info({table})").fetchall(),
                             self.db.execute(f"PRAGMA table_info({table})").fetchall())
        fresh.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
