package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

/** Opt-in file-copy fallback against an owned filesystem DAV service. */
class WebDavFallbackServiceTest {
    @Test
    fun unsupportedMoveCopiesOnceAndPreservesExternalReplacementDuringRecovery(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_DAV_FALLBACK_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(key: String) = config.getValue(key).jsonPrimitive.content
        check(value("fixture") == "dav-file-fallback")
        val address = URI(value("address"))
        check(address.scheme == "http" && address.host == "127.0.0.1" && address.port > 1024)
        check(address.path == "/")
        val scratch = File(manifest.parentFile, "scratch")
        check(!scratch.exists())
        WebDavStorageBackend(OkHttpClient(), address.toString(), "", "").use { backend ->
            val proof = backend.stat(".fixture-proof")
            assertEquals(value("proof"), backend.read(proof, 0, proof.size.toInt()).decodeToString())
            for ((index, mode) in listOf("normal", "replaced").withIndex()) {
                val repository = MemoryStorageRepository()
                val session = StorageSession(-154L - index, mode, "owned") { true }
                val records = StorageRecordStore(session, repository, Json)
                val identities = StorageResourceIdentities(backend, records)
                val mutations = StorageMutations(backend, records, identities, scratch)
                val snapshot = StorageSnapshot(backend, records)
                snapshot.directory(mode)
                val selected = checkNotNull(snapshot.cachedEntry("$mode/source.bin"))
                val originalIdentity = identities.identity(selected)
                val progress = StorageProgress(backend, records, "owned-writer", remoteEnabled = false)
                val originalProgress = progress.record(originalIdentity, selected.version, 1, page = 2, total = 4)
                val target = "$mode/target.bin"
                if (mode == "normal") {
                    mutations.move(selected, target)
                    assertTrue(records.list("mutations").isEmpty())
                    assertEquals(originalIdentity, identities.identity(backend.stat(target)))
                    assertEquals(
                        StorageFailure.Reason.NOT_FOUND,
                        assertThrows(StorageFailure::class.java) { runBlocking { backend.stat(selected.path) } }.reason,
                    )
                } else {
                    val failure = assertThrows(StorageFailure::class.java) {
                        runBlocking { mutations.move(selected, target) }
                    }
                    assertEquals(StorageFailure.Reason.CONFLICT, failure.reason)
                    val pending = records.list("mutations").single()
                    assertEquals("copied", Json.decodeFromString<StorageMutation>(pending.payload).phase)
                    // Recreate the orchestrator and store over the existing journal, without replaying MOVE/PUT.
                    val reopened = StorageRecordStore(session, repository, Json)
                    val reopenedIdentities = StorageResourceIdentities(backend, reopened)
                    val recovery = StorageMutations(backend, reopened, reopenedIdentities, scratch)
                    assertEquals(1, recovery.reconcile().size)
                    assertEquals(listOf(pending), reopened.list("mutations"))
                    val replacement = backend.stat(selected.path)
                    assertNotEquals(selected.version, replacement.version)
                    assertArrayEquals(ByteArray(65) { 9 }, backend.read(replacement, 0, 65))
                    val replacementIdentity = reopenedIdentities.identity(replacement)
                    assertNotEquals(originalIdentity to selected.version, replacementIdentity to replacement.version)
                    val reopenedProgress = StorageProgress(backend, reopened, "owned-writer", remoteEnabled = false)
                    assertEquals(null, reopenedProgress.pull(replacementIdentity, replacement.version))
                    assertEquals(originalProgress, reopenedProgress.pull(originalIdentity, selected.version))
                    assertNotEquals(originalIdentity, reopenedIdentities.identity(backend.stat(target)))
                }
                val copied = backend.stat(target)
                assertArrayEquals(ByteArray(64) { (index + 1).toByte() }, backend.read(copied, 0, 64))
                snapshot.directory(mode, refresh = true)
                assertEquals(mode == "replaced", snapshot.cachedEntry(selected.path) != null)
                assertTrue(snapshot.cachedEntry(target) != null)
                assertTrue(checkNotNull(scratch.listFiles()).isEmpty())
                println("DAV_FALLBACK $mode: bytes, journal, identity and refreshed snapshot verified")
            }
        }
    }
}
