package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Opt-in PUT truncation by an owned loopback proxy in front of a filesystem DAV service. */
class WebDavInterruptedWriteServiceTest {
    @Test
    fun truncatedUploadKeepsOriginalAndPendingWhileCompleteUploadCanBePublished(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_DAV_INTERRUPTED_WRITE_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(key: String) = config.getValue(key).jsonPrimitive.content
        check(value("fixture") == "dav-interrupted-write")
        val address = URI(value("address"))
        check(address.scheme == "http" && address.host == "127.0.0.1" && address.port > 1024)
        check(address.path == "/")
        fun backend() = WebDavStorageBackend(
            OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
            address.toString(),
            "",
            "",
        )
        backend().use { actual ->
            val proof = actual.stat(".fixture-proof")
            assertEquals(value("proof"), actual.read(proof, 0, proof.size.toInt()).decodeToString())
            val replacement = File(manifest.parentFile, "replacement.bin")
            check(!replacement.exists())
            val expected = ByteArray(4 * 1024 * 1024) { 7 }
            replacement.writeBytes(expected)
            for (mode in listOf("complete", "interrupted")) {
                val session = StorageSession(-156, mode, "owned") { true }
                val repository = MemoryStorageRepository()
                val records = StorageRecordStore(session, repository, Json)
                val original = actual.stat("$mode/book.bin")
                val mutations = StorageMutations(actual, records)
                if (mode == "complete") {
                    mutations.write(original.path, replacement, original)
                    assertTrue(records.list("mutations").isEmpty())
                    val target = actual.stat(original.path)
                    assertEquals(expected.size.toLong(), target.size)
                    assertArrayEquals(expected, actual.read(target, 0, expected.size))
                    assertEquals(listOf("book.bin"), actual.list(mode).map { it.name })
                } else {
                    assertThrows(IOException::class.java) {
                        runBlocking { mutations.write(original.path, replacement, original) }
                    }
                    val pending = records.list("mutations").single()
                    val operation = Json.decodeFromString<StorageMutation>(pending.payload)
                    assertEquals("prepared", operation.phase)
                    val stagingPath = "$mode/.koharia-write-${operation.id}.tmp"
                    backend().use { reopenedBackend ->
                        val staged = reopenedBackend.stat(stagingPath)
                        assertEquals(64L * 1024, staged.size)
                        assertArrayEquals(
                            expected.copyOf(staged.size.toInt()),
                            reopenedBackend.read(staged, 0, staged.size.toInt()),
                        )
                        val reopened = StorageRecordStore(session, repository, Json)
                        assertEquals(1, StorageMutations(reopenedBackend, reopened).reconcile().size)
                        assertEquals(listOf(pending), reopened.list("mutations"))
                        val target = reopenedBackend.stat(original.path)
                        assertEquals(original, target)
                        assertArrayEquals(ByteArray(64) { 3 }, reopenedBackend.read(target, 0, 64))
                        assertEquals(staged, reopenedBackend.stat(stagingPath))
                        assertEquals(setOf("book.bin", staged.name), reopenedBackend.list(mode).map { it.name }.toSet())
                    }
                }
                println("DAV_INTERRUPTED_WRITE $mode: actual bytes and journal verified")
            }
        }
    }
}
