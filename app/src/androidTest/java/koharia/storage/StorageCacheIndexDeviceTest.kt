package koharia.storage

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageCacheIndexDeviceTest {
    @Test fun reservationsDoNotRescanWarmRootAt5124096And16384Blocks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val parent = context.cacheDir.canonicalFile
        val owned = File(parent, "block-index-${System.nanoTime()}").canonicalFile
        check(owned.parentFile == parent && !owned.exists())
        try {
            for (count in listOf(512, 4096, 16384)) {
                val root = File(owned, "$count")
                repeat(count) { number ->
                    val file = File(root, "${number / 128}/$number.block")
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    file.writeBytes(byteArrayOf(1))
                }
                val started = SystemClock.elapsedRealtimeNanos()
                val index = StorageCacheIndex.get(root)
                index.access { assertEquals(count.toLong(), measuredBytes()) }
                val bootstrap = SystemClock.elapsedRealtimeNanos() - started
                val baselineStart = SystemClock.elapsedRealtimeNanos()
                repeat(8) {
                    val files = root.walkTopDown().filter {
                        it.isFile && (it.extension == "block" || it.name == "complete.bin")
                    }.toList()
                    assertEquals(count.toLong(), files.sumOf(File::length))
                    assertEquals(count, files.sortedBy(File::lastModified).size)
                }
                val baseline = SystemClock.elapsedRealtimeNanos() - baselineStart
                val warmStart = SystemClock.elapsedRealtimeNanos()
                repeat(8) { number ->
                    val target = File(root, "reserved-$number.block")
                    index.access {
                        reserve(target, 1, count + 1L)
                        release(target)
                    }
                }
                val warm = SystemClock.elapsedRealtimeNanos() - warmStart
                repeat(128) { number ->
                    val target = File(root, "new-$number.block")
                    index.access {
                        reserve(target, 1, count.toLong())
                        target.writeBytes(byteArrayOf(2))
                        release(target)
                    }
                }
                assertEquals(1, index.scans)
                assertEquals(count.toLong(), root.walkTopDown().filter { it.extension == "block" }.sumOf(File::length))
                index.access {
                    assertEquals(count.toLong(), measuredBytes())
                    assertEquals(0L, reservedBytes())
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putInt("block_count", count)
                        putLong("bootstrap_ns", bootstrap)
                        putLong("prior_algorithm_8_reserves_ns", baseline)
                        putLong("indexed_8_reserves_ns", warm)
                        putInt("root_scans_after_128_writes", index.scans)
                    },
                )
            }
        } finally {
            check(owned.parentFile == parent)
            assertTrue(!owned.exists() || owned.deleteRecursively())
        }
    }
}
