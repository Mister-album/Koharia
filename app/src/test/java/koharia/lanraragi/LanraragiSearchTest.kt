package koharia.lanraragi

import koharia.domain.lanraragi.LanraragiEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class LanraragiSearchTest {
    @Test
    fun `offline or unreachable falls back but empty online result stays empty`() = runTest {
        assertNull(searchLanraragiWithFallback(false) { error("Offline must not issue requests") })
        assertNull(searchLanraragiWithFallback(true) { throw IOException("unreachable") })
        assertNull(
            searchLanraragiWithFallback(true) {
                throw LanraragiException(LanraragiException.Reason.SERVER, 503)
            },
        )
        assertNull(
            searchLanraragiWithFallback(true) {
                delay(20_000)
                emptyList()
            },
        )
        assertEquals(emptyList<LanraragiEntry>(), searchLanraragiWithFallback(true) { emptyList() })
    }

    @Test
    fun `authentication business failure and cancellation remain visible`() {
        for (reason in listOf(LanraragiException.Reason.AUTH, LanraragiException.Reason.SERVER)) {
            assertThrows(LanraragiException::class.java) {
                runTest { searchLanraragiWithFallback(true) { throw LanraragiException(reason) } }
            }
        }
        assertThrows(CancellationException::class.java) {
            runTest { searchLanraragiWithFallback(true) { throw CancellationException() } }
        }
    }

    @Test
    fun `server matches include nested collections without keeping unrelated or empty collections`() {
        val archive = LanraragiEntry("a", title = "Remote syntax match")
        val inner = LanraragiEntry("inner", LanraragiEntry.Kind.TANK, "Inner", members = listOf("a"))
        val outer = LanraragiEntry("outer", LanraragiEntry.Kind.TANK, "Outer", members = listOf("inner"))
        val unrelated = LanraragiEntry("other", LanraragiEntry.Kind.TANK, "Other")
        val entries = listOf(archive, inner, outer, unrelated)
        assertEquals(
            setOf("inner", "outer"),
            filterLanraragiCatalog(entries, emptyList(), LanraragiFilter(), setOf("a")).map { it.id }.toSet(),
        )
        assertEquals(
            emptyList<LanraragiEntry>(),
            filterLanraragiCatalog(entries, emptyList(), LanraragiFilter(), emptySet()),
        )
    }
}
