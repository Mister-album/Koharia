package koharia.storage

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.hasPausedConnectionWrites
import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import koharia.source.local.LocalFolderSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class StorageIdentityRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val oldId: Long,
        val newId: Long = 0,
        val oldAccount: String = "",
        val newAccount: String = "",
        val oldPayload: String = "",
        val oldRevision: Long = 0,
        val newWriter: String = "",
        val process: Int,
        val hadActive: Boolean,
        val active: Long,
    )

    @Test
    fun credentialsKeepPendingProgressWithOriginalOwnerAfterRestart(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("identityRestartPhase")
        assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("identityRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val mode = LibraryStorageMode.valueOf(checkNotNull(args.getString("identityRestartMode")))
        require(mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val address = checkNotNull(args.getString("identityRestartAddress"))
        require(address.startsWith("http://127.0.0.1:") || address.startsWith("smb://127.0.0.1:"))
        val root = "e13f2ba1-8040-4bca-960a-5ac54ec78c34"
        val config = NetworkStorageConfiguration(mode, address, rootIdentity = root)
        val file = File(context.cacheDir, "storage-identity-restart-$run.json")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<LibraryStorageRepository>()
        suspend fun source(id: Long): LocalFolderSource = withTimeout(10_000) {
            while (sources.get(id) !is LocalFolderSource) delay(25)
            sources.get(id) as LocalFolderSource
        }
        fun store(
            id: Long,
            account: String,
        ) = StorageRecordStore(StorageSession(id, account, root) { true }, repository, Json)
        fun save(checkpoint: Checkpoint) = file.outputStream().use {
            it.write(Json.encodeToString(checkpoint).toByteArray())
            it.fd.sync()
        }
        if (phase == "prepare") {
            check(!file.exists())
            val hadActive = connections.activeConnectionId.isSet()
            val active = connections.activeConnectionId.get()
            val old = profiles.add("local-folder", "Storage identity restart $run")
            var checkpoint = Checkpoint(old.id, process = Process.myPid(), hadActive = hadActive, active = active)
            save(checkpoint)
            profiles.commitConfiguration(old.id) { draft ->
                NetworkStoragePreferences(old.id, draft).save(config, "root", "fixture-169")
            }
            source(old.id).close()
            val oldAccount = NetworkStoragePreferences(old.id).account
            NetworkStorageRuntime.backend(config, address, "root", "fixture-169").use { backend ->
                assertEquals(root, StorageIdentity.read(backend))
                val progress = StorageProgress(backend, store(old.id, oldAccount), StorageProgress.deviceId(context))
                assertTrue(progress.review("book", "v1") == null)
                progress.record("book", "v1", 100, page = 3, total = 10)
            }
            val oldRecord = checkNotNull(store(old.id, oldAccount).snapshot("progress", "book"))
            checkpoint =
                checkpoint.copy(
                    oldAccount = oldAccount,
                    oldPayload = oldRecord.payload,
                    oldRevision = oldRecord.revision,
                )
            save(checkpoint)
            val replacement = profiles.replaceIdentity(old.id, old.name) { id ->
                NetworkStoragePreferences(id).save(config, "nobody", "fixture-169")
            }
            checkpoint = checkpoint.copy(newId = replacement.id)
            save(checkpoint)
            source(replacement.id).close()
            val newAccount = NetworkStoragePreferences(replacement.id).account
            assertNotEquals(oldAccount, newAccount)
            NetworkStorageRuntime.backend(config, address, "nobody", "fixture-169").use { backend ->
                assertEquals(root, StorageIdentity.read(backend))
                val progress =
                    StorageProgress(backend, store(replacement.id, newAccount), StorageProgress.deviceId(context))
                assertTrue(progress.review("book", "v1") == null)
                val event = progress.record("book", "v1", 200, page = 6, total = 10)
                checkpoint = checkpoint.copy(newAccount = newAccount, newWriter = event.device)
                save(checkpoint)
            }
            assertTrue(profiles.requiresIdentityReplacement(old.id))
            return@runBlocking
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        if (phase == "recover") {
            val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
            try {
                assertNotEquals(checkpoint.process, Process.myPid())
                source(checkpoint.oldId)
                source(checkpoint.newId)
                assertTrue(profiles.requiresIdentityReplacement(checkpoint.oldId))
                assertEquals("root", NetworkStoragePreferences(checkpoint.oldId).username)
                assertEquals("nobody", NetworkStoragePreferences(checkpoint.newId).username)
                val current = store(checkpoint.newId, checkpoint.newAccount)
                // Source registration and runtime connectivity callbacks own automatic recovery.
                try {
                    withTimeout(30_000) {
                        while (current.get<StoragePendingProgress>("progress", "book")?.pending != false) delay(50)
                    }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    val failure = runCatching {
                        NetworkStorageRuntime.get(context, checkpoint.newId).progress.flush()
                    }.exceptionOrNull()
                    throw AssertionError(
                        "Automatic storage recovery failed; initialized=" + sources.isInitialized.value +
                            ", paused=" +
                            Injekt.get<koharia.connection.ConnectionIdentityChanges>().isPaused(checkpoint.newId) +
                            ", network=" + Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.value,
                        failure ?: timeout,
                    )
                }
                val old = NetworkStorageRuntime.get(context, checkpoint.oldId)
                val failure = runCatching { old.progress.flush() }.exceptionOrNull()
                assertTrue("Old runtime must reject writes", failure?.hasPausedConnectionWrites() == true)
                assertEquals(
                    StorageRecord("book", checkpoint.oldPayload, checkpoint.oldRevision),
                    old.records.snapshot("progress", "book"),
                )
                assertTrue(old.progress.review("book", "v1") == null)
                val latest = checkNotNull(current.get<StoragePendingProgress>("progress", "book"))
                assertFalse(latest.pending)
                assertEquals(6, latest.record.page)
                assertEquals(200L, latest.record.modifiedAt)
                assertEquals(checkpoint.newWriter, latest.record.device)
            } finally {
                foreground.close()
            }
        }
        for (id in listOf(checkpoint.oldId, checkpoint.newId).filter { it > 0 }) {
            val profile = profiles.profiles().singleOrNull { it.id == id }
            check(profile == null || profile.name.startsWith("Storage identity restart $run"))
            profiles.remove(id).getOrThrow()
            repository.removeConnection(id)
        }
        if (checkpoint.hadActive) {
            connections.activeConnectionId.set(
                checkpoint.active,
            )
        } else {
            connections.activeConnectionId.delete()
        }
        assertTrue(file.delete())
    }
}
