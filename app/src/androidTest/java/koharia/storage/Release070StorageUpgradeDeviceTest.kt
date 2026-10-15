package koharia.storage

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import koharia.data.storage.LibraryStorageRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.Epub_bookmark
import tachiyomi.data.Epub_pagination_cache
import tachiyomi.data.Epub_progress
import tachiyomi.data.Epub_remote_progress_cache
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.Tts_progress
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.migration.LegacyDatabaseSchemaBridge
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class Release070StorageUpgradeDeviceTest {
    @Test
    fun schema25RetainsNetworkOwnersAndPendingWhileNewWritersStayIsolated(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val name = "upgrade-070-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        check(!file.exists())
        file.parentFile!!.mkdirs()
        val bytes = instrumentation.context.assets.open("upgrade-v070/v0.7.0-schema25.db").use { it.readBytes() }
        assertEquals(
            "8f17924a7c96c7db6575b09fb4755fb08eda4762bdf4280d20c08a746bc83ccf",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        val ownerText = instrumentation.context.assets.open("upgrade-v070/owners.json")
            .bufferedReader().use { it.readText() }
        assertEquals(
            "d7aa2edfd8fd8aefce3941763a8fef03010a078ff5afcea8bcf02e26e865236c",
            MessageDigest.getInstance("SHA-256").digest(ownerText.toByteArray()).joinToString("") { "%02x".format(it) },
        )
        val owners = Json.decodeFromString<List<Owner>>(ownerText)
        val rawOwners = Json.parseToJsonElement(ownerText).jsonArray
        val preferenceNames = mutableListOf<String>()
        var driver: AndroidxSqliteDriver? = null
        fun open() = AndroidxSqliteDriver(
            BundledSQLiteDriver(),
            AndroidxSqliteDatabaseType.FileProvider(context, name),
            Database.Schema,
            AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
        )
        try {
            file.writeBytes(bytes)
            val original = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals(25, sqlite.version)
                sqlite.rawQuery(RECORDS, null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            }
            assertEquals(18, original.size)
            val bridge = LegacyDatabaseSchemaBridge(context, Database.Schema.version.toInt(), name)
            val backup = checkNotNull(bridge.prepare())
            open().use { it.strings("SELECT 1") }
            bridge.complete(backup)
            val migrated = open()
            driver = migrated
            assertEquals(listOf(Database.Schema.version.toString()), migrated.strings("PRAGMA user_version"))
            assertEquals(listOf("ok"), migrated.strings("PRAGMA integrity_check"))
            assertTrue(migrated.strings("PRAGMA foreign_key_check").isEmpty())
            assertEquals(original, migrated.strings(RECORDS))
            val repository = LibraryStorageRepositoryImpl(database(migrated))
            val writers = mutableSetOf<String>()
            val expectedWriters = mutableMapOf<StorageSession, String>()
            owners.forEachIndexed { ownerIndex, owner ->
                val preferenceName = "$name-${owner.id}"
                preferenceNames += preferenceName
                val preferences = context.getSharedPreferences(preferenceName, 0)
                check(preferences.all.isEmpty())
                check(
                    preferences.edit()
                        .putString(
                            "network_storage_configuration",
                            rawOwners[ownerIndex].jsonObject.getValue("configuration").toString(),
                        )
                        .putString(Preference.privateKey("storage_username"), owner.username)
                        .commit(),
                )
                val network = NetworkStoragePreferences(owner.id, preferences)
                assertEquals(owner.configuration, network.configuration)
                assertEquals(owner.configuration.accountIdentity, network.account)
                assertEquals(
                    network.account,
                    NetworkStoragePreferences.account(network.configuration, network.username),
                )
                assertTrue(network.configuration.needsValidation)
                assertFalse(network.configuration.verifiedInternal)
                // Changing only transport addresses must not change the retained root/account identity.
                assertEquals(
                    network.account,
                    NetworkStoragePreferences.account(
                        network.configuration.copy(address = "changed"),
                        network.username,
                    ),
                )
                for ((account, root) in listOf(
                    network.account to network.configuration.rootIdentity,
                    "other-account" to network.configuration.rootIdentity,
                    network.account to "other-root",
                )) {
                    val session = StorageSession(owner.id, account, root) { true }
                    val store = StorageRecordStore(session, repository, Json)
                    val old = checkNotNull(store.snapshot("progress", "legacy-book"))
                    val pending = Json.decodeFromString<StoragePendingProgress>(old.payload)
                    assertTrue(pending.pending)
                    assertFalse(pending.requiresConfirmation)
                    assertEquals("legacy-installation", pending.record.device)
                    assertEquals(7L, pending.record.sequence)
                    val progress = StorageProgress(NoStorageIo, store, "same-installation")
                    assertEquals(pending.record, progress.cached("legacy-book"))
                    val event = progress.record("new-book", "v1", 1700000000001, page = 4, total = 10)
                    assertNotEquals(pending.record.device, event.device)
                    assertTrue(writers.add(event.device))
                    assertEquals(1L, event.sequence)
                    assertEquals(old, store.snapshot("progress", "legacy-book"))
                    assertEquals(7L, store.get<Long>("progress-device", "legacy-installation"))
                    assertEquals(1L, store.get<Long>("progress-device", event.device))
                    expectedWriters[session] = event.device
                }
            }
            assertEquals(6, writers.size)
            assertTrue(migrated.strings(RECORDS).containsAll(original))
            migrated.close()
            driver = null
            val reopened = open()
            driver = reopened
            val reopenedRepository = LibraryStorageRepositoryImpl(database(reopened))
            expectedWriters.forEach { (session, writer) ->
                val store = StorageRecordStore(session, reopenedRepository, Json)
                val event = StorageProgress(NoStorageIo, store, "same-installation")
                    .record("after-reopen", "v1", 1700000000002)
                assertEquals(writer, event.device)
                assertEquals(2L, event.sequence)
            }
            assertTrue(reopened.strings(RECORDS).containsAll(original))
        } finally {
            driver?.close()
            preferenceNames.forEach(context::deleteSharedPreferences)
            context.deleteDatabase(name)
            file.parentFile?.listFiles { candidate -> candidate.name.startsWith("$name.migration-backup-") }
                ?.forEach { it.delete() }
        }
    }

    private fun database(driver: SqlDriver) = Database(
        driver,
        chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
        mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
        historyAdapter = History.Adapter(DateColumnAdapter),
        epub_progressAdapter = Epub_progress.Adapter(DateColumnAdapter, DateColumnAdapter),
        epub_bookmarkAdapter = Epub_bookmark.Adapter(DateColumnAdapter),
        epub_pagination_cacheAdapter = Epub_pagination_cache.Adapter(DateColumnAdapter),
        epub_remote_progress_cacheAdapter = Epub_remote_progress_cache.Adapter(
            DateColumnAdapter,
            DateColumnAdapter,
            DateColumnAdapter,
        ),
        tts_progressAdapter = Tts_progress.Adapter(DateColumnAdapter),
    )

    private suspend fun SqlDriver.strings(sql: String): List<String> = executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0).orEmpty()) })
    }, 0).await()

    @Serializable
    private data class Owner(val id: Long, val configuration: NetworkStorageConfiguration, val username: String)

    private object NoStorageIo : LibraryStorageBackend {
        override val capabilities = StorageCapabilities(true, true, true, true)
        override suspend fun stat(path: String): StorageEntry = error("Unexpected storage stat")
        override suspend fun list(path: String): List<StorageEntry> = error("Unexpected storage list")
        override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray =
            error("Unexpected storage read")
        override suspend fun createDirectory(path: String): Unit = error("Unexpected directory mutation")
        override suspend fun write(
            path: String,
            data: java.io.InputStream,
            length: Long,
            expectedVersion: String?,
        ): Unit = error("Unexpected remote write")
        override suspend fun move(entry: StorageEntry, destination: String): Unit = error("Unexpected remote move")
        override suspend fun delete(entry: StorageEntry): Unit = error("Unexpected remote deletion")
        override fun close() = Unit
    }

    private companion object {
        const val RECORDS = "SELECT connection_id || ':' || account_key || ':' || root_id || ':' || " +
            "namespace || ':' || " +
            "record_key || ':' || payload || ':' || revision FROM library_storage_record " +
            "ORDER BY connection_id,account_key,root_id,namespace,record_key"
    }
}
