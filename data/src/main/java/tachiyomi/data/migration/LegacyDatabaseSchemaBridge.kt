package tachiyomi.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Bridges the database created by Koharia's pre-SQLDelight Java database to the
 * baseline expected by the SQLDelight migrations.
 *
 * The bridge deliberately does not change PRAGMA user_version. SQLDelight must
 * still run every numbered migration so that views, triggers and newer fields
 * are installed in their normal order.
 */
class LegacyDatabaseSchemaBridge(
    private val context: Context,
) {

    fun prepare(): MigrationBackup? {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        if (!databaseFile.exists()) return null

        val database = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        return try {
            val version = database.readUserVersion()
            if (version >= CURRENT_SCHEMA_VERSION) return null

            database.checkpointWal()
            val backup = MigrationBackup.create(databaseFile)
            database.beginTransaction()
            try {
                if (database.isLegacyJavaSchema()) {
                    database.bridgeLegacyTables()
                    logcat(LogPriority.INFO) {
                        "Legacy database bridge applied (user_version=$version)"
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            backup
        } catch (exception: Throwable) {
            logcat(LogPriority.ERROR, exception) {
                "Legacy database bridge failed (user_version=${database.readUserVersionSafely()})"
            }
            throw exception
        } finally {
            database.close()
        }
    }

    /** Called only after Database.Schema and all SQLDelight migrations succeed. */
    fun complete(backup: MigrationBackup?) {
        if (backup == null) return
        try {
            ensureCurrentTriggersAndIndexes()
            backup.delete()
        } catch (exception: Throwable) {
            // Do not hide a successfully completed migration because a repair
            // object could not be written. The backup remains available.
            logcat(LogPriority.ERROR, exception) {
                "Failed to finalize migrated database; keeping backup at ${backup.file.path}"
            }
        }
    }

    /** Records enough state to diagnose a failed SQLDelight migration without deleting data. */
    fun logMigrationFailure(backup: MigrationBackup?, exception: Throwable) {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val details = if (databaseFile.exists()) {
            runCatching {
                SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    val version = database.readUserVersionSafely()
                    "user_version=$version, next_migration=${(version + 1).coerceAtMost(CURRENT_SCHEMA_VERSION)}, " +
                        "tables=${database.readTableNames()}"
                }
            }.getOrDefault("database_inspection_failed")
        } else {
            "database_missing"
        }
        logcat(LogPriority.ERROR, exception) {
            "Database migration failed ($details, backup=${backup?.file?.path ?: "none"}); retry is safe"
        }
    }

    private fun ensureCurrentTriggersAndIndexes() {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        if (!databaseFile.exists()) return
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS library_favorite_index ON mangas(favorite) WHERE favorite = 1",
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS mangas_url_index ON mangas(url)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_mangas_source ON mangas(source)")
                database.execSQL("CREATE INDEX IF NOT EXISTS chapters_manga_id_index ON chapters(manga_id)")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS chapters_unread_by_manga_index ON chapters(manga_id, read) WHERE read = 0",
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_chapters_url ON chapters(url)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_manga_sync_manga_id ON manga_sync(manga_id)")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_mangas_categories_manga_id ON mangas_categories(manga_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_mangas_categories_category_id ON mangas_categories(category_id)",
                )
                database.execSQL("INSERT OR IGNORE INTO categories(_id, name, sort, flags) VALUES (0, '', -1, 0)")

                database.execSQL("DROP TRIGGER IF EXISTS system_category_delete_trigger")
                database.execSQL(
                    """
                    CREATE TRIGGER system_category_delete_trigger
                    BEFORE DELETE ON categories
                    BEGIN
                      SELECT CASE WHEN old._id <= 0
                        THEN RAISE(ABORT, 'System category cannot be deleted') END;
                    END
                    """.trimIndent(),
                )

                database.execSQL("DROP TRIGGER IF EXISTS update_last_favorited_at_mangas")
                database.execSQL(
                    """
                    CREATE TRIGGER update_last_favorited_at_mangas
                    AFTER UPDATE OF favorite ON mangas
                    BEGIN
                      UPDATE mangas SET favorite_modified_at = strftime('%s', 'now')
                      WHERE _id = new._id;
                    END
                    """.trimIndent(),
                )
                database.execSQL("DROP TRIGGER IF EXISTS update_last_modified_at_mangas")
                database.execSQL(
                    """
                    CREATE TRIGGER update_last_modified_at_mangas
                    AFTER UPDATE ON mangas
                    FOR EACH ROW
                    WHEN new.last_modified_at = old.last_modified_at
                    BEGIN
                      UPDATE mangas SET last_modified_at = strftime('%s', 'now')
                      WHERE _id = new._id;
                    END
                    """.trimIndent(),
                )
                database.execSQL("DROP TRIGGER IF EXISTS update_last_modified_at_chapters")
                database.execSQL(
                    """
                    CREATE TRIGGER update_last_modified_at_chapters
                    AFTER UPDATE ON chapters
                    FOR EACH ROW
                    WHEN new.last_modified_at = old.last_modified_at
                    BEGIN
                      UPDATE chapters SET last_modified_at = strftime('%s', 'now')
                      WHERE _id = new._id;
                    END
                    """.trimIndent(),
                )
                database.execSQL("DROP TRIGGER IF EXISTS insert_manga_category_update_version")
                database.execSQL(
                    """
                    CREATE TRIGGER insert_manga_category_update_version
                    AFTER INSERT ON mangas_categories
                    BEGIN
                      UPDATE mangas SET version = version + 1
                      WHERE _id = new.manga_id AND (SELECT is_syncing FROM mangas WHERE _id = new.manga_id) = 0;
                    END
                    """.trimIndent(),
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun SQLiteDatabase.isLegacyJavaSchema(): Boolean {
        // These columns were introduced in the SQLDelight baseline and are not
        // present in the v0.1.0/v0.1.1 Java schema. Checking structure avoids
        // trusting a stale or incorrectly restored user_version.
        return hasTable("mangas") && (
            !hasColumn("mangas", "next_update") ||
                !hasColumn("chapters", "scanlator") ||
                !hasColumn("manga_sync", "library_id")
            )
    }

    private fun SQLiteDatabase.bridgeLegacyTables() {
        addColumnIfMissing("mangas", "next_update INTEGER")
        addColumnIfMissing("mangas", "cover_last_modified INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("mangas", "date_added INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("mangas", "update_strategy INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("mangas", "calculate_interval INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("mangas", "last_modified_at INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("mangas", "favorite_modified_at INTEGER")

        addColumnIfMissing("chapters", "scanlator TEXT")
        addColumnIfMissing("chapters", "bookmark INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("chapters", "source_order INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("chapters", "last_modified_at INTEGER NOT NULL DEFAULT 0")

        addColumnIfMissing("manga_sync", "library_id INTEGER")
        addColumnIfMissing("manga_sync", "remote_url TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing("manga_sync", "start_date INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("manga_sync", "finish_date INTEGER NOT NULL DEFAULT 0")

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS history(
                _id INTEGER NOT NULL PRIMARY KEY,
                chapter_id INTEGER NOT NULL UNIQUE,
                last_read INTEGER,
                time_read INTEGER NOT NULL,
                FOREIGN KEY(chapter_id) REFERENCES chapters (_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS history_history_chapter_id_index ON history(chapter_id)")
        execSQL("CREATE INDEX IF NOT EXISTS idx_history_last_read ON history(last_read)")

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS excluded_scanlators(
                manga_id INTEGER NOT NULL,
                scanlator TEXT NOT NULL,
                FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS excluded_scanlators_manga_id_index ON excluded_scanlators(manga_id)")
        execSQL("CREATE INDEX IF NOT EXISTS idx_excluded_scanlators_scanlator ON excluded_scanlators(scanlator)")

        execSQL(
            """
            CREATE TABLE IF NOT EXISTS sources(
                _id INTEGER NOT NULL PRIMARY KEY,
                lang TEXT NOT NULL,
                name TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.addColumnIfMissing(table: String, definition: String) {
        if (!hasTable(table)) return
        val column = definition.substringBefore(' ').trim()
        if (!hasColumn(table, column)) execSQL("ALTER TABLE $table ADD COLUMN $definition")
    }

    private fun SQLiteDatabase.hasTable(table: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean = rawQuery(
        "PRAGMA table_info($table)",
        null,
    ).use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
            .any { it == column }
    }

    private fun SQLiteDatabase.readUserVersion(): Int = rawQuery("PRAGMA user_version", null).use {
        check(it.moveToFirst()) { "Unable to read database user_version" }
        it.getInt(0)
    }

    private fun SQLiteDatabase.readUserVersionSafely(): Int = runCatching { readUserVersion() }.getOrDefault(-1)

    private fun SQLiteDatabase.readTableNames(): List<String> = rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        null,
    ).use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

    private fun SQLiteDatabase.checkpointWal() {
        runCatching {
            rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                // rawQuery is lazy; advancing the cursor is required for the
                // checkpoint statement to actually execute before the backup.
                cursor.moveToFirst()
            }
        }
    }

    class MigrationBackup internal constructor(
        internal val file: File,
    ) {
        internal fun delete() {
            if (!file.delete() && file.exists()) {
                logcat(LogPriority.WARN) { "Unable to delete database migration backup ${file.path}" }
            }
        }

        companion object {
            internal fun create(databaseFile: File): MigrationBackup {
                val backup = File(databaseFile.parentFile, "$DATABASE_NAME.migration-backup")
                if (!backup.exists()) databaseFile.copyTo(backup, overwrite = false)
                return MigrationBackup(backup)
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "tachiyomi.db"
        private const val CURRENT_SCHEMA_VERSION = 17
    }
}
