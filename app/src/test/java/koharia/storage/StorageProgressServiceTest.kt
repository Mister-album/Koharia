package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** Opt-in progress measurements against new owned loopback fixtures with synthetic records. */
class StorageProgressServiceTest {
    @Test
    fun webDavProgressBatchScaleMatrix(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_WEBDAV_PROGRESS_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(name: String) = config.getValue(name).jsonPrimitive.content
        val address = value("address").toHttpUrl()
        check(address.scheme == "http" && address.host == "127.0.0.1" && address.port > 1024)
        val metrics = value("metrics").toHttpUrl()
        check(metrics.host == "127.0.0.1" && metrics.port == address.port && metrics.encodedPath == "/__metrics")
        val output = File(manifest.parentFile, "webdav-progress-results.jsonl")
        check(!output.exists()) { "Retain existing evidence; provision a new fixture before another run" }
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
            for (count in listOf(1, 100, 1000)) {
                val root = address.newBuilder().addPathSegment("scale-$count").addPathSegment("").build()
                WebDavStorageBackend(client, root.toString(), "", "").use { backend ->
                    val row = progressBatch(backend, count, value("proof"), ::counters)
                    assertEquals(count.toLong(), row.getValue("PUT").jsonPrimitive.long)
                    assertEquals(count.toLong(), row.getValue("GET").jsonPrimitive.long)
                    assertEquals(count.toLong(), row.getValue("DELETE").jsonPrimitive.long)
                    assertEquals(1L, row.getValue("PROPFIND_DEPTH_1").jsonPrimitive.long)
                    assertTrue(row.getValue("requests").jsonPrimitive.long <= 6L * count + 10)
                    output.appendText("$row\n")
                    println("PROGRESS_SERVICE $row")
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun authenticatedSmbProgressBatchScaleMatrix(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_SMB_PROGRESS_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(name: String) = config.getValue(name).jsonPrimitive.content
        val address = URI(value("address"))
        check(address.scheme == "smb" && address.host == "127.0.0.1" && address.port > 1024 && address.path == "/scale")
        val metrics = value("metrics").toHttpUrl()
        check(metrics.host == "127.0.0.1" && metrics.port > 1024 && metrics.encodedPath == "/snapshot")
        val output = File(manifest.parentFile, "smb-progress-results.jsonl")
        check(!output.exists()) { "Retain existing evidence; provision a new fixture before another run" }
        val control = OkHttpClient()
        fun counters(): Map<String, Long> = control.newCall(Request.Builder().url(metrics).build()).execute().use {
            check(it.code == 200)
            Json.parseToJsonElement(it.body.string()).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.long }
        }
        try {
            for (count in listOf(1, 100, 1000)) {
                SmbStorageBackend("$address/scale-$count", value("username"), value("password"), "").use { remote ->
                    val backend = CountedBackend(remote)
                    val row = progressBatch(backend, count, value("proof")) { counters() + backend.counts }
                    assertEquals(1L, row.getValue("list_calls").jsonPrimitive.long)
                    assertEquals(count + 1L, row.getValue("listed_entries").jsonPrimitive.long)
                    for (operation in listOf("read_calls", "write_calls", "delete_calls")) {
                        assertEquals(count.toLong(), row.getValue(operation).jsonPrimitive.long)
                    }
                    assertTrue(row.getValue("sent_bytes").jsonPrimitive.long > 0)
                    assertTrue(row.getValue("received_bytes").jsonPrimitive.long > 0)
                    output.appendText("$row\n")
                    println("SMB_PROGRESS_SERVICE $row")
                }
            }
        } finally {
            control.connectionPool.evictAll()
            control.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun progressBatch(
        backend: LibraryStorageBackend,
        count: Int,
        expectedProof: String,
        counters: () -> Map<String, Long>,
    ): JsonObject {
        val proof = backend.stat(".fixture-proof")
        check(proof.size == expectedProof.length.toLong())
        check(backend.read(proof, 0, proof.size.toInt()).decodeToString() == expectedProof)
        assertTrue(backend.list(".koharia/progress").isEmpty())
        val records = StorageRecordStore(
            StorageSession(-115, "progress-scale", "owned-$count") { true },
            MemoryStorageRepository(),
            Json,
        )
        val progress = StorageProgress(backend, records, "installation")
        repeat(count) { progress.record("book-$it", "v1", 100, 1, 10) }
        progress.flush()
        assertEquals(count, backend.list(".koharia/progress").size)
        val expected = (0 until count).map { progress.record("book-$it", "v1", 200, 2, 10) }
        val before = counters()
        val started = System.nanoTime()
        progress.flush()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val after = counters()
        val delta = after.mapValues { (key, current) -> current - before.getValue(key) }
        val actual = backend.list(".koharia/progress").map {
            assertTrue(!it.directory && it.size in 1..65536)
            Json.decodeFromString<StorageProgressRecord>(backend.read(it, 0, it.size.toInt()).decodeToString())
        }
        assertEquals(count, actual.size)
        assertEquals(expected.toSet(), actual.toSet())
        expected.forEach {
            assertEquals(
                StoragePendingProgress(it, false),
                records.get<StoragePendingProgress>("progress", it.resource),
            )
        }
        return buildJsonObject {
            put("resources", count)
            put("elapsed_ms", elapsed)
            put("remote_records_verified", actual.size)
            put("pending", 0)
            delta.forEach { (name, value) -> put(name, value) }
        }
    }

    private class CountedBackend(private val backend: LibraryStorageBackend) : LibraryStorageBackend by backend {
        private val totals = mutableMapOf(
            "list_calls" to 0L,
            "listed_entries" to 0L,
            "read_calls" to 0L,
            "write_calls" to 0L,
            "delete_calls" to 0L,
        )
        val counts get() = totals.toMap()
        override suspend fun list(path: String) = backend.list(path).also {
            totals["list_calls"] = totals.getValue("list_calls") + 1
            totals["listed_entries"] = totals.getValue("listed_entries") + it.size
        }
        override suspend fun read(
            entry: StorageEntry,
            offset: Long,
            length: Int,
        ) = backend.read(entry, offset, length).also {
            totals["read_calls"] = totals.getValue("read_calls") + 1
        }
        override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
            backend.write(path, data, length, expectedVersion)
            totals["write_calls"] = totals.getValue("write_calls") + 1
        }
        override suspend fun delete(entry: StorageEntry) {
            backend.delete(entry)
            totals["delete_calls"] = totals.getValue("delete_calls") + 1
        }
    }
}
