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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class StorageMoveRecoveryDeviceTest {
    @Serializable private data class Checkpoint(val connection: Long, val root: String, val process: Int)

    @Test fun stableMoveEvidenceSurvivesReaderProcessExit(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("moveRecoveryPhase")
        assumeTrue(phase in setOf("prepare", "recover"))
        val run = checkNotNull(arguments.getString("moveRecoveryRun"))
        require(run.matches(Regex("[0-9]+")))
        val checkpointFile = File(context.cacheDir, "move-recovery-$run.json")
        val port = checkNotNull(arguments.getString("moveSmbPort")).toInt()
        val username = arguments.getString("moveSmbUser").orEmpty()
        val password = arguments.getString("moveSmbPassword").orEmpty()
        val base = "smb://127.0.0.1:$port/library/"
        val folder = "restart-$run"
        val config = NetworkStorageConfiguration(LibraryStorageMode.SMB, base)
        val parent = NetworkStorageRuntime.backend(config, base, username, password)
        val address = "$base$folder/"
        val actual = NetworkStorageRuntime.backend(config.copy(address = address), address, username, password)
        val repository = Injekt.get<LibraryStorageRepository>()
        var checkpoint: Checkpoint? = null
        var preserveForRestart = false
        try {
            val owner = if (phase == "prepare") {
                check(!checkpointFile.exists())
                parent.createDirectory(folder)
                Checkpoint(Long.MAX_VALUE - System.nanoTime(), StorageIdentity.ensure(actual), Process.myPid())
            } else {
                Json.decodeFromString<Checkpoint>(checkpointFile.readText()).also {
                    assertNotEquals(it.process, Process.myPid())
                    assertEquals(it.root, StorageIdentity.read(actual))
                }
            }
            checkpoint = owner
            val records =
                StorageRecordStore(StorageSession(owner.connection, "fixture", owner.root) { true }, repository, Json)
            val backend = object : LibraryStorageBackend by actual {
                override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                    error("A verified native move must not read file contents")
                }
                override suspend fun move(entry: StorageEntry, destination: String) {
                    check(phase == "prepare") { "Recovery must not issue a move" }
                    actual.move(entry, destination)
                    if (!entry.path.startsWith(".koharia/")) throw StorageFailure(StorageFailure.Reason.NETWORK)
                }
            }
            if (phase == "prepare") {
                val content = ByteArray(4 * 1024 * 1024) { 7 }
                actual.write("book.bin", content.inputStream(), content.size.toLong())
                val outcome =
                    runCatching { StorageMutations(backend, records).move(backend.stat("book.bin"), "target.bin") }
                assertTrue(outcome.exceptionOrNull() is StorageFailure)
                val pending = Json.decodeFromString<StorageMutation>(records.list("mutations").single().payload)
                assertNotNull(pending.moveEvidence?.identity)
                assertEquals(StorageMoveVerifier.SMB_CONTRACT, pending.moveEvidence?.contract)
                checkpointFile.writeText(Json.encodeToString(owner))
                preserveForRestart = true
            } else {
                assertTrue(StorageMutations(backend, records).reconcile().isEmpty())
                assertEquals(4 * 1024 * 1024L, backend.stat("target.bin").size)
                assertTrue(records.list("mutations").isEmpty())
            }
        } finally {
            try {
                if (!preserveForRestart && checkpoint != null) {
                    repository.removeConnection(checkpoint.connection)
                    suspend fun removeOwned(path: String) {
                        actual.list(path).forEach { if (it.directory) removeOwned(it.path) else actual.delete(it) }
                        if (path.isNotEmpty()) actual.delete(actual.stat(path))
                    }
                    removeOwned("")
                    parent.delete(parent.stat(folder))
                    assertTrue(!checkpointFile.exists() || checkpointFile.delete())
                }
            } finally {
                actual.close()
                parent.close()
            }
        }
    }
}
