package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** Opt-in measurements against the owned, authenticated loopback scale fixture only. */
class StorageMoveScaleServiceTest {
    @Test
    fun webDavFileMoveScaleMatrix(): Unit = runBlocking { webDavMoveScaleMatrix(directories = false) }

    @Test
    fun webDavDirectoryMoveScaleMatrix(): Unit = runBlocking { webDavMoveScaleMatrix(directories = true) }

    private suspend fun webDavMoveScaleMatrix(directories: Boolean) {
        val configured = System.getenv("KOHARIA_WEBDAV_SCALE_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(name: String) = config.getValue(name).jsonPrimitive.content
        val address = value("address").toHttpUrl()
        check(address.scheme == "http" && address.host == "127.0.0.1" && address.port > 1024)
        val metrics = value("metrics").toHttpUrl()
        check(metrics.host == "127.0.0.1" && metrics.port == address.port && metrics.encodedPath == "/__metrics")
        val output = File(
            manifest.parentFile,
            if (directories) "webdav-directory-scale-results.jsonl" else "webdav-scale-results.jsonl",
        )
        check(!output.exists()) { "Retain prior measurements; provision a new fixture before another run" }
        // Match the NetworkHelper client supplied by NetworkStorageRuntime in production.
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .build()
        fun counters(): Map<String, Long> = client.newCall(Request.Builder().url(metrics).build()).execute().use {
            check(it.code == 200)
            Json.parseToJsonElement(it.body.string()).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.long }
        }
        try {
            WebDavStorageBackend(client, address.toString(), "", "").use { remote ->
                val proof = remote.stat(".fixture-proof")
                check(proof.size == value("proof").length.toLong())
                check(remote.read(proof, 0, proof.size.toInt()).decodeToString() == value("proof"))
                val backend = CountedBackend(remote)
                val records =
                    StorageRecordStore(
                        StorageSession(-108, "dav-scale", "owned") {
                            true
                        },
                        MemoryStorageRepository(),
                        Json,
                    )
                val journal = StorageMutations(backend, records)
                val scales = if (directories) listOf(100L, 1000L, 10000L) else listOf(1048576L, 104857600L, 1073741824L)
                for (size in scales) {
                    val source = if (directories) "directory-$size" else "file-$size"
                    val entry = backend.stat(source)
                    assertEquals(directories, entry.directory)
                    if (!directories) assertEquals(size, entry.size)
                    val destination = "$source-moved"
                    backend.reset()
                    val before = counters()
                    val started = System.nanoTime()
                    journal.move(entry, destination)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val after = counters()
                    val expectedBytes = 2 * size * if (directories) 64 else 1
                    val expectedCopies = if (directories) 2 * size else 2
                    assertEquals(expectedBytes, backend.contentBytes)
                    assertEquals(expectedCopies.toInt(), backend.contentCopies)
                    assertEquals(1, backend.moves)
                    assertEquals(1L, after.getValue("MOVE") - before.getValue("MOVE"))
                    assertEquals(expectedCopies, after.getValue("GET") - before.getValue("GET"))
                    assertTrue(
                        after.getValue("response_body_bytes") - before.getValue("response_body_bytes") >= expectedBytes,
                    )
                    assertTrue(records.list("mutations").isEmpty())
                    val missing = assertThrows(StorageFailure::class.java) { runBlocking { remote.stat(source) } }
                    assertEquals(StorageFailure.Reason.NOT_FOUND, missing.reason)
                    val moved = remote.stat(destination)
                    if (directories) {
                        assertTrue(moved.directory)
                        assertEquals(size.toInt(), remote.list(destination).size)
                        for (index in listOf(0L, size - 1)) {
                            val child = remote.stat("$destination/${index.toString().padStart(5, '0')}.bin")
                            assertArrayEquals(ByteArray(64) { (index % 251).toByte() }, remote.read(child, 0, 64))
                        }
                        assertEquals(1L, after.getValue("LOCK") - before.getValue("LOCK"))
                        assertEquals(1L, after.getValue("UNLOCK") - before.getValue("UNLOCK"))
                    } else {
                        assertEquals(size, moved.size)
                        val head = "Koharia scale fixture".encodeToByteArray()
                        assertArrayEquals(head, remote.read(moved, 0, head.size))
                        assertArrayEquals(byteArrayOf('!'.code.toByte()), remote.read(moved, size - 1, 1))
                    }
                    val row = buildJsonObject {
                        put("case", source)
                        put("scale", size)
                        put("directory", directories)
                        put("content_bytes", backend.contentBytes)
                        put("content_copies", backend.contentCopies)
                        put("elapsed_ms", elapsed)
                        put("retained_pending", 0)
                        before.keys.forEach { key -> put(key, after.getValue(key) - before.getValue(key)) }
                    }
                    output.appendText("$row\n")
                    println("MOVE_SCALE $row")
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun authenticatedSmbMoveScaleMatrix(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_SMB_SCALE_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(name: String) = config.getValue(name).jsonPrimitive.content
        val address = URI(value("address"))
        check(address.scheme == "smb" && address.host == "127.0.0.1" && address.port > 1024 && address.path == "/scale")
        val metricsUrl = value("metrics").toHttpUrl()
        check(metricsUrl.host == "127.0.0.1" && metricsUrl.port > 1024 && metricsUrl.encodedPath == "/snapshot")
        val output = File(manifest.parentFile, "smb-scale-results.jsonl")
        check(!output.exists()) { "Retain prior measurements; provision a new fixture before another run" }
        val control = OkHttpClient()
        fun counters(): Map<String, Long> = control.newCall(Request.Builder().url(metricsUrl).build()).execute().use {
            check(it.code == 200)
            Json.parseToJsonElement(it.body.string()).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.long }
        }
        try {
            SmbStorageBackend(address.toString(), value("username"), value("password"), "").use { remote ->
                val proof = remote.stat(".fixture-proof")
                check(proof.size == value("proof").length.toLong())
                check(remote.read(proof, 0, proof.size.toInt()).decodeToString() == value("proof"))
                val backend = CountedBackend(remote)
                val records =
                    StorageRecordStore(StorageSession(-108, "scale", "owned") { true }, MemoryStorageRepository(), Json)
                val journal = StorageMutations(backend, records)
                val cases = listOf(
                    "file-1048576" to 1048576L,
                    "file-104857600" to 104857600L,
                    "file-1073741824" to 1073741824L,
                    "directory-100" to 100L,
                    "directory-1000" to 1000L,
                    "directory-10000" to 10000L,
                )
                for ((source, scale) in cases) {
                    // Match entry selection from a browsed directory; tmpfs-backed Samba initializes DOS metadata here.
                    if (source.startsWith("directory-")) remote.list(source)
                    val entry = backend.stat(source)
                    val destination = "$source-moved"
                    assertEquals(source.startsWith("directory-"), entry.directory)
                    if (!entry.directory) assertEquals(scale, entry.size)
                    backend.reset()
                    val before = counters()
                    val started = System.nanoTime()
                    journal.move(entry, destination)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val after = counters()
                    val bytes = backend.contentBytes
                    val copies = backend.contentCopies
                    val moves = backend.moves
                    val stats = backend.stats
                    val lists = backend.lists
                    assertEquals(1, moves)
                    assertTrue(records.list("mutations").isEmpty())
                    val missing = assertThrows(StorageFailure::class.java) { runBlocking { remote.stat(source) } }
                    assertEquals(StorageFailure.Reason.NOT_FOUND, missing.reason)
                    val moved = remote.stat(destination)
                    assertEquals(entry.identity, moved.identity)
                    if (entry.directory) {
                        assertEquals(2 * scale * 64, bytes)
                        assertEquals(scale.toInt(), remote.list(destination).size)
                        for (index in listOf(0L, scale - 1)) {
                            val child = remote.stat("$destination/${index.toString().padStart(5, '0')}.bin")
                            assertArrayEquals(ByteArray(64) { (index % 251).toByte() }, remote.read(child, 0, 64))
                        }
                    } else {
                        assertEquals(0L, bytes)
                        assertEquals(scale, moved.size)
                        val head = "Koharia scale fixture".encodeToByteArray()
                        assertArrayEquals(head, remote.read(moved, 0, head.size))
                        assertArrayEquals(byteArrayOf('!'.code.toByte()), remote.read(moved, scale - 1, 1))
                    }
                    val row = buildJsonObject {
                        put("case", source)
                        put("scale", scale)
                        put("directory", entry.directory)
                        put("content_bytes", bytes)
                        put("content_copies", copies)
                        put("moves", moves)
                        put("stat_calls", stats)
                        put("list_calls", lists)
                        put("elapsed_ms", elapsed)
                        put("retained_pending", 0)
                        before.keys.forEach { key -> put(key, after.getValue(key) - before.getValue(key)) }
                    }
                    output.appendText("$row\n")
                    println("MOVE_SCALE $row")
                }
            }
        } finally {
            control.connectionPool.evictAll()
            control.dispatcher.executorService.shutdown()
        }
    }

    private class CountedBackend(private val remote: LibraryStorageBackend) : LibraryStorageBackend by remote {
        var contentBytes = 0L
        var contentCopies = 0
        var moves = 0
        var stats = 0
        var lists = 0
        fun reset() {
            contentBytes = 0
            contentCopies = 0
            moves = 0
            stats = 0
            lists = 0
        }
        override suspend fun stat(path: String): StorageEntry {
            stats++
            return remote.stat(path)
        }
        override suspend fun list(path: String): List<StorageEntry> {
            lists++
            return remote.list(path)
        }
        override suspend fun move(entry: StorageEntry, destination: String) {
            if (!entry.path.startsWith(".koharia/")) moves++
            try {
                remote.move(entry, destination)
            } catch (error: StorageFailure) {
                println("MOVE_SCALE_FAILURE expected=$entry current=${remote.stat(entry.path)}")
                throw error
            }
        }
        override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray =
            remote.read(entry, offset, length).also { if (!entry.path.startsWith(".koharia/")) contentBytes += it.size }
        override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
            if (!entry.path.startsWith(".koharia/")) contentCopies++
            remote.copyTo(
                entry,
                object : OutputStream() {
                    override fun write(value: Int) {
                        contentBytes++
                        output.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        contentBytes += length
                        output.write(bytes, offset, length)
                    }
                },
            )
        }
    }
}
