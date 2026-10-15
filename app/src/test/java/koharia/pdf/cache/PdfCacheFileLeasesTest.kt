package koharia.pdf.cache

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PdfCacheFileLeasesTest {
    @TempDir lateinit var directory: Path

    @Test fun `all readers must release and repeated close does not release another reader`() {
        val file = File(directory.toFile(), "owned.pdf").apply { writeText("fixture") }
        PdfCacheFileLeases.acquire(file).use { first ->
            PdfCacheFileLeases.acquire(file).use {
                assertFalse(PdfCacheFileLeases.deleteIfUnused(file))
                first.close()
                first.close()
                assertFalse(PdfCacheFileLeases.deleteIfUnused(file))
                assertTrue(file.isFile)
            }
        }
        assertTrue(PdfCacheFileLeases.deleteIfUnused(file))
        assertFalse(file.exists())
    }

    @Test fun `path aliases share a lease while an unrelated cache remains reclaimable`() {
        val file = File(directory.toFile(), "owned.pdf").apply { writeText("fixture") }
        val idle = File(directory.toFile(), "idle.pdf").apply { writeText("idle") }
        PdfCacheFileLeases.acquire(File(directory.toFile(), "./owned.pdf")).use {
            assertFalse(PdfCacheFileLeases.deleteIfUnused(file))
            assertTrue(PdfCacheFileLeases.deleteIfUnused(idle))
        }
        assertTrue(PdfCacheFileLeases.deleteIfUnused(file))
    }
}
