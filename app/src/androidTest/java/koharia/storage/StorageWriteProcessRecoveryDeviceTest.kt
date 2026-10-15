package koharia.storage

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageWriteProcessRecoveryDeviceTest {
    @Serializable
    private data class Checkpoint(
        val connection: Long,
        val process: Int,
        val operation: StorageMutation,
        val original: StorageEntry,
    )

    @Test
    fun interruptedUploadRetainsSqlJournalWithoutPublishingOrReplay(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("writeRecoveryPhase")
        assumeTrue(phase in setOf("prepare", "recover"))
        val run = checkNotNull(args.getString("writeRecoveryRun"))
        require(run.matches(Regex("[0-9]+")))
        val mode = LibraryStorageMode.valueOf(checkNotNull(args.getString("writeRecoveryMode")))
        require(mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val address = checkNotNull(args.getString("writeRecoveryAddress"))
        require(address.startsWith("http://127.0.0.1:") || address.startsWith("smb://127.0.0.1:"))
        val checkpointFile = File(context.cacheDir, "write-recovery-$run.json")
        val blockedFile = File(context.cacheDir, "write-recovery-$run.blocked")
        val replacement = File(context.cacheDir, "write-recovery-$run.bin")
        val repository = Injekt.get<LibraryStorageRepository>()
        val actual = NetworkStorageRuntime.backend(
            NetworkStorageConfiguration(mode, address),
            address,
            args.getString("writeRecoveryUser").orEmpty(),
            args.getString("writeRecoveryPassword").orEmpty(),
        )
        try {
            val previous = if (phase == "recover") {
                Json.decodeFromString<Checkpoint>(checkpointFile.readText()).also {
                    assertNotEquals(it.process, Process.myPid())
                    assertTrue(blockedFile.exists())
                }
            } else {
                check(!checkpointFile.exists() && !blockedFile.exists() && !replacement.exists())
                null
            }
            val connection = previous?.connection ?: (Long.MAX_VALUE - System.nanoTime())
            val records =
                StorageRecordStore(StorageSession(connection, "fixture", "write-$run") { true }, repository, Json)
            var writeCalls = 0
            val guarded = object : LibraryStorageBackend by actual {
                override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                    check(phase == "prepare") { "Recovery must never replay an upload" }
                    writeCalls++
                    val operation = Json.decodeFromString<StorageMutation>(records.list("mutations").single().payload)
                    assertEquals("prepared", operation.phase)
                    assertEquals(".koharia-write-${operation.id}.tmp", path)
                    val checkpoint = Checkpoint(connection, Process.myPid(), operation, actual.stat("book.bin"))
                    checkpointFile.outputStream().use {
                        it.write(Json.encodeToString(checkpoint).toByteArray())
                        it.fd.sync()
                    }
                    val gated = object : FilterInputStream(data) {
                        var sent = 0
                        val limit = if (mode == LibraryStorageMode.SMB) 2 * 1024 * 1024 else 1024 * 1024
                        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                            if (sent >= limit) {
                                blockedFile.writeText("upload-in-progress")
                                check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) {
                                    "Host did not terminate fixture"
                                }
                            }
                            return super.read(bytes, offset, minOf(count, limit - sent)).also {
                                if (it > 0) sent += it
                            }
                        }
                        override fun read(): Int = error("Expected bulk upload reads")
                    }
                    actual.write(path, gated, length, expectedVersion)
                    error("Prepare phase must end by host force-stop during upload")
                }
                override suspend fun move(entry: StorageEntry, destination: String) =
                    error("Partial upload must never be published")
                override suspend fun delete(entry: StorageEntry) = error("Recovery must retain partial data")
            }
            if (phase == "prepare") {
                assertEquals(64L, actual.stat("book.bin").size)
                replacement.writeBytes(ByteArray(4 * 1024 * 1024) { 7 })
                StorageMutations(guarded, records).write("book.bin", replacement, actual.stat("book.bin"))
                error("Host termination required")
            } else {
                val checkpoint = checkNotNull(previous)
                val before = records.list("mutations").single()
                assertEquals(checkpoint.operation, Json.decodeFromString<StorageMutation>(before.payload))
                val pending = StorageMutations(guarded, records).reconcile()
                assertEquals(listOf(checkpoint.operation), pending)
                assertEquals(before, records.list("mutations").single())
                assertEquals(checkpoint.original, actual.stat("book.bin"))
                assertTrue(actual.read(actual.stat("book.bin"), 0, 64).contentEquals(ByteArray(64) { 3 }))
                val temporary = actual.stat(".koharia-write-${checkpoint.operation.id}.tmp")
                assertTrue(temporary.size in 1 until checkpoint.operation.contentSize!!)
                assertEquals(0, writeCalls)
                println(
                    "WRITE_RECOVERY_OK mode=$mode previousPid=${checkpoint.process} pid=${Process.myPid()} partialBytes=${temporary.size} pending=1 writes=0",
                )
                repository.removeConnection(connection)
                assertTrue(checkpointFile.delete())
                assertTrue(blockedFile.delete())
                assertTrue(replacement.delete())
            }
        } finally {
            actual.close()
        }
    }

    @Test
    fun removeOnlyInterruptedFixtureState(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val run = InstrumentationRegistry.getArguments().getString("writeRecoveryCleanupRun")
        assumeTrue(run != null)
        require(checkNotNull(run).matches(Regex("[0-9]+")))
        val file = File(context.cacheDir, "write-recovery-$run.json")
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        Injekt.get<LibraryStorageRepository>().removeConnection(checkpoint.connection)
        for (suffix in listOf("json", "blocked", "bin")) {
            val owned = File(context.cacheDir, "write-recovery-$run.$suffix")
            assertTrue(!owned.exists() || owned.delete())
        }
    }
}
