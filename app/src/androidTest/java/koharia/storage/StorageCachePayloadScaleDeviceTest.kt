package koharia.storage

import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Explicitly enabled actual payload measurement; never uses existing library/cache roots. */
@RunWith(AndroidJUnit4::class)
class StorageCachePayloadScaleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val blockSize = 256 * 1024
    private val trace = InstrumentationRegistry.getArguments().getString("cachePayloadTrace") == "true"

    @Test
    fun actualPayloadAndSameRootLockMeasurements(): Unit = runBlocking {
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        assumeTrue(InstrumentationRegistry.getArguments().getString("cachePayloadScale") == "true")
        check(!trace || Trace.isEnabled()) { "Requested cache tracing requires an active recorder" }
        val configuredMiB = InstrumentationRegistry.getArguments().getString("cachePayloadMiB")?.toLong()
        check(configuredMiB == null || configuredMiB in listOf(128L, 1024L, 4096L))
        val sizesMiB = configuredMiB?.let(::listOf) ?: listOf(128L, 1024L, 4096L)
        val parent = instrumentation.targetContext.cacheDir.canonicalFile
        check(parent.usableSpace > 8L * 1024 * 1024 * 1024)
        val owned = File(parent, "cache-payload-${System.nanoTime()}").canonicalFile
        check(owned.parentFile == parent && !owned.exists() && owned.mkdir())
        emit("owned", JSONObject().put("root", owned.name).put("pid", Process.myPid()))
        try {
            for (size in sizesMiB.map { it * 1024 * 1024 }) {
                check(parent.usableSpace > size + 4L * 1024 * 1024 * 1024)
                measure(File(owned, "$size"), size)
            }
        } finally {
            check(owned.parentFile == parent)
            assertTrue(owned.deleteRecursively())
            emit("cleanup", JSONObject().put("root", owned.name).put("removed", !owned.exists()))
        }
    }

    private suspend fun measure(root: File, size: Long) {
        val index = StorageCacheIndex.get(root)
        index.access { assertEquals(0L, measuredBytes()) }
        val calls = AtomicLong()
        val fetched = AtomicLong()
        val backend = object : LibraryStorageBackend by NoStorageIo {
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
                if (trace) Trace.beginSection("CachePayloadBackend:${offset / blockSize}")
                try {
                    calls.incrementAndGet()
                    fetched.addAndGet(length.toLong())
                    return payload(offset, length)
                } finally {
                    if (trace) Trace.endSection()
                }
            }
        }
        val session = StorageSession(-126, "payload-scale", "owned") { true }
        val cache = StorageBlockCache(root, session, backend) { size }
        val entry = StorageEntry("generated.bin", false, size, 1, "fixture-v1")
        val running = AtomicBoolean(true)
        val waits = mutableListOf<Long>()
        val observerFailure = AtomicReference<Throwable?>()
        val before = metrics()
        val observer = thread(name = "cache-scale-lock-observer", isDaemon = true) {
            try {
                while (running.get()) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    if (trace) Trace.beginSection("CacheIndexAccess")
                    try {
                        index.access {
                            waits += SystemClock.elapsedRealtimeNanos() - start
                            if (trace) {
                                Trace.beginSection("CacheIndexAcquired")
                                Trace.endSection()
                            }
                        }
                    } finally {
                        if (trace) Trace.endSection()
                    }
                    Thread.sleep(5)
                }
            } catch (error: Throwable) {
                observerFailure.set(error)
            }
        }
        val started = SystemClock.elapsedRealtimeNanos()
        var segmentStart = started
        try {
            val blocks = (size / blockSize).toInt()
            repeat(blocks) { number ->
                val offset = number.toLong() * blockSize
                if (trace) Trace.beginAsyncSection("CachePayloadRead", number)
                try {
                    assertArrayEquals(payload(offset, blockSize), cache.read(entry, offset, blockSize))
                } finally {
                    if (trace) Trace.endAsyncSection("CachePayloadRead", number)
                }
                if ((number + 1) % 512 == 0) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    assertEquals(1, index.scans)
                    emit(
                        "progress",
                        metrics()
                            .put("payload_bytes", size)
                            .put("blocks_written", number + 1)
                            .put("segment_ms", (now - segmentStart) / 1_000_000)
                            .put("scans", index.scans),
                    )
                    segmentStart = now
                }
            }
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            running.set(false)
            observer.join(10_000)
            check(!observer.isAlive)
            observerFailure.get()?.let { throw it }
            assertTrue(waits.isNotEmpty())
            assertEquals(blocks.toLong(), calls.get())
            assertEquals(size, fetched.get())
            index.access {
                assertEquals(size, measuredBytes())
                assertEquals(0L, reservedBytes())
            }
            val files = root.walkTopDown().filter { it.isFile }.toList()
            assertEquals(blocks, files.count { it.extension == "block" })
            assertEquals(blocks, files.count { it.extension == "hash" })
            assertEquals(size, files.filter { it.extension == "block" }.sumOf(File::length))
            assertTrue(files.none { it.extension == "part" })
            // A new cache object must read the persisted bytes without fetching the backend again.
            val reopened = StorageBlockCache(root, session, backend) { size }
            for (number in listOf(0, blocks / 2, blocks - 1)) {
                val offset = number.toLong() * blockSize
                assertArrayEquals(payload(offset, blockSize), reopened.read(entry, offset, blockSize))
            }
            assertEquals(blocks.toLong(), calls.get())
            assertEquals(1, index.scans)
            val sorted = waits.sorted()
            fun percentile(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()] / 1000
            val row = JSONObject()
                .put("payload_bytes", size)
                .put("block_bytes", blockSize)
                .put("blocks", blocks)
                .put("source_reads", calls.get())
                .put("directory_scans", index.scans)
                .put("elapsed_ms", elapsed)
                .put("lock_samples", sorted.size)
                .put("lock_access_p50_us", percentile(0.5))
                .put("lock_access_p95_us", percentile(0.95))
                .put("lock_access_p99_us", percentile(0.99))
                .put("lock_access_max_us", sorted.last() / 1000)
                .put("before", before)
                .put("after_write", metrics())
            index.access {
                assertEquals(blocks, clearUnused())
                assertEquals(0L, measuredBytes())
                assertEquals(0L, reservedBytes())
            }
            assertTrue(root.walkTopDown().none { it.isFile })
            emit("result", row.put("after_clear", metrics()))
        } finally {
            running.set(false)
            observer.join(10_000)
            check(!observer.isAlive)
        }
    }

    private fun payload(offset: Long, length: Int) = ByteArray(length) { (offset / blockSize % 251).toByte() }

    private fun metrics(): JSONObject {
        val runtime = Runtime.getRuntime()
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return JSONObject()
            .put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("process_cpu_ms", Process.getElapsedCpuTime())
            .put("fd_count", checkNotNull(File("/proc/self/fd").list()).size)
            .put("threads", checkNotNull(File("/proc/self/task").list()).size)
            .put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
            .put("pss_kib", memory.totalPss)
            .put("gc_count", Debug.getRuntimeStat("art.gc.gc-count"))
            .put("gc_time_ms", Debug.getRuntimeStat("art.gc.gc-time"))
            .put("allocated_bytes", Debug.getRuntimeStat("art.gc.bytes-allocated"))
    }

    private fun emit(kind: String, data: JSONObject) {
        instrumentation.sendStatus(2, Bundle().apply { putString("cache_payload_$kind", data.toString()) })
    }

    private object NoStorageIo : LibraryStorageBackend {
        override val capabilities = StorageCapabilities(false, true, false, false)
        override suspend fun stat(path: String): StorageEntry = error("Unexpected fixture IO")
        override suspend fun list(path: String): List<StorageEntry> = error("Unexpected fixture IO")
        override suspend fun read(
            entry: StorageEntry,
            offset: Long,
            length: Int,
        ): ByteArray = error("Unexpected fixture IO")
        override suspend fun createDirectory(path: String): Unit = error("Unexpected fixture IO")
        override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?): Unit =
            error("Unexpected fixture IO")
        override suspend fun move(entry: StorageEntry, destination: String): Unit = error("Unexpected fixture IO")
        override suspend fun delete(entry: StorageEntry): Unit = error("Unexpected fixture IO")
        override fun close() = Unit
    }
}
