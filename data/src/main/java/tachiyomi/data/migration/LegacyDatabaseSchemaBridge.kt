package tachiyomi.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Repairs upgrade-safe schema gaps before SQLDelight opens the database, then
 * validates the complete schema after its numbered migrations have run.
 */
class LegacyDatabaseSchemaBridge(
    private val context: Context,
    private val expectedSchemaVersion: Int,
    private val databaseName: String = DATABASE_NAME,
) {

    private var pendingBackup: MigrationBackup? = null

    init {
        require(expectedSchemaVersion > 0) { "Expected database schema version must be positive" }
    }

    fun prepare(): MigrationBackup? {
        val databaseFile = context.getDatabasePath(databaseName)
        if (!databaseFile.exists()) return null

        val database = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        return try {
            val version = database.readUserVersion()
            val tableNames = database.readTableNames().toSet()
            if (version == 0 && tableNames.isEmpty()) return null

            val isLegacyJavaSchema = database.isLegacyJavaSchema()
            val inspection = database.inspectSchema(version, isLegacyJavaSchema)
            val requiresWork = version != expectedSchemaVersion ||
                isLegacyJavaSchema ||
                inspection.repairableIssues.isNotEmpty() ||
                inspection.criticalIssues.isNotEmpty()
            if (!requiresWork) return null

            database.checkpointWal()
            val backup = MigrationBackup.create(databaseFile)
            pendingBackup = backup

            check(inspection.criticalIssues.isEmpty()) {
                "Database schema has non-repairable gaps: ${inspection.criticalIssues.joinToString()}"
            }

            database.beginTransaction()
            try {
                if (isLegacyJavaSchema) {
                    database.bridgeLegacyTables()
                    logcat(LogPriority.INFO) {
                        "Legacy database bridge applied (user_version=$version)"
                    }
                }

                val normalizedVersion = database.finishPartiallyAppliedMigration(version)
                database.ensureCoreColumnsForVersion(normalizedVersion)
                database.ensureAuxiliaryTablesAndColumns()
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }

            logcat(LogPriority.INFO) {
                "Database schema guard prepared user_version=${database.readUserVersionSafely()}, " +
                    "target=$expectedSchemaVersion, repairs=${inspection.repairableIssues}"
            }
            backup
        } catch (exception: Throwable) {
            logcat(LogPriority.ERROR, exception) {
                "Database schema guard failed before SQLDelight open " +
                    "(user_version=${database.readUserVersionSafely()}, backup=${pendingBackup?.file?.path ?: "none"})"
            }
            throw exception
        } finally {
            database.close()
        }
    }

    /** Called only after the migration driver has completed and closed. */
    fun complete(backup: MigrationBackup?) {
        val effectiveBackup = backup ?: pendingBackup ?: return
        val databaseFile = context.getDatabasePath(databaseName)
        if (!databaseFile.exists()) return

        try {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.beginTransaction()
                try {
                    database.ensureCoreColumnsForVersion(expectedSchemaVersion)
                    database.ensureAuxiliaryTablesAndColumns()
                    database.ensureCurrentSchemaObjects()
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }

                val foreignKeysValid = database.validateCurrentSchema()
                if (foreignKeysValid) {
                    effectiveBackup.delete()
                    pendingBackup = null
                } else {
                    logcat(LogPriority.ERROR) {
                        "Database schema migrated but foreign key violations remain; " +
                            "keeping backup at ${effectiveBackup.file.path}"
                    }
                }
            }
        } catch (exception: Throwable) {
            logcat(LogPriority.ERROR, exception) {
                "Database schema validation failed; keeping backup at ${effectiveBackup.file.path}"
            }
            throw exception
        }
    }

    /** Records enough state to diagnose a failed migration without deleting data. */
    fun logMigrationFailure(backup: MigrationBackup?, exception: Throwable) {
        val databaseFile = context.getDatabasePath(databaseName)
        val effectiveBackup = backup ?: pendingBackup
        val details = if (databaseFile.exists()) {
            runCatching {
                SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    val version = database.readUserVersionSafely()
                    "user_version=$version, next_migration=${(version + 1).coerceAtMost(expectedSchemaVersion)}, " +
                        "tables=${database.readTableNames()}"
                }
            }.getOrDefault("database_inspection_failed")
        } else {
            "database_missing"
        }
        logcat(LogPriority.ERROR, exception) {
            "Database migration failed ($details, backup=${effectiveBackup?.file?.path ?: "none"}); retry is safe"
        }
    }

    private fun SQLiteDatabase.inspectSchema(
        version: Int,
        isLegacyJavaSchema: Boolean,
    ): SchemaInspection {
        val tables = readTableNames().toSet()
        val criticalIssues = mutableListOf<String>()
        val repairableIssues = mutableListOf<String>()

        if (version > expectedSchemaVersion) {
            criticalIssues += "database version $version is newer than supported $expectedSchemaVersion"
        }

        val requiredCoreTables = if (isLegacyJavaSchema) LEGACY_CORE_TABLES else CORE_TABLES
        requiredCoreTables.filterNot(tables::contains).forEach { table ->
            criticalIssues += "missing core table $table"
        }

        CORE_TABLE_SPECS
            .filter { it.name in tables }
            .forEach { table ->
                val columns = readColumnNames(table.name)
                table.columns.forEach { column ->
                    if (column.name in columns) return@forEach
                    if (column.addDefinition == null) {
                        criticalIssues += "missing core column ${table.name}.${column.name}"
                    } else if (version >= column.sinceVersion || isLegacyJavaSchema) {
                        repairableIssues += "missing column ${table.name}.${column.name}"
                    }
                }
            }

        AUXILIARY_TABLE_SPECS.forEach { table ->
            if (table.name !in tables) {
                if (version >= expectedSchemaVersion) {
                    repairableIssues += "missing auxiliary table ${table.name}"
                }
                return@forEach
            }
            val columns = readColumnNames(table.name)
            table.columns.forEach { column ->
                if (column.name in columns) return@forEach
                if (column.addDefinition == null) {
                    criticalIssues += "missing key column ${table.name}.${column.name}"
                } else {
                    repairableIssues += "missing column ${table.name}.${column.name}"
                }
            }
        }

        if (version >= expectedSchemaVersion) {
            EXPECTED_INDEXES.filterNot { hasObject("index", it) }.forEach { name ->
                repairableIssues += "missing index $name"
            }
            EXPECTED_VIEWS.keys.filterNot { hasObject("view", it) }.forEach { name ->
                repairableIssues += "missing view $name"
            }
            EXPECTED_TRIGGERS.keys.filterNot { hasObject("trigger", it) }.forEach { name ->
                repairableIssues += "missing trigger $name"
            }
        }

        return SchemaInspection(
            criticalIssues = criticalIssues,
            repairableIssues = repairableIssues,
        )
    }

    private fun SQLiteDatabase.isLegacyJavaSchema(): Boolean {
        val tables = readTableNames().toSet()
        // Some affected installs carry a stale SQLDelight-looking user_version even though
        // their actual tables are still the legacy Java schema. Structure is authoritative.
        return LEGACY_CORE_TABLES.all(tables::contains) && (
            "history" !in tables ||
                !hasColumn("mangas", "next_update") ||
                !hasColumn("chapters", "scanlator") ||
                !hasColumn("manga_sync", "library_id")
            )
    }

    private fun SQLiteDatabase.bridgeLegacyTables() {
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

        ensureCoreColumnsForVersion(1)
    }

    private fun SQLiteDatabase.finishPartiallyAppliedMigration(startVersion: Int): Int {
        return when (startVersion) {
            2 -> {
                val columns = listOf(
                    "mangas" to CORE_TABLE_SPECS.column("mangas", "version"),
                    "mangas" to CORE_TABLE_SPECS.column("mangas", "is_syncing"),
                    "chapters" to CORE_TABLE_SPECS.column("chapters", "version"),
                    "chapters" to CORE_TABLE_SPECS.column("chapters", "is_syncing"),
                )
                if (columns.any { (table, column) -> hasColumn(table, column.name) }) {
                    columns.forEach { (table, column) -> addColumnIfMissing(table, column) }
                    setUserVersion(3)
                    logcat(LogPriority.WARN) { "Completed partially applied database migration 2" }
                    3
                } else {
                    startVersion
                }
            }
            4 -> finishSingleColumnMigration(startVersion, 5, "manga_sync", "private")
            5 -> finishSingleColumnMigration(startVersion, 6, "mangas", "notes")
            11 -> {
                val mangaMemo = CORE_TABLE_SPECS.column("mangas", "memo")
                val chapterMemo = CORE_TABLE_SPECS.column("chapters", "memo")
                if (hasColumn("mangas", mangaMemo.name) || hasColumn("chapters", chapterMemo.name)) {
                    addColumnIfMissing("mangas", mangaMemo)
                    addColumnIfMissing("chapters", chapterMemo)
                    setUserVersion(12)
                    logcat(LogPriority.WARN) { "Completed partially applied database migration 11" }
                    12
                } else {
                    startVersion
                }
            }
            else -> startVersion
        }
    }

    private fun SQLiteDatabase.finishSingleColumnMigration(
        startVersion: Int,
        completedVersion: Int,
        table: String,
        columnName: String,
    ): Int {
        val column = CORE_TABLE_SPECS.column(table, columnName)
        if (!hasColumn(table, column.name)) return startVersion
        setUserVersion(completedVersion)
        logcat(LogPriority.WARN) { "Completed partially applied database migration $startVersion" }
        return completedVersion
    }

    private fun SQLiteDatabase.ensureCoreColumnsForVersion(version: Int) {
        CORE_TABLE_SPECS.forEach { table ->
            check(hasTable(table.name)) { "Missing core database table ${table.name}" }
            table.columns
                .filter { it.sinceVersion <= version }
                .forEach { column -> addColumnIfMissing(table.name, column) }
        }
    }

    private fun SQLiteDatabase.ensureAuxiliaryTablesAndColumns() {
        AUXILIARY_TABLE_SPECS.forEach { table ->
            execSQL(table.createSql)
            table.columns.forEach { column -> addColumnIfMissing(table.name, column) }
        }
    }

    private fun SQLiteDatabase.addColumnIfMissing(table: String, column: ColumnSpec) {
        if (hasColumn(table, column.name)) return
        val definition = checkNotNull(column.addDefinition) {
            "Missing non-repairable database column $table.${column.name}"
        }
        execSQL("ALTER TABLE $table ADD COLUMN $definition")
    }

    private fun SQLiteDatabase.ensureCurrentSchemaObjects() {
        EXPECTED_INDEX_SQL.forEach { sql -> execSQL(sql) }
        execSQL("INSERT OR IGNORE INTO categories(_id, name, sort, flags) VALUES (0, '', -1, 0)")

        EXPECTED_VIEWS.forEach { (name, sql) ->
            if (!hasObject("view", name)) execSQL(sql)
        }
        EXPECTED_TRIGGERS.forEach { (name, sql) ->
            if (!hasObject("trigger", name)) execSQL(sql)
        }
    }

    /** Returns false only for non-fatal orphaned rows, which are logged without deleting data. */
    private fun SQLiteDatabase.validateCurrentSchema(): Boolean {
        val version = readUserVersion()
        check(version == expectedSchemaVersion) {
            "Database user_version=$version, expected=$expectedSchemaVersion"
        }

        val tables = readTableNames().toSet()
        val missingTables = ALL_TABLE_SPECS.map(TableSpec::name).filterNot(tables::contains)
        check(missingTables.isEmpty()) { "Missing database tables: $missingTables" }

        ALL_TABLE_SPECS.forEach { table ->
            val columns = readColumnNames(table.name)
            val missingColumns = table.columns.map(ColumnSpec::name).filterNot(columns::contains)
            check(missingColumns.isEmpty()) {
                "Missing columns in ${table.name}: $missingColumns"
            }
        }

        val missingIndexes = EXPECTED_INDEXES.filterNot { hasObject("index", it) }
        val missingViews = EXPECTED_VIEWS.keys.filterNot { hasObject("view", it) }
        val missingTriggers = EXPECTED_TRIGGERS.keys.filterNot { hasObject("trigger", it) }
        check(missingIndexes.isEmpty()) { "Missing database indexes: $missingIndexes" }
        check(missingViews.isEmpty()) { "Missing database views: $missingViews" }
        check(missingTriggers.isEmpty()) { "Missing database triggers: $missingTriggers" }

        val quickCheck = rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        check(quickCheck == listOf("ok")) { "SQLite quick_check failed: $quickCheck" }

        val foreignKeyTables = rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        if (foreignKeyTables.isNotEmpty()) {
            logcat(LogPriority.ERROR) {
                "Database foreign_key_check found violations in tables=$foreignKeyTables"
            }
            return false
        }
        return true
    }

    private fun SQLiteDatabase.hasTable(table: String): Boolean = hasObject("table", table)

    private fun SQLiteDatabase.hasObject(type: String, name: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ? LIMIT 1",
        arrayOf(type, name),
    ).use { it.moveToFirst() }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        column in readColumnNames(table)

    private fun SQLiteDatabase.readColumnNames(table: String): Set<String> = rawQuery(
        "PRAGMA table_info($table)",
        null,
    ).use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

    private fun SQLiteDatabase.readUserVersion(): Int = rawQuery("PRAGMA user_version", null).use {
        check(it.moveToFirst()) { "Unable to read database user_version" }
        it.getInt(0)
    }

    private fun SQLiteDatabase.setUserVersion(version: Int) {
        execSQL("PRAGMA user_version = $version")
    }

    private fun SQLiteDatabase.readUserVersionSafely(): Int = runCatching { readUserVersion() }.getOrDefault(-1)

    private fun SQLiteDatabase.readTableNames(): List<String> = rawQuery(
        """
        SELECT name FROM sqlite_master
        WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'
        ORDER BY name
        """.trimIndent(),
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
                if (!cursor.moveToFirst()) {
                    logcat(LogPriority.WARN) { "Unable to read database WAL checkpoint result" }
                    return@use
                }
                val busy = cursor.getInt(0)
                val logFrames = cursor.getInt(1)
                val checkpointedFrames = cursor.getInt(2)
                if (busy != 0 || (logFrames >= 0 && checkpointedFrames < logFrames)) {
                    logcat(LogPriority.WARN) {
                        "Database WAL checkpoint incomplete; preserving sidecars with migration backup " +
                            "(busy=$busy, logFrames=$logFrames, checkpointedFrames=$checkpointedFrames)"
                    }
                }
            }
        }.onFailure { exception ->
            logcat(LogPriority.WARN, exception) {
                "Database WAL checkpoint failed; preserving sidecars with migration backup"
            }
        }
    }

    class MigrationBackup internal constructor(
        internal val file: File,
        private val sidecarFiles: List<File>,
    ) {
        internal fun delete() {
            (listOf(file) + sidecarFiles).forEach { backupFile ->
                if (!backupFile.delete() && backupFile.exists()) {
                    logcat(LogPriority.WARN) {
                        "Unable to delete database migration backup ${backupFile.path}"
                    }
                }
            }
        }

        companion object {
            internal fun create(databaseFile: File): MigrationBackup {
                val timestamp = System.currentTimeMillis()
                var suffix = 0
                var backup: File
                do {
                    val suffixText = if (suffix == 0) "" else "-$suffix"
                    backup =
                        File(databaseFile.parentFile, "${databaseFile.name}.migration-backup-$timestamp$suffixText")
                    suffix++
                } while (backup.exists())
                databaseFile.copyTo(backup, overwrite = false)
                val sidecarBackups = listOf("-wal", "-shm").mapNotNull { suffix ->
                    val sidecar = File(databaseFile.path + suffix)
                    if (!sidecar.exists()) return@mapNotNull null
                    File(backup.path + suffix).also { sidecar.copyTo(it, overwrite = false) }
                }
                return MigrationBackup(backup, sidecarBackups)
            }
        }
    }

    private data class SchemaInspection(
        val criticalIssues: List<String>,
        val repairableIssues: List<String>,
    )

    private data class TableSpec(
        val name: String,
        val columns: List<ColumnSpec>,
        val createSql: String = "",
    )

    private data class ColumnSpec(
        val name: String,
        val addDefinition: String? = null,
        val sinceVersion: Int = 1,
    )

    private companion object {
        const val DATABASE_NAME = "tachiyomi.db"

        val LEGACY_CORE_TABLES = setOf("categories", "chapters", "manga_sync", "mangas", "mangas_categories")
        val CORE_TABLES = LEGACY_CORE_TABLES + setOf("excluded_scanlators", "history", "sources")

        val CORE_TABLE_SPECS = listOf(
            TableSpec(
                "categories",
                listOf(ColumnSpec("_id"), ColumnSpec("name"), ColumnSpec("sort"), ColumnSpec("flags")),
            ),
            TableSpec(
                "chapters",
                listOf(
                    ColumnSpec("_id"),
                    ColumnSpec("manga_id"),
                    ColumnSpec("url"),
                    ColumnSpec("name"),
                    ColumnSpec("scanlator", "scanlator TEXT"),
                    ColumnSpec("read"),
                    ColumnSpec("bookmark", "bookmark INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("last_page_read"),
                    ColumnSpec("chapter_number"),
                    ColumnSpec("source_order", "source_order INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("date_fetch"),
                    ColumnSpec("date_upload"),
                    ColumnSpec("last_modified_at", "last_modified_at INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("version", "version INTEGER NOT NULL DEFAULT 0", sinceVersion = 3),
                    ColumnSpec("is_syncing", "is_syncing INTEGER NOT NULL DEFAULT 0", sinceVersion = 3),
                    ColumnSpec("memo", "memo BLOB NOT NULL DEFAULT '{}'", sinceVersion = 12),
                ),
            ),
            TableSpec(
                "excluded_scanlators",
                listOf(ColumnSpec("manga_id"), ColumnSpec("scanlator")),
            ),
            TableSpec(
                "history",
                listOf(ColumnSpec("_id"), ColumnSpec("chapter_id"), ColumnSpec("last_read"), ColumnSpec("time_read")),
            ),
            TableSpec(
                "manga_sync",
                listOf(
                    ColumnSpec("_id"),
                    ColumnSpec("manga_id"),
                    ColumnSpec("sync_id"),
                    ColumnSpec("remote_id"),
                    ColumnSpec("library_id", "library_id INTEGER"),
                    ColumnSpec("title"),
                    ColumnSpec("last_chapter_read"),
                    ColumnSpec("total_chapters"),
                    ColumnSpec("status"),
                    ColumnSpec("score"),
                    ColumnSpec("remote_url", "remote_url TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("start_date", "start_date INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("finish_date", "finish_date INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("private", "private INTEGER NOT NULL DEFAULT 0", sinceVersion = 5),
                ),
            ),
            TableSpec(
                "mangas",
                listOf(
                    ColumnSpec("_id"),
                    ColumnSpec("source"),
                    ColumnSpec("url"),
                    ColumnSpec("artist"),
                    ColumnSpec("author"),
                    ColumnSpec("description"),
                    ColumnSpec("genre"),
                    ColumnSpec("title"),
                    ColumnSpec("status"),
                    ColumnSpec("thumbnail_url"),
                    ColumnSpec("favorite"),
                    ColumnSpec("last_update"),
                    ColumnSpec("next_update", "next_update INTEGER"),
                    ColumnSpec("initialized"),
                    ColumnSpec("viewer"),
                    ColumnSpec("chapter_flags"),
                    ColumnSpec("cover_last_modified", "cover_last_modified INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("date_added", "date_added INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("update_strategy", "update_strategy INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("calculate_interval", "calculate_interval INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("last_modified_at", "last_modified_at INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("favorite_modified_at", "favorite_modified_at INTEGER"),
                    ColumnSpec("version", "version INTEGER NOT NULL DEFAULT 0", sinceVersion = 3),
                    ColumnSpec("is_syncing", "is_syncing INTEGER NOT NULL DEFAULT 0", sinceVersion = 3),
                    ColumnSpec("notes", "notes TEXT NOT NULL DEFAULT ''", sinceVersion = 6),
                    ColumnSpec("memo", "memo BLOB NOT NULL DEFAULT '{}'", sinceVersion = 12),
                ),
            ),
            TableSpec(
                "mangas_categories",
                listOf(ColumnSpec("_id"), ColumnSpec("manga_id"), ColumnSpec("category_id")),
            ),
            TableSpec(
                "sources",
                listOf(ColumnSpec("_id"), ColumnSpec("lang"), ColumnSpec("name")),
            ),
        )

        val AUXILIARY_TABLE_SPECS = listOf(
            TableSpec(
                name = "extension_repos",
                columns = listOf(
                    ColumnSpec("base_url"),
                    ColumnSpec("name", "name TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("short_name", "short_name TEXT"),
                    ColumnSpec("website", "website TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("signing_key_fingerprint", "signing_key_fingerprint TEXT NOT NULL DEFAULT ''"),
                ),
                createSql = """
                    CREATE TABLE IF NOT EXISTS extension_repos(
                        base_url TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        short_name TEXT,
                        website TEXT NOT NULL,
                        signing_key_fingerprint TEXT UNIQUE NOT NULL
                    )
                """.trimIndent(),
            ),
            TableSpec(
                name = "komga_shared_download_matches",
                columns = listOf(
                    ColumnSpec("server_id"),
                    ColumnSpec("book_url"),
                    ColumnSpec("series_url", "series_url TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("file_hash", "file_hash TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("size_bytes", "size_bytes INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("isbn", "isbn TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("series_title", "series_title TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("book_title", "book_title TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("number_sort", "number_sort REAL NOT NULL DEFAULT 0"),
                    ColumnSpec("local_relative_path", "local_relative_path TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("file_name", "file_name TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("file_kind", "file_kind TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("created_at", "created_at INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("last_verified_at", "last_verified_at INTEGER NOT NULL DEFAULT 0"),
                ),
                createSql = """
                    CREATE TABLE IF NOT EXISTS komga_shared_download_matches(
                        server_id INTEGER NOT NULL,
                        book_url TEXT NOT NULL,
                        series_url TEXT NOT NULL DEFAULT '',
                        file_hash TEXT NOT NULL DEFAULT '',
                        size_bytes INTEGER NOT NULL DEFAULT 0,
                        isbn TEXT NOT NULL DEFAULT '',
                        series_title TEXT NOT NULL DEFAULT '',
                        book_title TEXT NOT NULL DEFAULT '',
                        number_sort REAL NOT NULL DEFAULT 0,
                        local_relative_path TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        file_kind TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_verified_at INTEGER NOT NULL,
                        PRIMARY KEY(server_id, book_url)
                    )
                """.trimIndent(),
            ),
            TableSpec(
                name = "epub_progress",
                columns = listOf(
                    ColumnSpec("chapter_id"),
                    ColumnSpec("manga_id", "manga_id INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("book_url", "book_url TEXT"),
                    ColumnSpec("locator_json", "locator_json TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("progression", "progression REAL"),
                    ColumnSpec("position_index", "position_index INTEGER"),
                    ColumnSpec("updated_at", "updated_at INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("last_synced_at", "last_synced_at INTEGER"),
                ),
                createSql = """
                    CREATE TABLE IF NOT EXISTS epub_progress(
                        chapter_id INTEGER NOT NULL PRIMARY KEY,
                        manga_id INTEGER NOT NULL,
                        book_url TEXT,
                        locator_json TEXT NOT NULL,
                        progression REAL,
                        position_index INTEGER,
                        updated_at INTEGER NOT NULL,
                        last_synced_at INTEGER,
                        FOREIGN KEY(chapter_id) REFERENCES chapters (_id) ON DELETE CASCADE,
                        FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
                    )
                """.trimIndent(),
            ),
            TableSpec(
                name = "epub_bookmark",
                columns = listOf(
                    ColumnSpec("_id"),
                    ColumnSpec("chapter_id"),
                    ColumnSpec("manga_id", "manga_id INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("locator_json", "locator_json TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("section_title", "section_title TEXT"),
                    ColumnSpec("progression", "progression REAL"),
                    ColumnSpec("note", "note TEXT"),
                    ColumnSpec("created_at", "created_at INTEGER NOT NULL DEFAULT 0"),
                ),
                createSql = """
                    CREATE TABLE IF NOT EXISTS epub_bookmark(
                        _id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        chapter_id INTEGER NOT NULL,
                        manga_id INTEGER NOT NULL,
                        locator_json TEXT NOT NULL,
                        section_title TEXT,
                        progression REAL,
                        note TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(chapter_id) REFERENCES chapters (_id) ON DELETE CASCADE,
                        FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
                    )
                """.trimIndent(),
            ),
            TableSpec(
                name = "epub_pagination_cache",
                columns = listOf(
                    ColumnSpec("chapter_id"),
                    ColumnSpec("publication_key"),
                    ColumnSpec("layout_key"),
                    ColumnSpec("layout_snapshot_json", "layout_snapshot_json TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("resource_page_counts_json", "resource_page_counts_json TEXT NOT NULL DEFAULT '{}'"),
                    ColumnSpec("current_locator_json", "current_locator_json TEXT"),
                    ColumnSpec("current_visual_page", "current_visual_page INTEGER"),
                    ColumnSpec("total_visual_pages", "total_visual_pages INTEGER"),
                    ColumnSpec("is_complete", "is_complete INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("measured_resource_count", "measured_resource_count INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("updated_at", "updated_at INTEGER NOT NULL DEFAULT 0"),
                ),
                createSql = """
                    CREATE TABLE IF NOT EXISTS epub_pagination_cache(
                        chapter_id INTEGER NOT NULL,
                        publication_key TEXT NOT NULL,
                        layout_key TEXT NOT NULL,
                        layout_snapshot_json TEXT NOT NULL,
                        resource_page_counts_json TEXT NOT NULL,
                        current_locator_json TEXT,
                        current_visual_page INTEGER,
                        total_visual_pages INTEGER,
                        is_complete INTEGER NOT NULL DEFAULT 0,
                        measured_resource_count INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(chapter_id, publication_key, layout_key),
                        FOREIGN KEY(chapter_id) REFERENCES chapters (_id) ON DELETE CASCADE
                    )
                """.trimIndent(),
            ),
            TableSpec(
                name = "epub_remote_progress_cache",
                columns = listOf(
                    ColumnSpec("chapter_id"),
                    ColumnSpec("manga_id", "manga_id INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("book_url", "book_url TEXT NOT NULL DEFAULT ''"),
                    ColumnSpec("locator_json", "locator_json TEXT"),
                    ColumnSpec("progression", "progression REAL"),
                    ColumnSpec("position_index", "position_index INTEGER"),
                    ColumnSpec("modified_at", "modified_at INTEGER"),
                    ColumnSpec("checked_at", "checked_at INTEGER NOT NULL DEFAULT 0"),
                    ColumnSpec("server_date", "server_date INTEGER"),
                ),
                createSql = """
                    CREATE TABLE IF NOT EXISTS epub_remote_progress_cache(
                        chapter_id INTEGER NOT NULL PRIMARY KEY,
                        manga_id INTEGER NOT NULL,
                        book_url TEXT NOT NULL,
                        locator_json TEXT,
                        progression REAL,
                        position_index INTEGER,
                        modified_at INTEGER,
                        checked_at INTEGER NOT NULL,
                        server_date INTEGER,
                        FOREIGN KEY(chapter_id) REFERENCES chapters (_id) ON DELETE CASCADE,
                        FOREIGN KEY(manga_id) REFERENCES mangas (_id) ON DELETE CASCADE
                    )
                """.trimIndent(),
            ),
        )

        val ALL_TABLE_SPECS = CORE_TABLE_SPECS + AUXILIARY_TABLE_SPECS

        val EXPECTED_INDEX_SQL = listOf(
            "CREATE INDEX IF NOT EXISTS library_favorite_index ON mangas(favorite) WHERE favorite = 1",
            "CREATE INDEX IF NOT EXISTS mangas_url_index ON mangas(url)",
            "CREATE INDEX IF NOT EXISTS idx_mangas_source ON mangas(source)",
            "CREATE INDEX IF NOT EXISTS chapters_manga_id_index ON chapters(manga_id)",
            "CREATE INDEX IF NOT EXISTS chapters_unread_by_manga_index ON chapters(manga_id, read) WHERE read = 0",
            "CREATE INDEX IF NOT EXISTS idx_chapters_url ON chapters(url)",
            "CREATE INDEX IF NOT EXISTS idx_manga_sync_manga_id ON manga_sync(manga_id)",
            "CREATE INDEX IF NOT EXISTS idx_mangas_categories_manga_id ON mangas_categories(manga_id)",
            "CREATE INDEX IF NOT EXISTS idx_mangas_categories_category_id ON mangas_categories(category_id)",
            "CREATE INDEX IF NOT EXISTS excluded_scanlators_manga_id_index ON excluded_scanlators(manga_id)",
            "CREATE INDEX IF NOT EXISTS idx_excluded_scanlators_scanlator ON excluded_scanlators(scanlator)",
            "CREATE INDEX IF NOT EXISTS history_history_chapter_id_index ON history(chapter_id)",
            "CREATE INDEX IF NOT EXISTS idx_history_last_read ON history(last_read)",
            "CREATE INDEX IF NOT EXISTS idx_komga_shared_download_matches_book_url ON komga_shared_download_matches(book_url)",
            "CREATE INDEX IF NOT EXISTS idx_komga_shared_download_matches_file_hash ON komga_shared_download_matches(file_hash)",
            "CREATE INDEX IF NOT EXISTS idx_komga_shared_download_matches_local_relative_path " +
                "ON komga_shared_download_matches(local_relative_path)",
            "CREATE INDEX IF NOT EXISTS epub_progress_manga_id_index ON epub_progress(manga_id)",
            "CREATE INDEX IF NOT EXISTS epub_progress_updated_at_index ON epub_progress(updated_at)",
            "CREATE INDEX IF NOT EXISTS epub_bookmark_chapter_id_index ON epub_bookmark(chapter_id)",
            "CREATE INDEX IF NOT EXISTS epub_bookmark_manga_id_index ON epub_bookmark(manga_id)",
            "CREATE INDEX IF NOT EXISTS epub_pagination_cache_chapter_id_index ON epub_pagination_cache(chapter_id)",
            "CREATE INDEX IF NOT EXISTS epub_remote_progress_cache_manga_id_index ON epub_remote_progress_cache(manga_id)",
        )

        val EXPECTED_INDEXES = setOf(
            "library_favorite_index",
            "mangas_url_index",
            "idx_mangas_source",
            "chapters_manga_id_index",
            "chapters_unread_by_manga_index",
            "idx_chapters_url",
            "idx_manga_sync_manga_id",
            "idx_mangas_categories_manga_id",
            "idx_mangas_categories_category_id",
            "excluded_scanlators_manga_id_index",
            "idx_excluded_scanlators_scanlator",
            "history_history_chapter_id_index",
            "idx_history_last_read",
            "idx_komga_shared_download_matches_book_url",
            "idx_komga_shared_download_matches_file_hash",
            "idx_komga_shared_download_matches_local_relative_path",
            "epub_progress_manga_id_index",
            "epub_progress_updated_at_index",
            "epub_bookmark_chapter_id_index",
            "epub_bookmark_manga_id_index",
            "epub_pagination_cache_chapter_id_index",
            "epub_remote_progress_cache_manga_id_index",
        )

        val EXPECTED_VIEWS = mapOf(
            "historyView" to """
                CREATE VIEW historyView AS
                SELECT history._id AS id, mangas._id AS mangaId, chapters._id AS chapterId,
                    mangas.title, mangas.thumbnail_url AS thumbnailUrl, mangas.source, mangas.favorite,
                    mangas.cover_last_modified, chapters.chapter_number AS chapterNumber,
                    history.last_read AS readAt, history.time_read AS readDuration,
                    max_last_read.last_read AS maxReadAt, max_last_read.chapter_id AS maxReadAtChapterId
                FROM mangas
                JOIN chapters ON mangas._id = chapters.manga_id
                JOIN history ON chapters._id = history.chapter_id
                JOIN (
                    SELECT chapters.manga_id, chapters._id AS chapter_id, MAX(history.last_read) AS last_read
                    FROM chapters JOIN history ON chapters._id = history.chapter_id
                    GROUP BY chapters.manga_id
                ) AS max_last_read ON chapters.manga_id = max_last_read.manga_id
            """.trimIndent(),
            "libraryView" to """
                CREATE VIEW libraryView AS
                SELECT M.*, coalesce(C.total, 0) AS totalCount, coalesce(C.readCount, 0) AS readCount,
                    coalesce(C.latestUpload, 0) AS latestUpload, coalesce(C.fetchedAt, 0) AS chapterFetchedAt,
                    coalesce(C.lastRead, 0) AS lastRead, coalesce(C.bookmarkCount, 0) AS bookmarkCount,
                    coalesce(MC.categories, '0') AS categories
                FROM mangas M
                LEFT JOIN (
                    SELECT chapters.manga_id, count(*) AS total, sum(read) AS readCount,
                        coalesce(max(chapters.date_upload), 0) AS latestUpload,
                        coalesce(max(history.last_read), 0) AS lastRead,
                        coalesce(max(chapters.date_fetch), 0) AS fetchedAt,
                        sum(chapters.bookmark) AS bookmarkCount
                    FROM chapters
                    LEFT JOIN excluded_scanlators ON chapters.manga_id = excluded_scanlators.manga_id
                        AND chapters.scanlator = excluded_scanlators.scanlator
                    LEFT JOIN history ON chapters._id = history.chapter_id
                    WHERE excluded_scanlators.scanlator IS NULL
                    GROUP BY chapters.manga_id
                ) AS C ON M._id = C.manga_id
                LEFT JOIN (
                    SELECT manga_id, group_concat(category_id) AS categories
                    FROM mangas_categories GROUP BY manga_id
                ) AS MC ON MC.manga_id = M._id
                WHERE M.favorite = 1
            """.trimIndent(),
            "updatesView" to """
                CREATE VIEW updatesView AS
                SELECT mangas._id AS mangaId, mangas.title AS mangaTitle, chapters._id AS chapterId,
                    chapters.name AS chapterName, chapters.scanlator, chapters.url AS chapterUrl,
                    chapters.read, chapters.bookmark, chapters.last_page_read, mangas.source, mangas.favorite,
                    mangas.thumbnail_url AS thumbnailUrl, mangas.cover_last_modified AS coverLastModified,
                    chapters.date_upload AS dateUpload, chapters.date_fetch AS datefetch,
                    excluded_scanlators.scanlator AS excludedScanlator
                FROM mangas JOIN chapters ON mangas._id = chapters.manga_id
                LEFT JOIN excluded_scanlators ON mangas._id = excluded_scanlators.manga_id
                    AND chapters.scanlator = excluded_scanlators.scanlator
                WHERE favorite = 1 AND date_fetch > date_added
                ORDER BY date_fetch DESC
            """.trimIndent(),
        )

        val EXPECTED_TRIGGERS = mapOf(
            "system_category_delete_trigger" to """
                CREATE TRIGGER system_category_delete_trigger BEFORE DELETE ON categories
                BEGIN
                    SELECT CASE WHEN old._id <= 0
                        THEN RAISE(ABORT, 'System category cannot be deleted') END;
                END
            """.trimIndent(),
            "update_last_favorited_at_mangas" to """
                CREATE TRIGGER update_last_favorited_at_mangas AFTER UPDATE OF favorite ON mangas
                BEGIN
                    UPDATE mangas SET favorite_modified_at = strftime('%s', 'now') WHERE _id = new._id;
                END
            """.trimIndent(),
            "update_last_modified_at_mangas" to """
                CREATE TRIGGER update_last_modified_at_mangas AFTER UPDATE ON mangas
                FOR EACH ROW WHEN new.last_modified_at = old.last_modified_at
                BEGIN
                    UPDATE mangas SET last_modified_at = strftime('%s', 'now') WHERE _id = new._id;
                END
            """.trimIndent(),
            "update_manga_version" to """
                CREATE TRIGGER update_manga_version AFTER UPDATE ON mangas
                BEGIN
                    UPDATE mangas SET version = version + 1
                    WHERE _id = new._id AND new.is_syncing = 0 AND (
                        new.url != old.url OR new.description != old.description OR new.favorite != old.favorite
                    );
                END
            """.trimIndent(),
            "update_last_modified_at_chapters" to """
                CREATE TRIGGER update_last_modified_at_chapters AFTER UPDATE ON chapters
                FOR EACH ROW WHEN new.last_modified_at = old.last_modified_at
                BEGIN
                    UPDATE chapters SET last_modified_at = strftime('%s', 'now') WHERE _id = new._id;
                END
            """.trimIndent(),
            "update_chapter_and_manga_version" to """
                CREATE TRIGGER update_chapter_and_manga_version AFTER UPDATE ON chapters
                WHEN new.is_syncing = 0 AND (
                    new.read != old.read OR new.bookmark != old.bookmark OR
                    new.last_page_read != old.last_page_read
                )
                BEGIN
                    UPDATE chapters SET version = version + 1 WHERE _id = new._id;
                    UPDATE mangas SET version = version + 1
                    WHERE _id = new.manga_id AND
                        (SELECT is_syncing FROM mangas WHERE _id = new.manga_id) = 0;
                END
            """.trimIndent(),
            "insert_manga_category_update_version" to """
                CREATE TRIGGER insert_manga_category_update_version AFTER INSERT ON mangas_categories
                BEGIN
                    UPDATE mangas SET version = version + 1
                    WHERE _id = new.manga_id AND
                        (SELECT is_syncing FROM mangas WHERE _id = new.manga_id) = 0;
                END
            """.trimIndent(),
        )

        fun List<TableSpec>.column(table: String, column: String): ColumnSpec {
            return first { it.name == table }.columns.first { it.name == column }
        }
    }
}
