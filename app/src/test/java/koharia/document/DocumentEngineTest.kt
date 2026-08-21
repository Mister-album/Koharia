package koharia.document

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class DocumentEngineTest {

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
