package koharia.connection

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.KohariaBackupEnvelope
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.domain.storage.LibraryStorageRepository
import koharia.source.local.LocalFolderSource
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StorageFailure
import koharia.storage.StorageIdentity
import koharia.storage.StoragePendingProgress
import koharia.storage.StorageProgressRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
class StorageActiveRestoreDeviceTest {
    @Test
    fun restoredNetworkConfigurationsRetireRuntimeAndRequireValidation(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("activeRestoreAddress")
        assumeTrue(address != null)
        require(checkNotNull(address).startsWith("http://127.0.0.1:") || address.startsWith("smb://127.0.0.1:"))
        val mode = LibraryStorageMode.valueOf(checkNotNull(args.getString("activeRestoreMode")))
        require(mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val config = NetworkStorageConfiguration(mode, address, rootIdentity = "e13f2ba1-8040-4bca-960a-5ac54ec78c34")
        val prefix = "Network active restore ${System.nanoTime()}"
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<LibraryStorageRepository>()
        val inventory = profiles.profiles()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val owned = mutableSetOf<Long>()
        val temporaryPreferences = mutableListOf<String>()
        val backup = File(context.cacheDir, "$prefix.kohariabk")
        val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        suspend fun source(id: Long): LocalFolderSource = withTimeout(10_000) {
            while (sources.get(id) !is LocalFolderSource) delay(25)
            sources.get(id) as LocalFolderSource
        }
        fun settings(id: Long, name: String): BackupSourcePreferences {
            val preferenceName = "$prefix-$name"
            val store = context.getSharedPreferences(preferenceName, 0)
            check(store.all.isEmpty())
            temporaryPreferences += preferenceName
            NetworkStoragePreferences(id, store).save(config.copy(verifiedInternal = true), "nobody", "fixture-173")
            return BackupSourcePreferences(
                "source_$id",
                store.all.map { (key, value) ->
                    BackupPreference(key, StringPreferenceValue(value as String))
                },
            )
        }
        try {
            val old = profiles.add("local-folder", prefix)
            owned += old.id
            profiles.commitConfiguration(old.id) { draft ->
                NetworkStoragePreferences(old.id, draft).save(config, "root", "fixture-173")
            }
            val oldSource = source(old.id)
            val runtime = NetworkStorageRuntime.get(context, old.id)
            val entry = runtime.backend.stat("book.bin")
            assertTrue(runtime.backend.read(entry, 0, 64).contentEquals(ByteArray(64) { 3 }))
            runtime.records.put(
                "progress",
                "book",
                StoragePendingProgress(
                    StorageProgressRecord("book", "v1", "bcd28e35-3ea3-4a34-b6cb-9ce50a739ed9", 1, 100, 3, 10),
                    pending = true,
                    requiresConfirmation = true,
                ),
                1,
            )
            val oldEvent = runtime.progress.record("book", "v1", 200, page = 4, total = 10)
            val pending = checkNotNull(runtime.records.snapshot("progress", "book"))
            val oldAccount = runtime.account
            val unused = System.currentTimeMillis() + 100_000
            check(!preferences.isKnownConnectionId(unused))
            writeBackup(
                backup,
                Backup(
                    emptyList(),
                    backupConnections = listOf(
                        BackupConnection(old.id, "local-folder", prefix),
                        BackupConnection(unused, "local-folder", "$prefix added"),
                    ),
                    backupSourcePreferences = listOf(settings(old.id, "replacement"), settings(unused, "added")),
                ),
            )
            BackupRestorer(context, BackupNotifier(context), false).restore(
                Uri.fromFile(backup),
                RestoreOptions(libraryEntries = false, categories = false, appSettings = false),
            )
            val imported = profiles.profiles().filter { it.id != old.id && it.name.startsWith(prefix) }
            owned += imported.map { it.id }
            assertEquals(2, imported.size)
            assertTrue(imported.none { it.id == unused })
            assertNotSame(oldSource, source(old.id))
            assertTrue(runCatching { runtime.session.checkActive() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { runtime.backend.stat("book.bin") }.exceptionOrNull() is CancellationException)
            val retained = NetworkStorageRuntime.get(context, old.id)
            assertNotSame(runtime, retained)
            assertEquals(oldAccount, retained.account)
            assertEquals(pending, retained.records.snapshot("progress", "book"))
            assertEquals(entry.path, retained.backend.stat("book.bin").path)
            assertTrue(
                retained.backend.read(retained.backend.stat("book.bin"), 0, 64).contentEquals(
                    ByteArray(64) {
                        3
                    },
                ),
            )
            val writers = mutableSetOf(oldEvent.device)
            for (profile in imported) {
                val settings = NetworkStoragePreferences(profile.id)
                assertEquals("nobody", settings.username)
                assertTrue(settings.configuration.needsValidation)
                assertFalse(settings.configuration.verifiedInternal)
                assertTrue(profiles.requiresIdentityReplacement(profile.id))
                val restored = NetworkStorageRuntime.get(context, profile.id)
                assertNotEquals(oldAccount, restored.account)
                assertEquals(null, restored.records.snapshot("progress", "book"))
                val unverified = runCatching { restored.backend.stat("book.bin") }.exceptionOrNull()
                assertEquals(StorageFailure.Reason.UNVERIFIED, (unverified as? StorageFailure)?.reason)
                // This is the settings validation backend; it does not activate the imported runtime.
                NetworkStorageRuntime.backend(
                    settings.configuration,
                    address,
                    settings.username,
                    settings.password,
                    validation = true,
                ).use { backend ->
                    assertEquals(config.rootIdentity, StorageIdentity.read(backend))
                    assertTrue(backend.read(backend.stat("book.bin"), 0, 64).contentEquals(ByteArray(64) { 3 }))
                }
                val local = restored.progress.record("book", "v1", 300, page = 6, total = 10)
                assertTrue(writers.add(local.device))
                val beforeFlush = restored.records.snapshot("progress", "book")
                assertTrue(runCatching { restored.progress.flush() }.isFailure)
                assertEquals(beforeFlush, restored.records.snapshot("progress", "book"))
                assertTrue(restored.records.get<StoragePendingProgress>("progress", "book")!!.pending)
            }
            assertEquals(3, writers.size)
            assertEquals(pending, retained.records.snapshot("progress", "book"))
            assertTrue(profiles.profiles().containsAll(inventory))
            assertFalse(ConnectionRestoreState.isRestoring)
        } finally {
            profiles.profiles().filter { it.name.startsWith(prefix) }.forEach { owned += it.id }
            for (id in owned) {
                check(profiles.profiles().singleOrNull { it.id == id }?.name?.startsWith(prefix) != false)
                profiles.remove(id).getOrThrow()
                repository.removeConnection(id)
            }
            temporaryPreferences.forEach(context::deleteSharedPreferences)
            backup.delete()
            if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
            foreground.close()
        }
    }

    private fun writeBackup(file: File, backup: Backup) {
        val parser = Injekt.get<ProtoBuf>()
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(parser.encodeToByteArray(Backup.serializer(), backup)) }
        }.toByteArray()
        val envelope = KohariaBackupEnvelope(
            magic = KohariaBackupEnvelope.MAGIC,
            formatVersion = KohariaBackupEnvelope.FORMAT_VERSION,
            schemaVersion = KohariaBackupEnvelope.SCHEMA_VERSION,
            createdAt = System.currentTimeMillis(),
            applicationId = "app.koharia.dev.devicefixture",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(compressed)
                .joinToString("") { "%02x".format(it) },
            compressedPayload = compressed,
        )
        file.writeBytes(parser.encodeToByteArray(KohariaBackupEnvelope.serializer(), envelope))
    }
}
