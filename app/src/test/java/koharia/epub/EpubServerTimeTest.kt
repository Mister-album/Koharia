package koharia.epub

import koharia.domain.epub.model.EpubRemoteProgressCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Date

class EpubServerTimeTest {
    private val receivedAt = 1_700_000_000_000L

    private fun sample(serverTime: Long?) = EpubRemoteProgressCache(
        chapterId = 1,
        mangaId = 1,
        bookUrl = "https://fixture.invalid/book",
        locatorJson = null,
        progression = null,
        positionIndex = null,
        modifiedAt = null,
        checkedAt = Date(receivedAt),
        serverDate = serverTime?.let(::Date),
    )

    @Test
    fun `old synchronized sample does not become a clock warning`() {
        assertNull(sample(receivedAt + 1_000).serverTimeOffsetMinutes())
    }

    @Test
    fun `missing server date has no warning`() {
        assertNull(sample(null).serverTimeOffsetMinutes())
    }

    @Test
    fun `unknown receipt time and sentinel server dates have no warning`() {
        assertNull(sample(-62_135_596_800_000L).serverTimeOffsetMinutes())
        assertNull(sample(0).serverTimeOffsetMinutes())
        assertNull(sample(receivedAt).copy(checkedAt = Date(0)).serverTimeOffsetMinutes())
    }

    @Test
    fun `actual positive and negative offsets retain threshold`() {
        assertNull(sample(receivedAt + 299_000).serverTimeOffsetMinutes())
        assertEquals(5L, sample(receivedAt + 300_000).serverTimeOffsetMinutes())
        assertEquals(10L, sample(receivedAt - 600_000).serverTimeOffsetMinutes())
    }
}
