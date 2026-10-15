package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

/** Opt-in ordinary move/recovery on an owned Samba/tmpfs directory with three generated files. */
class SmbColdDirectoryServiceTest {
    @Test
    fun coldDirectoryConflictPreservesBytesAndReconcilesAfterAnExplicitFreshMove(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_SMB_COLD_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(key: String) = config.getValue(key).jsonPrimitive.content
        check(value("fixture") == "cold-directory-recovery")
        val address = URI(value("address"))
        check(address.scheme == "smb" && address.host == "127.0.0.1" && address.port > 1024)
        check(address.path == "/scale")
        SmbStorageBackend(address.toString(), value("username"), value("password"), "").use { backend ->
            val proof = backend.stat(".fixture-proof")
            assertEquals(value("proof"), backend.read(proof, 0, proof.size.toInt()).decodeToString())
            val records = StorageRecordStore(
                StorageSession(-153, "cold-directory", "owned") { true },
                MemoryStorageRepository(),
                Json,
            )
            val identities = StorageResourceIdentities(backend, records)
            val mutations = StorageMutations(backend, records, identities)
            val snapshot = StorageSnapshot(backend, records)
            val selected = snapshot.directory("cold").directory
            assertEquals(selected, snapshot.cachedEntry("cold"))
            val originalIdentity = identities.identity(selected)
            val failure = assertThrows(StorageFailure::class.java) {
                runBlocking { mutations.move(selected, "moved") }
            }
            assertEquals(StorageFailure.Reason.CONFLICT, failure.reason)
            val current = backend.stat("cold")
            assertEquals(selected.version, current.version)
            assertNotEquals(selected.identity, current.identity)
            val pending = records.list("mutations").single()
            assertEquals(1, mutations.reconcile().size)
            assertEquals(listOf(pending), records.list("mutations"))
            assertEquals(
                StorageFailure.Reason.NOT_FOUND,
                assertThrows(StorageFailure::class.java) { runBlocking { backend.stat("moved") } }.reason,
            )
            suspend fun assertContents(root: String) {
                assertEquals(setOf("0.bin", "1.bin", "2.bin"), backend.list(root).map { it.name }.toSet())
                for (index in 0..2) {
                    val file = backend.stat("$root/$index.bin")
                    assertEquals(64L, file.size)
                    assertArrayEquals(ByteArray(64) { (index + 1).toByte() }, backend.read(file, 0, 64))
                }
            }
            assertContents("cold")
            println("SMB_COLD original-preserved;destination-absent;pending-unchanged-after-reconcile")
            // Explicit user retry uses a fresh selection; no automatic replay of the old request.
            snapshot.directory("cold", refresh = true)
            val refreshed = checkNotNull(snapshot.cachedEntry("cold"))
            assertEquals(current.identity, refreshed.identity)
            mutations.move(refreshed, "moved")
            snapshot.directory("", refresh = true)
            assertEquals(null, snapshot.cachedEntry("cold"))
            assertEquals(current.identity, snapshot.cachedEntry("moved")?.identity)
            assertContents("moved")
            assertEquals(
                StorageFailure.Reason.NOT_FOUND,
                assertThrows(StorageFailure::class.java) { runBlocking { backend.stat("cold") } }.reason,
            )
            assertEquals(listOf(pending), records.list("mutations"))
            assertTrue(mutations.reconcile().isEmpty())
            assertTrue(records.list("mutations").isEmpty())
            assertEquals(originalIdentity, identities.identity(backend.stat("moved")))
            val reopenedRecords = StorageRecordStore(
                StorageSession(-154, "cold-directory", "owned") { true },
                MemoryStorageRepository(),
                Json,
            )
            val reopenedIdentities = StorageResourceIdentities(backend, reopenedRecords)
            reopenedIdentities.refresh()
            assertEquals(originalIdentity, reopenedIdentities.identity(backend.stat("moved")))
            assertContents("moved")
            println(
                "SMB_COLD explicit-snapshot-refresh-move;old-journal-reconciled;identity-reopened;all-bytes-preserved",
            )
        }
    }
}
