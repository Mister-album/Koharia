package eu.kanade.tachiyomi.data.backup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BackupPayloadLimitsTest {
    @Test fun `exact boundary and empty streams remain readable`() {
        for (size in listOf(0, 1, 8192, 8193)) {
            val bytes = ByteArray(size) { it.toByte() }
            assertArrayEquals(bytes, BackupPayloadLimits.read(ByteArrayInputStream(bytes), size))
        }
    }

    @Test fun `untrusted available and short reads cannot bypass budget or consume the rest`() {
        val input = object : InputStream() {
            var consumed = 0
            override fun available() = Int.MAX_VALUE
            override fun read(): Int {
                consumed++
                return 7
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length, 3)
                bytes.fill(7, offset, offset + count)
                consumed += count
                return count
            }
        }
        assertThrows(BackupSizeLimitExceeded::class.java) { BackupPayloadLimits.read(input, 100) }
        assertEquals(101, input.consumed)
    }

    @Test fun `zero length reads cannot spin indefinitely`() {
        val input = object : InputStream() {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
            override fun read() = 1
        }
        assertThrows(BackupSizeLimitExceeded::class.java) { BackupPayloadLimits.read(input, 10) }
    }

    @Test fun `gzip expansion is limited independently of compressed size`() {
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { gzip -> repeat(128) { gzip.write(ByteArray(8192)) } }
        }.toByteArray()
        assertThrows(BackupSizeLimitExceeded::class.java) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { BackupPayloadLimits.read(it, 4096) }
        }
    }
}
