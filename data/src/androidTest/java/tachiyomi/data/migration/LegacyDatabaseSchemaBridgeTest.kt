package tachiyomi.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyDatabaseSchemaBridgeTest {

    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "schema-guard-${System.nanoTime()}.db"
        createCurrentDatabase()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        context.getDatabasePath(databaseName).parentFile
            ?.listFiles { file -> file.name.startsWith("$databaseName.migration-backup-") }
            ?.forEach(File::delete)
    }

    @Test
    fun repairsMissingAuxiliaryTablesAtCurrentVersion() {
        auxiliaryTables.forEach { table ->
            writableDatabase().use { database -> database.execSQL("DROP TABLE $table") }

            runSchemaGuard()

            readableDatabase().use { database ->
                assertTrue("Expected $table to be repaired", database.hasObject("table", table))
            }
        }
    }

    @Test
    fun repairsMissingKomgaIndexesAtCurrentVersion() {
        writableDatabase().use { database ->
            komgaIndexes.forEach { index -> database.execSQL("DROP INDEX $index") }
        }

        runSchemaGuard()

        readableDatabase().use { database ->
            komgaIndexes.forEach { index ->
                assertTrue("Expected $index to be repaired", database.hasObject("index", index))
            }
        }
    }

    @Test
    fun repairsMissingViewAndTriggerAtCurrentVersion() {
        writableDatabase().use { database ->
            database.execSQL("DROP VIEW libraryView")
            database.execSQL("DROP TRIGGER update_chapter_and_manga_version")
        }

        runSchemaGuard()

        readableDatabase().use { database ->
            assertTrue(database.hasObject("view", "libraryView"))
            assertTrue(database.hasObject("trigger", "update_chapter_and_manga_version"))
        }
    }

    @Test
    fun migratesLegacyJavaVersionOneAndPreservesRows() {
        context.deleteDatabase(databaseName)
        createLegacyJavaDatabase()

        migratePreparedDatabase()

        readableDatabase().use { database ->
            assertEquals(Database.Schema.version.toInt(), database.userVersion())
            assertEquals("Legacy title", database.singleString("SELECT title FROM mangas WHERE _id = 1"))
            assertEquals("Legacy chapter", database.singleString("SELECT name FROM chapters WHERE _id = 2"))
            assertTrue(database.hasObject("table", "komga_shared_download_matches"))
            assertTrue(database.hasObject("table", "epub_remote_progress_cache"))
        }
    }

    @Test
    fun migratesPublishedSqlDelightVersionTwelve() {
        writableDatabase().use { database ->
            (auxiliaryTables - "extension_repos").forEach { table -> database.execSQL("DROP TABLE $table") }
            database.execSQL("PRAGMA user_version = 12")
        }

        migratePreparedDatabase()

        readableDatabase().use { database -> assertCurrentSchema(database) }
    }

    @Test
    fun migratesPublishedSqlDelightVersionThirteen() {
        writableDatabase().use { database ->
            auxiliaryTables.filter { it.startsWith("epub_") }.forEach { table ->
                database.execSQL("DROP TABLE $table")
            }
            database.execSQL("PRAGMA user_version = 13")
        }

        migratePreparedDatabase()

        readableDatabase().use { database -> assertCurrentSchema(database) }
    }

    @Test
    fun completesPartiallyAppliedNonIdempotentMigrations() {
        listOf(2, 4, 5, 11).forEach { migration ->
            writableDatabase().use { database ->
                database.execSQL("DROP VIEW IF EXISTS historyView")
                database.execSQL("DROP VIEW IF EXISTS libraryView")
                database.execSQL("DROP VIEW IF EXISTS updatesView")
                when (migration) {
                    2 -> {
                        database.execSQL("ALTER TABLE manga_sync DROP COLUMN private")
                        database.execSQL("ALTER TABLE mangas DROP COLUMN notes")
                        database.execSQL("ALTER TABLE mangas DROP COLUMN memo")
                        database.execSQL("ALTER TABLE chapters DROP COLUMN memo")
                    }
                    4 -> {
                        database.execSQL("ALTER TABLE mangas DROP COLUMN notes")
                        database.execSQL("ALTER TABLE mangas DROP COLUMN memo")
                        database.execSQL("ALTER TABLE chapters DROP COLUMN memo")
                    }
                    5 -> {
                        database.execSQL("ALTER TABLE mangas DROP COLUMN memo")
                        database.execSQL("ALTER TABLE chapters DROP COLUMN memo")
                    }
                }
                database.execSQL("PRAGMA user_version = $migration")
            }

            migratePreparedDatabase()

            readableDatabase().use { database -> assertCurrentSchema(database) }
        }
    }

    @Test
    fun retainsBackupWhenCoreTableIsMissing() {
        writableDatabase().use { database -> database.execSQL("DROP TABLE mangas") }
        val bridge = schemaBridge()

        val failure = runCatching { bridge.prepare() }.exceptionOrNull()

        assertNotNull(failure)
        val backups = context.getDatabasePath(databaseName).parentFile
            ?.listFiles { file -> file.name.startsWith("$databaseName.migration-backup-") }
            .orEmpty()
        assertTrue("Expected a retained migration backup", backups.isNotEmpty())
    }

    private fun runSchemaGuard() {
        val bridge = schemaBridge()
        val backup = bridge.prepare()
        assertNotNull("Expected the schema guard to create a repair backup", backup)
        bridge.complete(backup)
    }

    private fun migratePreparedDatabase() {
        val bridge = schemaBridge()
        val backup = bridge.prepare()
        assertNotNull("Expected the migration to create a backup", backup)
        val driver = openAndInitializeDriver()
        try {
            bridge.complete(backup)
        } finally {
            driver.close()
        }
    }

    private fun schemaBridge(): LegacyDatabaseSchemaBridge {
        return LegacyDatabaseSchemaBridge(
            context = context,
            expectedSchemaVersion = Database.Schema.version.toInt(),
            databaseName = databaseName,
        )
    }

    private fun createCurrentDatabase() {
        openAndInitializeDriver().close()
    }

    private fun openAndInitializeDriver(): AndroidxSqliteDriver {
        val driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, databaseName),
            schema = Database.Schema,
            configuration = AndroidxSqliteConfiguration(
                isForeignKeyConstraintsEnabled = true,
            ),
        )
        runBlocking {
            driver.executeQuery(
                identifier = null,
                sql = "SELECT 1",
                mapper = { QueryResult.Unit },
                parameters = 0,
                binders = null,
            ).await()
        }
        return driver
    }

    private fun createLegacyJavaDatabase() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { database ->
            legacyJavaCreateStatements.forEach(database::execSQL)
            database.execSQL(
                """
                INSERT INTO mangas(
                    _id, source, url, artist, author, description, genre, title, status,
                    thumbnail_url, favorite, last_update, initialized, viewer, chapter_flags
                ) VALUES (1, 1, '/legacy', NULL, NULL, NULL, NULL, 'Legacy title', 1, NULL, 1, 0, 1, 0, 0)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO chapters(
                    _id, manga_id, url, name, read, last_page_read, chapter_number, date_fetch, date_upload
                ) VALUES (2, 1, '/legacy/chapter', 'Legacy chapter', 0, 0, 1, 0, 0)
                """.trimIndent(),
            )
            database.execSQL("INSERT INTO categories(_id, name, sort, flags) VALUES (3, 'Legacy category', 0, 0)")
            database.execSQL("INSERT INTO mangas_categories(_id, manga_id, category_id) VALUES (4, 1, 3)")
            database.execSQL(
                """
                INSERT INTO manga_sync(
                    _id, manga_id, sync_id, remote_id, title, last_chapter_read,
                    total_chapters, status, score
                ) VALUES (5, 1, 1, 1, 'Legacy title', 1, 1, 1, 0)
                """.trimIndent(),
            )
            database.execSQL("PRAGMA user_version = 1")
        }
    }

    private fun writableDatabase(): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
    }

    private fun readableDatabase(): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    private fun SQLiteDatabase.hasObject(type: String, name: String): Boolean {
        return rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ? LIMIT 1",
            arrayOf(type, name),
        ).use { it.moveToFirst() }
    }

    private fun SQLiteDatabase.userVersion(): Int {
        return rawQuery("PRAGMA user_version", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun SQLiteDatabase.singleString(sql: String): String {
        return rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
    }

    private fun assertCurrentSchema(database: SQLiteDatabase) {
        assertEquals(Database.Schema.version.toInt(), database.userVersion())
        auxiliaryTables.forEach { table -> assertTrue(database.hasObject("table", table)) }
        komgaIndexes.forEach { index -> assertTrue(database.hasObject("index", index)) }
    }

    private companion object {
        val auxiliaryTables = listOf(
            "extension_repos",
            "komga_shared_download_matches",
            "epub_progress",
            "epub_bookmark",
            "epub_pagination_cache",
            "epub_remote_progress_cache",
        )

        val komgaIndexes = listOf(
            "idx_komga_shared_download_matches_book_url",
            "idx_komga_shared_download_matches_file_hash",
            "idx_komga_shared_download_matches_local_relative_path",
        )

        val legacyJavaCreateStatements = listOf(
            """
            CREATE TABLE mangas(
                _id INTEGER NOT NULL PRIMARY KEY, source INTEGER NOT NULL, url TEXT NOT NULL,
                artist TEXT, author TEXT, description TEXT, genre TEXT, title TEXT NOT NULL,
                status INTEGER NOT NULL, thumbnail_url TEXT, favorite INTEGER NOT NULL,
                last_update INTEGER, initialized INTEGER NOT NULL, viewer INTEGER NOT NULL,
                chapter_flags INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE chapters(
                _id INTEGER NOT NULL PRIMARY KEY, manga_id INTEGER NOT NULL, url TEXT NOT NULL,
                name TEXT NOT NULL, read INTEGER NOT NULL, last_page_read INTEGER NOT NULL,
                chapter_number REAL NOT NULL, date_fetch INTEGER NOT NULL, date_upload INTEGER NOT NULL,
                FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE TABLE manga_sync(
                _id INTEGER NOT NULL PRIMARY KEY, manga_id INTEGER NOT NULL, sync_id INTEGER NOT NULL,
                remote_id INTEGER NOT NULL, title TEXT NOT NULL, last_chapter_read INTEGER NOT NULL,
                total_chapters INTEGER NOT NULL, status INTEGER NOT NULL, score REAL NOT NULL,
                UNIQUE(manga_id, sync_id) ON CONFLICT REPLACE,
                FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE TABLE categories(_id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, sort INTEGER NOT NULL, flags INTEGER NOT NULL)",
            """
            CREATE TABLE mangas_categories(
                _id INTEGER NOT NULL PRIMARY KEY, manga_id INTEGER NOT NULL, category_id INTEGER NOT NULL,
                FOREIGN KEY(category_id) REFERENCES categories(_id) ON DELETE CASCADE,
                FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX mangas_url_index ON mangas(url)",
            "CREATE INDEX mangas_favorite_index ON mangas(favorite)",
            "CREATE INDEX chapters_manga_id_index ON chapters(manga_id)",
        )
    }
}
