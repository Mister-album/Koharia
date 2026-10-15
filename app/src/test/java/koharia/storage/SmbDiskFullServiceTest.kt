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
import java.io.InputStream
import java.net.URI

/** Opt-in upload into an owned Samba share backed by a bounded disposable filesystem. */
class SmbDiskFullServiceTest {
    @Test
    fun partialUploadOnDiskFullRetainsOriginalAndJournalWithoutReplay(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_SMB_DISK_FULL_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(key: String) = config.getValue(key).jsonPrimitive.content
        check(value("fixture") == "smb-disk-full")
        val address = URI(value("address"))
        check(address.scheme == "smb" && address.host == "127.0.0.1" && address.port > 1024)
        check(address.path == "/limited")
        SmbStorageBackend(address.toString(), value("username"), value("password"), "").use { actual ->
            val proof = actual.stat(".fixture-proof")
            assertEquals(value("proof"), actual.read(proof, 0, proof.size.toInt()).decodeToString())
            var uploads = 0
            var moves = 0
            var deletes = 0
            val backend = object : LibraryStorageBackend by actual {
                override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                    uploads++
                    actual.write(path, data, length, expectedVersion)
                }

                override suspend fun move(entry: StorageEntry, destination: String) {
                    moves++
                    actual.move(entry, destination)
                }

                override suspend fun delete(entry: StorageEntry) {
                    deletes++
                    actual.delete(entry)
                }
            }
            val repository = MemoryStorageRepository()
            val session = StorageSession(-155, "disk-full", "owned") { true }
            val records = StorageRecordStore(session, repository, Json)
            val replacement = File(manifest.parentFile, "replacement.bin")
            check(!replacement.exists())
            replacement.outputStream().use { output -> repeat(64) { output.write(ByteArray(64 * 1024) { 7 }) } }
            val original = backend.stat("book.bin")
            val failure = assertThrows(StorageFailure::class.java) {
                runBlocking { StorageMutations(backend, records).write("book.bin", replacement, original) }
            }
            val pending = records.list("mutations").single()
            val operation = Json.decodeFromString<StorageMutation>(pending.payload)
            assertEquals("prepared", operation.phase)
            val staged = backend.stat(".koharia-write-${operation.id}.tmp")
            assertTrue(staged.size in 1 until replacement.length())
            assertArrayEquals(ByteArray(staged.size.toInt()) { 7 }, actual.read(staged, 0, staged.size.toInt()))
            assertArrayEquals(ByteArray(64) { 3 }, actual.read(actual.stat("book.bin"), 0, 64))
            assertEquals(original.version, actual.stat("book.bin").version)
            val reopened = StorageRecordStore(session, repository, Json)
            assertEquals(1, StorageMutations(backend, reopened).reconcile().size)
            assertEquals(listOf(pending), reopened.list("mutations"))
            assertEquals(staged, actual.stat(staged.path))
            assertEquals(1, uploads)
            assertEquals(0, moves)
            assertEquals(0, deletes)
            println("SMB_DISK_FULL partial=${staged.size};original=64;uploads=1;moves=0;deletes=0;pending=1")
            println(
                "SMB_DISK_FULL failure=${failure.reason};causes=${generateSequence<Throwable>(failure) {
                    it.cause
                }.map { it.javaClass.simpleName }.toList()}",
            )
            assertEquals(StorageFailure.Reason.SPACE, failure.reason)
        }
    }
}
