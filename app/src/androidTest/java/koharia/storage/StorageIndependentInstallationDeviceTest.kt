package koharia.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageIndependentInstallationDeviceTest {
    @Test
    fun independentWritersAndRestoredHoldsUseSeparateInstallationData(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName in setOf("app.koharia.dev.devicefixture", "app.koharia.dev.einkfixture"))
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("installationPhase")
        assumeTrue(phase in setOf("write", "verify"))
        val run = checkNotNull(args.getString("installationRun"))
        require(run.matches(Regex("[0-9]+")))
        val producer = context.packageName == "app.koharia.dev.devicefixture"
        val role = if (producer) "producer" else "peer"
        val other = if (producer) "peer" else "producer"
        val mode = LibraryStorageMode.valueOf(checkNotNull(args.getString("installationMode")))
        require(mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val address = checkNotNull(args.getString("installationAddress"))
        require(address.startsWith("http://127.0.0.1:") || address.startsWith("smb://127.0.0.1:"))
        val actual = NetworkStorageRuntime.backend(
            NetworkStorageConfiguration(mode, address),
            address,
            args.getString("installationUser").orEmpty(),
            args.getString("installationPassword").orEmpty(),
        )
        val repository = Injekt.get<LibraryStorageRepository>()
        val connection = -run.toLong()
        val resultFile = File(context.cacheDir, "installation-$run.json")
        val timingFile = File(context.cacheDir, "installation-$run.timing")
        try {
            val root = StorageIdentity.read(actual)
            val store = StorageRecordStore(StorageSession(connection, "fixture", root) { true }, repository, Json)
            val installation = StorageProgress.deviceId(context)
            val progress = StorageProgress(actual, store, installation)
            if (phase == "write") {
                assertTrue(store.list("progress").isEmpty())
                assertFalse(resultFile.exists())
                val backup = StorageBackup(repository, Json)
                if (producer) {
                    progress.record("held-book", "v1", 100, page = 1, total = 30)
                    store.put("cache", "fixture-only", "temporary-byte-cache-marker")
                    val payload = backup.create(connection, "fixture", root)
                    val namespaces = Json.parseToJsonElement(payload).jsonObject.getValue("entries").jsonArray.map {
                        it.jsonObject.getValue("namespace").jsonPrimitive.content
                    }
                    assertEquals(listOf("progress"), namespaces)
                    assertFalse(payload.contains("temporary-byte-cache-marker"))
                    val bytes = payload.toByteArray()
                    actual.write("fixture-backup.json", bytes.inputStream(), bytes.size.toLong())
                    // Keep the producer's exported intent held as well, so this test only uploads common-book.
                    val pending = checkNotNull(store.get<StoragePendingProgress>("progress", "held-book"))
                    store.put(
                        "progress",
                        "held-book",
                        pending.copy(requiresConfirmation = true),
                        pending.record.sequence,
                    )
                } else {
                    withTimeout(30_000) {
                        while (runCatching { actual.stat("fixture-backup.json") }.isFailure) delay(100)
                    }
                    val entry = actual.stat("fixture-backup.json")
                    val payload = actual.read(entry, 0, entry.size.toInt()).decodeToString()
                    assertTrue(runCatching { backup.restore(connection, "wrong-root", payload) }.isFailure)
                    assertTrue(store.list("progress").isEmpty())
                    backup.restore(connection, root, payload)
                    val restored = checkNotNull(store.get<StoragePendingProgress>("progress", "held-book"))
                    assertTrue(restored.pending && restored.requiresConfirmation)
                    assertTrue(store.list("progress-writer").isEmpty())
                    assertTrue(store.list("progress-device").isEmpty())
                    assertTrue(store.list("cache").isEmpty())
                    val noRequests = object : LibraryStorageBackend by actual {
                        override suspend fun stat(path: String): StorageEntry = error("Held restore performed stat")
                        override suspend fun list(
                            path: String,
                        ): List<StorageEntry> = error("Held restore performed list")
                    }
                    StorageProgress(noRequests, store, installation).flush()
                    backup.restore(connection, root, payload)
                    assertEquals(restored, store.get<StoragePendingProgress>("progress", "held-book"))
                }
                val event = progress.record(
                    "common-book",
                    "v1",
                    if (producer) 200 else 300,
                    page = if (producer) 10 else 20,
                    total = 30,
                )
                val pendingRow = store.list("progress").single { it.key == "common-book" }
                val ready = Json.encodeToString(event).toByteArray()
                actual.write("ready-$role.json", ready.inputStream(), ready.size.toLong())
                withTimeout(30_000) {
                    while (runCatching { actual.stat("ready-$other.json") }.isFailure) delay(100)
                }
                val peer = actual.stat("ready-$other.json")
                val peerEvent = Json.decodeFromString<StorageProgressRecord>(
                    actual.read(peer, 0, peer.size.toInt()).decodeToString(),
                )
                assertNotEquals(event.device, peerEvent.device)
                val scheduled = checkNotNull(args.getString("installationStartAt")).toLong()
                val wait = scheduled - System.currentTimeMillis()
                check(wait in 1..30_000) { "Both independent installations must reach the scheduled upload" }
                delay(wait)
                val started = System.currentTimeMillis()
                progress.flush()
                timingFile.writeText("$started,${System.currentTimeMillis()},$installation")
                assertFalse(checkNotNull(store.get<StoragePendingProgress>("progress", "common-book")).pending)
                // Reproduce a lost local acknowledgement using only this test's isolated SQL row.
                repository.put(connection, "fixture", root, "progress", pendingRow)
                StorageProgress(actual, store, installation).flush()
                assertFalse(checkNotNull(store.get<StoragePendingProgress>("progress", "common-book")).pending)
                progress.flush()
                resultFile.writeText(Json.encodeToString(event))
            } else {
                val own = Json.decodeFromString<StorageProgressRecord>(resultFile.readText())
                assertEquals(own, progress.cached("common-book"))
                assertFalse(checkNotNull(store.get<StoragePendingProgress>("progress", "common-book")).pending)
                assertTrue(
                    checkNotNull(store.get<StoragePendingProgress>("progress", "held-book")).requiresConfirmation,
                )
                assertEquals(own.device, store.get<String>("progress-writer", installation))
                val remote = actual.list(".koharia/progress").map {
                    Json.decodeFromString<StorageProgressRecord>(actual.read(it, 0, it.size.toInt()).decodeToString())
                }
                assertEquals(2, remote.size)
                assertEquals(2, remote.map { it.device }.toSet().size)
                assertEquals(setOf(10, 20), remote.map { it.page }.toSet())
                assertTrue(remote.all { it.resource == "common-book" })
                assertTrue(own in remote)
                repository.removeConnection(connection)
                assertTrue(resultFile.delete())
                assertTrue(timingFile.delete())
            }
        } finally {
            actual.close()
        }
    }
}
