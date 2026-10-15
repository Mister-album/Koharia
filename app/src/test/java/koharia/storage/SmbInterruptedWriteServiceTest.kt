package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

/** Opt-in disconnection after a real SMB WRITE acknowledgement on an owned loopback share. */
class SmbInterruptedWriteServiceTest {
    @Test
    fun interruptedUploadRetainsPartialFileAndOriginalWithoutReplayingOnNewConnection(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_SMB_INTERRUPTED_WRITE_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(key: String) = config.getValue(key).jsonPrimitive.content
        check(value("fixture") == "smb-interrupted-write")
        val address = URI(value("address"))
        check(address.scheme == "smb" && address.host == "127.0.0.1" && address.port > 1024)
        check(address.path == "/interrupted")
        fun backend() = SmbStorageBackend(address.toString(), value("username"), value("password"), "")
        val replacement = File(manifest.parentFile, "replacement.bin")
        check(!replacement.exists())
        replacement.outputStream().use { output -> repeat(64) { output.write(ByteArray(64 * 1024) { 7 }) } }
        val repository = MemoryStorageRepository()
        val session = StorageSession(-157, "interrupted", "owned") { true }
        val records = StorageRecordStore(session, repository, Json)
        val original: StorageEntry
        backend().use { actual ->
            val proof = actual.stat(".fixture-proof")
            assertEquals(value("proof"), actual.read(proof, 0, proof.size.toInt()).decodeToString())
            // Samba may initialize DOS creation metadata on the first content open of a seeded file.
            val seeded = actual.stat("book.bin")
            assertArrayEquals(ByteArray(64) { 3 }, actual.read(seeded, 0, 64))
            original = actual.stat("book.bin")
            val failure = assertThrows(StorageFailure::class.java) {
                runBlocking { StorageMutations(actual, records).write("book.bin", replacement, original) }
            }
            assertEquals(StorageFailure.Reason.NETWORK, failure.reason)
        }
        val pending = records.list("mutations").single()
        val operation = Json.decodeFromString<StorageMutation>(pending.payload)
        assertEquals("prepared", operation.phase)
        backend().use { reopenedBackend ->
            val staged = reopenedBackend.stat(".koharia-write-${operation.id}.tmp")
            assertTrue(staged.size in 1 until replacement.length())
            assertArrayEquals(
                ByteArray(staged.size.toInt()) {
                    7
                },
                reopenedBackend.read(staged, 0, staged.size.toInt()),
            )
            val reopened = StorageRecordStore(session, repository, Json)
            assertEquals(1, StorageMutations(reopenedBackend, reopened).reconcile().size)
            assertEquals(listOf(pending), reopened.list("mutations"))
            assertEquals(original, reopenedBackend.stat("book.bin"))
            assertArrayEquals(ByteArray(64) { 3 }, reopenedBackend.read(original, 0, 64))
            assertEquals(staged, reopenedBackend.stat(staged.path))
            assertEquals(
                setOf(".fixture-proof", ".koharia", "book.bin", staged.name),
                reopenedBackend.list("").map { it.name }.toSet(),
            )
            println(
                "SMB_INTERRUPTED_WRITE partial=${staged.size};original=64;prepared-journal-preserved;new-connection",
            )
        }
    }
}
