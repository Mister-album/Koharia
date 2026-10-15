package koharia.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class StorageCacheClearTest {
    @TempDir lateinit var temporary: Path

    @Test fun `explicit clear preserves leased ranges and complete files until all readers release`() {
        val root = temporary.toFile()
        val active = File(root, "active").apply { mkdirs() }
        val range = File(active, "0.block").apply { writeText("range") }
        val checksum = File(active, "0.hash").apply { writeText("checksum") }
        val complete = File(active, "complete.bin").apply { writeText("complete") }
        File(active, "complete.ok").writeText("checked")
        val idle = File(root, "idle/complete.bin").apply {
            parentFile!!.mkdirs()
            writeText("idle")
        }
        val marker = File(idle.parentFile, "complete.ok").apply { writeText("checked") }
        val manual = File(root, "manual.cbz").apply { writeText("unmanaged file") }
        val index = StorageCacheIndex.get(root)
        val first = index.acquire(active)
        val second = index.acquire(active)
        try {
            index.access {
                assertEquals(1, clearUnused())
                assertFalse(idle.exists())
                assertFalse(marker.exists())
                assertEquals(range.length() + complete.length(), measuredBytes())
            }
            first.close()
            first.close()
            index.access { assertEquals(0, clearUnused()) }
            assertTrue(range.isFile && checksum.isFile && complete.isFile)
            second.close()
            index.access {
                assertEquals(2, clearUnused())
                assertEquals(0, clearUnused())
                assertEquals(0L, measuredBytes())
            }
            assertFalse(range.exists() || checksum.exists() || complete.exists())
            assertEquals("unmanaged file", manual.readText())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun `clear retains reserved promotion and reclaims missing accounting`() {
        val root = temporary.toFile()
        val index = StorageCacheIndex.get(root)
        val target = File(root, "transfer/complete.bin").apply { parentFile!!.mkdirs() }
        val removedExternally = File(root, "old/0.block").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        index.access {
            reserve(target, 4, 1024)
            target.writeText("data")
            record(target)
            assertTrue(removedExternally.delete())
            assertEquals(0, clearUnused())
            assertEquals("data", target.readText())
            assertEquals(4L, reservedBytes())
            assertEquals(4L, measuredBytes())
            release(target)
            assertEquals(1, clearUnused())
            assertEquals(0L, reservedBytes())
            assertEquals(0L, measuredBytes())
        }
    }
}
