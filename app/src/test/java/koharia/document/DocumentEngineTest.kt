package koharia.document

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DocumentEngineTest {

    @Test
    fun `compressed text can be longer than its Palm database`() {
        val record = byteArrayOf('a'.code.toByte()) + ByteArray(80) { if (it % 2 == 0) 0x80.toByte() else 0x0f }
        assertEquals("a".repeat(401), MobiParser.decodeText(palmDatabase(record, 401)))
    }

    @Test
    fun `declared text length excludes trailing record padding`() {
        assertEquals("hello", MobiParser.decodeText(palmDatabase("hello padding".encodeToByteArray(), 5, 1)))
    }

    @Test
    fun `unsigned oversized text length is rejected before allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            MobiParser.decodeText(palmDatabase(byteArrayOf(65), -1))
        }
    }

    @Test
    fun `record trailers and multibyte overlaps are excluded before decompression`() {
        val record = "hello".encodeToByteArray() + byteArrayOf(65, 66, 2, 9, 0x82.toByte())
        assertEquals(5, mobiTextRecordLength(record, 3))
        assertEquals(record.size, mobiTextRecordLength(record, 0))
        assertEquals("hello", MobiParser.decodeText(palmDatabase(record, 5, 1, extraFlags = 3)))
    }

    @Test
    fun `record trailer cannot underflow the text record`() {
        assertThrows(IllegalArgumentException::class.java) {
            mobiTextRecordLength(byteArrayOf(65, 0x8f.toByte()), 2)
        }
    }

    @Test
    fun `KF8 auxiliary stylesheet flows are not appended to book text`() {
        val database = ByteBuffer.allocate(418).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(76, 3)
            putInt(78, 102)
            putInt(86, 382)
            putInt(94, 390)
            putShort(102, 1)
            putInt(106, 8)
            putShort(110, 1)
            putInt(118, 0x4d4f4249)
            putInt(122, 264)
            putInt(138, 8)
            putInt(294, 2)
            putInt(298, 2)
            position(382)
            put("helloCSS".encodeToByteArray())
            putInt(0x46445354)
            putInt(12)
            putInt(2)
            putInt(0)
            putInt(5)
            putInt(5)
            putInt(8)
        }.array()
        assertEquals("hello", MobiParser.decodeText(database))
    }

    private fun palmDatabase(record: ByteArray, length: Int, compression: Int = 2, extraFlags: Int? = null): ByteArray {
        val textOffset = if (extraFlags == null) 110 else 338
        return ByteBuffer.allocate(textOffset + record.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(76, 2)
            putInt(78, 94)
            putInt(86, textOffset)
            putShort(94, compression.toShort())
            putInt(98, length)
            putShort(102, 1)
            if (extraFlags != null) {
                putInt(110, 0x4d4f4249)
                putInt(114, 0xe4)
                putShort(336, extraFlags.toShort())
            }
            position(textOffset)
            put(record)
        }.array()
    }

    @Test
    fun `PalmDOC back references can overlap without copying the output buffer`() {
        val compressed = byteArrayOf(
            'a'.code.toByte(),
            'b'.code.toByte(),
            'c'.code.toByte(),
            0x80.toByte(),
            0x1b,
        )

        assertArrayEquals("abcabcabc".encodeToByteArray(), decompressPalmDocRecord(compressed))
    }
}
