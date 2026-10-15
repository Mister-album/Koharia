package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Opt-in actual payload measurements, confined to a fresh task-owned cache root. */
class StorageCachePayloadScaleTest {
    @Test
    fun actualPayloadAndSameRootLockMeasurements(): Unit = runBlocking {
        val configured = System.getenv("KOHARIA_CACHE_SCALE_OUTPUT")
        assumeTrue(!configured.isNullOrBlank())
        val task = File(checkNotNull(configured)).canonicalFile
        check(task.isDirectory && "/.test-artifacts/" in task.invariantSeparatorsPath)
        check(task.usableSpace > 8L * 1024 * 1024 * 1024)
        val output = File(task, "cache-payload-results.jsonl")
        val progress = File(task, "cache-payload-progress.jsonl")
        check(!output.exists() && !progress.exists()) { "Preserve previous scale evidence; use a new output directory" }
        val blockSize = 256 * 1024
        val heap = ManagementFactory.getMemoryMXBean()
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        for (size in listOf(128L * 1024 * 1024, 1024L * 1024 * 1024, 4L * 1024 * 1024 * 1024)) {
            val root = Files.createTempDirectory(task.toPath(), "cache-payload-$size-").toFile().canonicalFile
            check(root.parentFile == task)
            val index = StorageCacheIndex.get(root)
            index.access { assertEquals(0L, measuredBytes()) }
            val calls = AtomicLong()
            val fetched = AtomicLong()
            fun payload(offset: Long, length: Int) = ByteArray(length) { (offset / blockSize % 251).toByte() }
            val backend = object : LibraryStorageBackend by MemoryStorageBackend() {
                override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
                    calls.incrementAndGet()
                    fetched.addAndGet(length.toLong())
                    return payload(offset, length)
                }
            }
            val session = StorageSession(-110, "payload-scale", "owned") { true }
            val cache = StorageBlockCache(root, session, backend) { size }
            val entry = StorageEntry("generated.bin", false, size, 1, "fixture-v1")
            val running = AtomicBoolean(true)
            val waits = mutableListOf<Long>()
            val observerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val observer = thread(name = "cache-scale-lock-observer", isDaemon = true) {
                try {
                    while (running.get()) {
                        val start = System.nanoTime()
                        index.access { waits += System.nanoTime() - start }
                        Thread.sleep(5)
                    }
                } catch (error: Throwable) {
                    observerFailure.set(error)
                }
            }
            val gcBefore = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
            val heapBefore = heap.heapMemoryUsage.used
            var peakHeap = heapBefore
            val started = System.nanoTime()
            var segmentStart = started
            try {
                val blocks = (size / blockSize).toInt()
                repeat(blocks) { number ->
                    val offset = number.toLong() * blockSize
                    assertArrayEquals(payload(offset, blockSize), cache.read(entry, offset, blockSize))
                    if ((number + 1) % 512 == 0) {
                        val now = System.nanoTime()
                        peakHeap = maxOf(peakHeap, heap.heapMemoryUsage.used)
                        assertEquals(1, index.scans)
                        progress.appendText(
                            buildJsonObject {
                                put("size", size)
                                put("blocks_written", number + 1)
                                put("segment_ms", (now - segmentStart) / 1_000_000)
                                put("scans", index.scans)
                            }.toString() + "\n",
                        )
                        segmentStart = now
                    }
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000
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
                for (number in listOf(0, blocks / 2, blocks - 1)) {
                    val offset = number.toLong() * blockSize
                    assertArrayEquals(payload(offset, blockSize), cache.read(entry, offset, blockSize))
                }
                assertEquals(blocks.toLong(), calls.get(), "sample rereads must use the persisted cache")
                assertEquals(1, index.scans)
                val sorted = waits.sorted()
                fun percentile(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()] / 1000
                val row = buildJsonObject {
                    put("payload_bytes", size)
                    put("block_bytes", blockSize)
                    put("blocks", blocks)
                    put("source_reads", calls.get())
                    put("directory_scans", index.scans)
                    put("elapsed_ms", elapsed)
                    put("lock_samples", sorted.size)
                    put("lock_wait_p50_us", percentile(0.5))
                    put("lock_wait_p95_us", percentile(0.95))
                    put("lock_wait_p99_us", percentile(0.99))
                    put("lock_wait_max_us", sorted.last() / 1000)
                    put("jvm_heap_before", heapBefore)
                    put("jvm_heap_peak_sampled", peakHeap)
                    put("jvm_gc_collections", collectors.sumOf { it.collectionCount.coerceAtLeast(0) } - gcBefore)
                    put("cache_root", root.name)
                }
                // Clear only the fresh cache owned by this case, through the production index.
                index.access {
                    assertEquals(blocks, clearUnused())
                    assertEquals(0L, measuredBytes())
                    assertEquals(0L, reservedBytes())
                }
                assertTrue(root.walkTopDown().none { it.isFile })
                output.appendText("$row\n")
                println("CACHE_PAYLOAD_SCALE $row")
            } finally {
                running.set(false)
                observer.join(10_000)
            }
        }
    }
}
