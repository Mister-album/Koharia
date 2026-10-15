package koharia.storage

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.storage.LibraryStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageMoveEvidenceDeviceTest {
    @Test fun webdavRetainsContentProofForAmbiguousMoves() = exercise(LibraryStorageMode.WEBDAV)

    @Test fun smbChecksStableIdentityBeforeSkippingContentReads() = exercise(LibraryStorageMode.SMB)

    private fun exercise(mode: LibraryStorageMode): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("app.koharia.dev.devicefixture", instrumentation.targetContext.packageName)
        val arguments = InstrumentationRegistry.getArguments()
        val port = arguments.getString("moveSmbPort")?.toInt() ?: 18445
        val username = if (mode == LibraryStorageMode.SMB) arguments.getString("moveSmbUser").orEmpty() else ""
        val password = if (mode == LibraryStorageMode.SMB) arguments.getString("moveSmbPassword").orEmpty() else ""
        val base = if (mode ==
            LibraryStorageMode.WEBDAV
        ) {
            "http://127.0.0.1:18765/"
        } else {
            "smb://127.0.0.1:$port/library/"
        }
        val config = NetworkStorageConfiguration(mode, base)
        val parent = NetworkStorageRuntime.backend(config, base, username, password)
        val folder = "move-${UUID.randomUUID()}"
        parent.createDirectory(folder)
        val address = "$base$folder/"
        val actual = NetworkStorageRuntime.backend(config.copy(address = address), address, username, password)
        val repository = Injekt.get<LibraryStorageRepository>()
        val id = Long.MAX_VALUE - System.nanoTime()
        try {
            val root = StorageIdentity.ensure(actual)
            val records = StorageRecordStore(StorageSession(id, "fixture", root) { true }, repository, Json)
            val content = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
            actual.write("book.bin", content.inputStream(), content.size.toLong())
            var contentReadBytes = 0L
            var userMoves = 0
            val backend = object : LibraryStorageBackend by actual {
                override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                    if (!entry.path.startsWith(".koharia/")) contentReadBytes += entry.size
                    actual.copyTo(entry, output)
                }
                override suspend fun move(entry: StorageEntry, destination: String) {
                    actual.move(entry, destination)
                    if (!entry.path.startsWith(".koharia/")) {
                        userMoves++
                        throw StorageFailure(StorageFailure.Reason.NETWORK)
                    }
                }
            }
            val identities = StorageResourceIdentities(backend, records)
            val source = backend.stat("book.bin")
            if (mode == LibraryStorageMode.SMB && source.identity != null) {
                val wrongOwner = runCatching { actual.move(source.copy(identity = "foreign-object"), "forbidden.bin") }
                assertEquals(StorageFailure.Reason.CONFLICT, (wrongOwner.exceptionOrNull() as? StorageFailure)?.reason)
                assertEquals(source.identity, actual.stat("book.bin").identity)
            }
            val identity = identities.identity(source)
            val failure = runCatching { StorageMutations(backend, records, identities).move(source, "target.bin") }
            assertTrue(failure.exceptionOrNull() is StorageFailure)
            val journal = records.list("mutations").map { Json.decodeFromString<StorageMutation>(it.payload) }
                .single { it.kind == "move" }
            val fast = journal.moveEvidence?.identity != null
            if (mode == LibraryStorageMode.WEBDAV) assertFalse(fast)
            if (mode == LibraryStorageMode.SMB && arguments.getString("requireFastSmb") == "true") assertTrue(fast)
            assertEquals(if (fast) 0L else content.size.toLong(), contentReadBytes)
            assertTrue(StorageMutations(backend, records, identities).reconcile().isEmpty())
            assertEquals(1, userMoves)
            assertEquals(if (fast) 0L else content.size.toLong() * 2, contentReadBytes)
            assertEquals(identity, identities.identity(backend.stat("target.bin")))
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("move_protocol", mode.name)
                    putBoolean("stable_native_identity", fast)
                    putLong("moved_content_bytes", content.size.toLong())
                    putLong("content_verification_bytes", contentReadBytes)
                    putInt("user_move_requests", userMoves)
                },
            )
            val next = backend.stat("target.bin")
            assertTrue(
                runCatching {
                    StorageMutations(backend, records, identities).move(next, "changed.bin")
                }.exceptionOrNull() is StorageFailure,
            )
            val current = actual.stat("changed.bin")
            val replacement = "external fixture change".encodeToByteArray()
            actual.delete(current)
            actual.write("changed.bin", replacement.inputStream(), replacement.size.toLong())
            assertEquals(1, StorageMutations(backend, records, identities).reconcile().count { it.kind == "move" })
            assertEquals(2, userMoves)
            assertEquals(replacement.size.toLong(), actual.stat("changed.bin").size)
        } finally {
            repository.removeConnection(id)
            suspend fun removeOwned(path: String) {
                actual.list(path).forEach { if (it.directory) removeOwned(it.path) else actual.delete(it) }
                if (path.isNotEmpty()) actual.delete(actual.stat(path))
            }
            removeOwned("")
            actual.close()
            parent.delete(parent.stat(folder))
            parent.close()
        }
    }
}
