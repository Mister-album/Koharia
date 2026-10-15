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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Ordinary response-loss recovery against owned loopback storage and synthetic progress only. */
class StorageProgressRecoveryServiceTest {
    @Test
    fun authenticatedSmbBatchResumesAfterFlushAcknowledgementIsLost(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_SMB_PROGRESS_RECOVERY_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(name: String) = config.getValue(name).jsonPrimitive.content
        val address = URI(value("address"))
        check(address.scheme == "smb" && address.host == "127.0.0.1" && address.port > 1024 && address.path == "/scale")
        val metrics = value("metrics").toHttpUrl()
        check(metrics.host == "127.0.0.1" && metrics.port > 1024 && metrics.encodedPath == "/snapshot")
        val output = File(manifest.parentFile, "smb-recovery-result.json")
        check(!output.exists()) { "Retain prior evidence; provision a fresh owned fixture" }
        val control = OkHttpClient()
        fun counters(): Map<String, Long> = control.newCall(Request.Builder().url(metrics).build()).execute().use {
            check(it.code == 200)
            Json.parseToJsonElement(it.body.string()).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.long }
        }
        fun backend() = SmbStorageBackend("$address/scale-3", value("username"), value("password"), "")
        try {
            val store = StorageRecordStore(
                StorageSession(-118, "progress-scale", "owned-recovery") { true },
                MemoryStorageRepository(),
                Json,
            )
            val expected: List<StorageProgressRecord>
            val initial: Map<String, Long>
            val interrupted: Map<String, Long>
            backend().use { remote ->
                val proof = remote.stat(".fixture-proof")
                check(proof.size == value("proof").length.toLong())
                check(remote.read(proof, 0, proof.size.toInt()).decodeToString() == value("proof"))
                assertTrue(remote.list(".koharia/progress").isEmpty())
                val progress = StorageProgress(remote, store, "installation")
                expected = (0 until 3).map { progress.record("book-$it", "v1", 200, 2, 10) }
                initial = counters()
                val failure = runCatching { progress.flush() }.exceptionOrNull()
                assertEquals(StorageFailure.Reason.NETWORK, (failure as? StorageFailure)?.reason)
                interrupted = counters().mapValues { (key, current) -> current - initial.getValue(key) }
                assertEquals(1L, interrupted.getValue("dropped_flush_response"))
                assertEquals(2L, interrupted.getValue("successful_write_responses"))
                assertEquals(2L, interrupted.getValue("successful_flush_responses"))
                assertEquals(0L, interrupted.getValue("create_conflicts"))
                expected.forEachIndexed { index, record ->
                    assertEquals(
                        StoragePendingProgress(record, index != 0),
                        store.get<StoragePendingProgress>("progress", record.resource),
                    )
                }
            }
            backend().use { remote ->
                StorageProgress(remote, store, "installation").flush()
                val recovered = counters().mapValues { (key, current) -> current - initial.getValue(key) }
                assertEquals(1L, recovered.getValue("dropped_flush_response"))
                assertEquals(3L, recovered.getValue("successful_write_responses"))
                assertEquals(3L, recovered.getValue("successful_flush_responses"))
                assertEquals(1L, recovered.getValue("create_conflicts"))
                expected.forEach { record ->
                    assertEquals(
                        StoragePendingProgress(record, false),
                        store.get<StoragePendingProgress>("progress", record.resource),
                    )
                }
                val entries = remote.list(".koharia/progress")
                assertEquals(3, entries.size)
                val actual = entries.map {
                    assertTrue(!it.directory && it.size in 1..65536)
                    Json.decodeFromString<StorageProgressRecord>(remote.read(it, 0, it.size.toInt()).decodeToString())
                }
                assertEquals(expected.toSet(), actual.toSet())
                val result = buildJsonObject {
                    put("verified_records", actual.size)
                    put("pending", 0)
                    put("interrupted", buildJsonObject { interrupted.forEach { (key, value) -> put(key, value) } })
                    put("recovered", buildJsonObject { recovered.forEach { (key, value) -> put(key, value) } })
                }
                output.writeText("$result\n")
                println("SMB_PROGRESS_RECOVERY $result")
            }
        } finally {
            control.connectionPool.evictAll()
            control.dispatcher.executorService.shutdown()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["upload", "prune"])
    fun webDavBatchResumesAfterACommittedMutationLosesItsResponse(fault: String): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_WEBDAV_PROGRESS_RECOVERY_MANIFEST")
        assumeTrue(!configured.isNullOrBlank())
        val manifest = File(checkNotNull(configured)).canonicalFile
        check("/.test-artifacts/" in manifest.invariantSeparatorsPath)
        val config = Json.parseToJsonElement(manifest.readText()).jsonObject
        fun value(name: String) = config.getValue(name).jsonPrimitive.content
        val address = value("address").toHttpUrl()
        check(address.scheme == "http" && address.host == "127.0.0.1" && address.port > 1024)
        val metrics = value("metrics").toHttpUrl()
        check(metrics.host == "127.0.0.1" && metrics.port == address.port && metrics.encodedPath == "/__metrics")
        val output = File(manifest.parentFile, "webdav-$fault-recovery.json")
        check(!output.exists()) { "Retain prior evidence; provision a fresh owned fixture" }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .build()
        fun counters(): Map<String, Long> = client.newCall(Request.Builder().url(metrics).build()).execute().use {
            check(it.code == 200)
            Json.parseToJsonElement(it.body.string()).jsonObject.getValue(fault).jsonObject.mapValues { entry ->
                entry.value.jsonPrimitive.long
            }
        }
        val root = address.newBuilder().addPathSegment(fault).addPathSegment("").build().toString()
        try {
            val store = StorageRecordStore(
                StorageSession(-117, "recovery-fixture", fault) { true },
                MemoryStorageRepository(),
                Json,
            )
            val expected: List<StorageProgressRecord>
            val initial: Map<String, Long>
            val interrupted: Map<String, Long>
            WebDavStorageBackend(client, root, "", "").use { backend ->
                val proof = backend.stat(".fixture-proof")
                check(proof.size == value("proof").length.toLong())
                check(backend.read(proof, 0, proof.size.toInt()).decodeToString() == value("proof"))
                assertTrue(backend.list(".koharia/progress").isEmpty())
                val progress = StorageProgress(backend, store, "installation")
                if (fault == "prune") {
                    repeat(3) { progress.record("book-$it", "v1", 100, 1, 10) }
                    progress.flush()
                }
                expected = (0 until 3).map { progress.record("book-$it", "v1", 200, 2, 10) }
                initial = counters()
                val failure = runCatching { progress.flush() }.exceptionOrNull()
                assertTrue(failure is IOException, "The committed response loss must reach the caller")
                interrupted = counters().mapValues { (key, current) -> current - initial.getValue(key) }
                assertEquals(1L, interrupted.getValue("dropped"))
                assertEquals(if (fault == "upload") 2L else 1L, interrupted.getValue("put_attempts"))
                assertEquals(if (fault == "upload") 2L else 1L, interrupted.getValue("put_committed"))
                assertEquals(if (fault == "prune") 1L else 0L, interrupted.getValue("delete_committed"))
                expected.forEachIndexed { index, record ->
                    assertEquals(
                        StoragePendingProgress(record, index != 0),
                        store.get<StoragePendingProgress>("progress", record.resource),
                    )
                }
            }

            // Recreate the protocol/coordinator objects while retaining the same local repository.
            WebDavStorageBackend(client, root, "", "").use { backend ->
                StorageProgress(backend, store, "installation").flush()
                val recovered = counters().mapValues { (key, current) -> current - initial.getValue(key) }
                assertEquals(1L, recovered.getValue("dropped"))
                assertEquals(3L, recovered.getValue("put_committed"))
                assertEquals(if (fault == "upload") 4L else 3L, recovered.getValue("put_attempts"))
                assertEquals(if (fault == "upload") 1L else 0L, recovered.getValue("put_conflicts"))
                assertEquals(if (fault == "prune") 3L else 0L, recovered.getValue("delete_committed"))
                expected.forEach { record ->
                    assertEquals(
                        StoragePendingProgress(record, false),
                        store.get<StoragePendingProgress>("progress", record.resource),
                    )
                }
                val entries = backend.list(".koharia/progress")
                assertEquals(3, entries.size)
                val actual = entries.map {
                    assertTrue(!it.directory && it.size in 1..65536)
                    Json.decodeFromString<StorageProgressRecord>(backend.read(it, 0, it.size.toInt()).decodeToString())
                }
                assertEquals(expected.toSet(), actual.toSet())
                val row = buildJsonObject {
                    put("fault", fault)
                    put("verified_records", actual.size)
                    put("pending", 0)
                    put("interrupted", buildJsonObject { interrupted.forEach { (key, value) -> put(key, value) } })
                    put("recovered", buildJsonObject { recovered.forEach { (key, value) -> put(key, value) } })
                }
                output.writeText("$row\n")
                println("PROGRESS_RECOVERY $row")
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
