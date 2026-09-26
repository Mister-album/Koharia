package koharia.reader.resampling

import com.davemorrissey.labs.subscaleview.TileGrid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

class TileGridTest {
    @Test
    fun `long images partition promptly beyond the previous nonterminating boundary`() =
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            for (length in listOf(16383, 16384, 20000, 20001, 100001)) {
                for (sample in listOf(1, 2, 4, 8, 32)) {
                    val count = TileGrid.count(length, 256, sample)
                    val boundaries = (0..count).map { TileGrid.boundary(it, count, length) }
                    assertEquals(0, boundaries.first())
                    assertEquals(length, boundaries.last())
                    assertEquals(length, boundaries.zipWithNext().sumOf { (a, b) -> b - a })
                    boundaries.zipWithNext().forEach { (a, b) ->
                        assertTrue(b > a)
                        assertTrue((b - a + sample - 1) / sample <= 256)
                    }
                }
            }
        }

    @Test
    fun `small and extreme dimensions preserve positive intervals without integer overflow`() {
        for (length in listOf(1, 255, 256, 257, Int.MAX_VALUE)) {
            for (limit in listOf(1, 256, 4096, Int.MAX_VALUE)) {
                for (sample in listOf(1, 4, 1 shl 28)) {
                    val count = TileGrid.count(length, limit, sample)
                    assertTrue(count in 1..length)
                    assertEquals(0, TileGrid.boundary(0, count, length))
                    assertEquals(length, TileGrid.boundary(count, count, length))
                    for (index in setOf(0, count / 2, count - 1)) {
                        val span = TileGrid.boundary(index + 1, count, length).toLong() -
                            TileGrid.boundary(index, count, length)
                        assertTrue(span > 0 && (span + sample - 1) / sample <= limit)
                    }
                }
            }
        }
    }
}
