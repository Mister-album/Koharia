package koharia.connection

import android.os.Bundle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class ProgressQueryDeviceTest {
    private val kavita get() = Injekt.get<KavitaRepository>()
    private val suwayomi get() = Injekt.get<SuwayomiRepository>()
    private val lanraragi get() = Injekt.get<LanraragiRepository>()

    @Test
    fun restoreHoldMigrationPreservesExistingLivePendingState(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val name = "restore-hold-migration-${System.nanoTime()}.db"
        check(!context.getDatabasePath(name).exists())
        val previous = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
            override val version = 32L
            override fun create(driver: SqlDriver) = QueryResult.AsyncValue<Unit> {
                Database.Schema.create(driver).await()
                driver.execute(null, "DROP INDEX lanraragi_read_state_pending_index", 0).await()
                driver.execute(null, "ALTER TABLE lanraragi_read_state DROP COLUMN requires_confirmation", 0).await()
                driver.execute(
                    null,
                    "CREATE INDEX lanraragi_read_state_pending_index " +
                        "ON lanraragi_read_state(connection_id,pending,local_unread)",
                    0,
                ).await()
            }
            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: app.cash.sqldelight.db.AfterVersion,
            ) = QueryResult.AsyncValue<Unit> { error("Disposable fixture must start fresh") }
        }
        fun open(schema: SqlSchema<QueryResult.AsyncValue<Unit>>) = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = schema,
        )
        try {
            open(previous).use { driver ->
                driver.execute(null, "INSERT INTO lanraragi_read_state VALUES (1,'book',3,10,100,0,1,4,0)", 0).await()
            }
            open(Database.Schema).use { driver ->
                assertEquals(
                    listOf("connection_id", "pending", "local_unread", "requires_confirmation"),
                    driver.strings("SELECT name FROM pragma_index_info('lanraragi_read_state_pending_index')"),
                )
                assertEquals(
                    listOf("3:4:1:0"),
                    driver.strings(
                        "SELECT page_index || ':' || revision || ':' || pending || ':' || requires_confirmation " +
                            "FROM lanraragi_read_state",
                    ),
                )
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun indexMigrationPreservesPendingHistoryAndAccountIsolation(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val name = "progress-query-migration-${System.nanoTime()}.db"
        check(!context.getDatabasePath(name).exists())
        val indexes =
            listOf(
                "kavita_operation_pending_index",
                "suwayomi_operation_pending_index",
                "lanraragi_read_state_pending_index",
                "mangas_source_url_index",
            )
        val previous = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
            override val version = 27L
            override fun create(driver: SqlDriver) = QueryResult.AsyncValue {
                Database.Schema.create(driver).await()
                indexes.forEach { driver.execute(null, "DROP INDEX $it", 0).await() }
            }
            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: app.cash.sqldelight.db.AfterVersion,
            ) = QueryResult.AsyncValue<Unit> { error("Disposable fixture must start fresh") }
        }
        fun open(schema: SqlSchema<QueryResult.AsyncValue<Unit>>) = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = schema,
        )
        try {
            open(previous).use { driver ->
                for (source in 1..2) {
                    driver.execute(
                        null,
                        "INSERT INTO mangas(source,url,title,status,favorite,initialized,viewer,chapter_flags," +
                            "cover_last_modified,date_added) VALUES ($source,'same','fixture',0,1,1,0,0,0,0)",
                        0,
                    ).await()
                }
                for (table in listOf("kavita_operation", "suwayomi_operation")) {
                    driver.execute(null, "INSERT INTO $table VALUES (1,'first','resource','pending',4,1)", 0).await()
                    driver.execute(null, "INSERT INTO $table VALUES (1,'second','resource','history',7,0)", 0).await()
                }
                driver.execute(
                    null,
                    "INSERT INTO lanraragi_read_state(connection_id,archive_id,page_index,total_pages,read_at," +
                        "local_unread,pending,revision,initial_page) VALUES (1,'archive',3,10,100,0,1,4,0)",
                    0,
                ).await()
            }
            val migrated = object : SqlSchema<QueryResult.AsyncValue<Unit>> by previous {
                override val version = 29L
                override fun migrate(
                    driver: SqlDriver,
                    oldVersion: Long,
                    newVersion: Long,
                    vararg callbacks: app.cash.sqldelight.db.AfterVersion,
                ) = Database.Schema.migrate(driver, oldVersion, newVersion, *callbacks)
            }
            open(migrated).use { driver ->
                assertEquals(
                    listOf("1:same:1", "2:same:1"),
                    driver.strings("SELECT source || ':' || url || ':' || favorite FROM mangas ORDER BY source"),
                )
                for (table in listOf("kavita_operation", "suwayomi_operation")) {
                    assertEquals(
                        listOf("pending:4:1", "history:7:0"),
                        driver.strings(
                            "SELECT payload || ':' || revision || ':' || pending FROM $table ORDER BY account_key",
                        ),
                    )
                }
                assertEquals(
                    listOf("3:4:1"),
                    driver.strings(
                        "SELECT page_index || ':' || revision || ':' || pending FROM lanraragi_read_state",
                    ),
                )
                val present = driver.strings("SELECT name FROM sqlite_master WHERE type='index'")
                assertTrue(present.containsAll(indexes))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private suspend fun SqlDriver.strings(sql: String): List<String> = executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0).orEmpty()) })
    }, 0).await()

    @Test
    fun pointAndPendingQueriesStayBoundedAcrossHistorySizes(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("app.koharia.dev.devicefixture", instrumentation.targetContext.packageName)
        val database = Injekt.get<Database>()
        val driver = Injekt.get<SqlDriver>()
        for (size in listOf(1_000, 10_000, 50_000)) {
            val id = System.nanoTime()
            check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
            try {
                database.transaction {
                    repeat(size) { index ->
                        kavita.putOperation(id, "fixture", KavitaOperation("progress/$index", "{}", 1, index < 3))
                        suwayomi.putOperation(id, "fixture", SuwayomiOperation("$index", "{}", 1, index < 3))
                        lanraragi.record(
                            id,
                            LanraragiReadState(
                                "$index",
                                1,
                                10,
                                100,
                                pending = index < 3 || index % 2 == 0,
                                requiresConfirmation = index >= 3 && index % 2 == 0,
                            ),
                        )
                    }
                }
                measure("kavita", size, 1) {
                    assertEquals("progress/${size - 1}", kavita.operation(id, "fixture", "progress/${size - 1}")?.key)
                }
                measure("suwayomi", size, 1) {
                    assertEquals("${size - 1}", suwayomi.operation(id, "fixture", "${size - 1}")?.key)
                }
                measure("lanraragi", size, 1) {
                    assertEquals("${size - 1}", lanraragi.readState(id, "${size - 1}")?.archiveId)
                }
                measure("kavita-previous-full-query", size, size) {
                    assertEquals(size, kavita.operations(id, "fixture").size)
                }
                measure("suwayomi-previous-full-query", size, size) {
                    assertEquals(size, suwayomi.operations(id, "fixture").size)
                }
                measure("lanraragi-previous-full-query", size, size) {
                    assertEquals(size, lanraragi.readStates(id).size)
                }
                measure("kavita-pending", size, 3) { assertEquals(3, kavita.pendingOperations(id, "fixture").size) }
                measure("suwayomi-pending", size, 3) { assertEquals(3, suwayomi.pendingOperations(id, "fixture").size) }
                measure("lanraragi-pending", size, 3) { assertEquals(3, lanraragi.pendingReadStates(id).size) }

                assertNull(kavita.operation(id, "other", "progress/0"))
                assertNull(suwayomi.operation(id, "other", "0"))
                assertTrue(kavita.pendingOperations(id, "other").isEmpty())
                assertTrue(suwayomi.pendingOperations(id, "other").isEmpty())
                assertNull(lanraragi.readState(id, "missing"))
                assertTrue(lanraragi.readStates(id, emptyList()).isEmpty())
                val ids = (0 until 1_002.coerceAtMost(size)).map(Int::toString)
                assertEquals(ids.toSet(), lanraragi.readStates(id, ids + ids.first()).map { it.archiveId }.toSet())

                val oldK = checkNotNull(kavita.operation(id, "fixture", "progress/0"))
                kavita.putOperation(id, "fixture", oldK.copy(revision = 2, payload = "newer"))
                kavita.acknowledge(id, "fixture", oldK.key, oldK.revision)
                assertTrue(kavita.operation(id, "fixture", oldK.key)!!.pending)
                val oldS = checkNotNull(suwayomi.operation(id, "fixture", "0"))
                suwayomi.putOperation(id, "fixture", oldS.copy(revision = 2, payload = "newer"))
                suwayomi.acknowledge(id, "fixture", oldS)
                assertEquals("newer", suwayomi.operation(id, "fixture", oldS.key)?.payload)
                assertTrue(suwayomi.operation(id, "fixture", oldS.key)!!.pending)
                val oldL = checkNotNull(lanraragi.readState(id, "0"))
                lanraragi.record(id, oldL.copy(pageIndex = 5))
                lanraragi.acknowledge(id, oldL)
                assertTrue(lanraragi.readState(id, "0")!!.pending)
                lanraragi.record(id, oldL.copy(localUnread = true))
                assertEquals(2, lanraragi.pendingReadStates(id).size)

                for (provider in listOf("kavita", "suwayomi", "lanraragi")) {
                    val table = if (provider == "lanraragi") "lanraragi_read_state" else "${provider}_operation"
                    val owner = if (provider == "lanraragi") "" else " AND account_key = 'fixture'"
                    val local = if (provider == "lanraragi") {
                        " AND local_unread = 0 AND requires_confirmation = 0"
                    } else {
                        ""
                    }
                    val details = driver.executeQuery(
                        null,
                        "EXPLAIN QUERY PLAN SELECT * FROM $table WHERE connection_id = $id$owner AND pending = 1$local",
                        { cursor ->
                            QueryResult.Value(
                                buildList {
                                    while (cursor.next().value) add(cursor.getString(3).orEmpty())
                                },
                            )
                        },
                        0,
                    ).await()
                    assertTrue(details.toString(), details.any { "${table}_pending_index" in it })
                    assertTrue(details.toString(), details.none { "SCAN " in it })
                    if (provider == "lanraragi") {
                        assertTrue(details.toString(), details.any { "requires_confirmation=?" in it })
                    }
                }
            } finally {
                kavita.removeConnection(id)
                suwayomi.removeConnection(id)
                lanraragi.remove(id)
            }
        }
    }

    private suspend fun measure(provider: String, history: Int, rows: Int, operation: suspend () -> Unit) {
        operation()
        val timings = List(5) {
            val start = System.nanoTime()
            operation()
            System.nanoTime() - start
        }.sorted()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("progress-query", "$provider history=$history returned=$rows medianNs=${timings[2]}")
            },
        )
    }
}
