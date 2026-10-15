package koharia.storage

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.UUID

class WebDavRangeServiceDeviceTest {
    @Test
    fun actualServiceRangesAndFallbackNeverPublishFailedContent(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val address = checkNotNull(InstrumentationRegistry.getArguments().getString("rangeServiceAddress"))
        check(URI(address).host == "127.0.0.1" && URI(address).path == "/")
        val parent = context.cacheDir.canonicalFile
        val owned = File(parent, "dav-range-${UUID.randomUUID()}").canonicalFile
        check(owned.parentFile == parent && !owned.exists() && owned.mkdirs())
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val backend = WebDavStorageBackend(client, address, "", "")
        val failures = mapOf(
            "bad-range" to StorageFailure.Reason.PROTOCOL,
            "bad-total" to StorageFailure.Reason.PROTOCOL,
            "short-range" to StorageFailure.Reason.PROTOCOL,
            "long-range" to StorageFailure.Reason.PROTOCOL,
            "unsatisfied" to StorageFailure.Reason.NETWORK,
            "strong-changed" to StorageFailure.Reason.CONFLICT,
            "weak-changed" to StorageFailure.Reason.CONFLICT,
            "missing-length-changed" to StorageFailure.Reason.CONFLICT,
            "full-short" to StorageFailure.Reason.PROTOCOL,
            "full-long" to StorageFailure.Reason.PROTOCOL,
            "full-changed" to StorageFailure.Reason.CONFLICT,
        )
        try {
            for (mode in listOf("normal-range", "no-range", "whole-200") + failures.keys) {
                val root = File(owned, mode)
                val entry = backend.stat("$mode/book.bin")
                val session = StorageSession(Long.MIN_VALUE + 255, "owned", mode) { true }
                val cache = StorageBlockCache(root, session, backend) { 2L * 1024 * 1024 }
                val expectedFailure = failures[mode]
                val result = runCatching { cache.read(entry, 17, 23) }
                if (expectedFailure == null) {
                    assertArrayEquals(ByteArray(23) { ((it + 17) % 251).toByte() }, result.getOrThrow())
                    val files = root.walkTopDown().filter { it.isFile }.toList()
                    if (mode == "no-range") {
                        val complete = files.single { it.name == "complete.bin" }
                        assertArrayEquals(ByteArray(entry.size.toInt()) { (it % 251).toByte() }, complete.readBytes())
                        assertTrue(files.any { it.name == "complete.ok" })
                    } else {
                        assertTrue(files.any { it.extension == "block" })
                    }
                    assertArrayEquals(ByteArray(23) { ((it + 17) % 251).toByte() }, cache.read(entry, 17, 23))
                } else {
                    val error = result.exceptionOrNull()
                    assertTrue("$mode must fail as StorageFailure, got $error", error is StorageFailure)
                    assertEquals(mode, expectedFailure, (error as StorageFailure).reason)
                    val published = root.walkTopDown().filter {
                        it.isFile && (it.extension in listOf("block", "hash") || it.name.startsWith("complete."))
                    }.toList()
                    assertTrue(
                        "$mode must not publish failed content: ${published.map {
                            it.name
                        }}",
                        published.isEmpty(),
                    )
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply { putString("dav_range_case", "$mode:${expectedFailure ?: "PASSED"}") },
                )
            }
        } finally {
            backend.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            check(owned.canonicalFile.parentFile == parent)
            check(owned.deleteRecursively())
        }
    }
}
