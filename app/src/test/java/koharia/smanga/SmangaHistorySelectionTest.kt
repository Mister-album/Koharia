package koharia.smanga

import koharia.domain.smanga.SmangaReadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SmangaHistorySelectionTest {
    @Test
    fun `latest exact reading wins over the maximum chapter id`() {
        val latest = state(chapterId = 3, readAt = 200)
        val states = listOf(state(chapterId = 99, readAt = 100), latest)

        assertEquals(latest, selectSmangaHistoryState(7, states))
    }

    @Test
    fun `a partial visit remains partial when selected for history`() {
        val partial = state(chapterId = 3, readAt = 200, pageIndex = 2)
        val selected = selectSmangaHistoryState(7, listOf(partial))

        assertEquals(partial, selected)
        assertFalse(requireNotNull(selected).completed)
    }

    @Test
    fun `missing exact progress or a missing timestamp cannot identify a resume chapter`() {
        assertNull(selectSmangaHistoryState(7, emptyList()))
        assertNull(selectSmangaHistoryState(7, listOf(state(pageIndex = -1))))
        assertNull(selectSmangaHistoryState(7, listOf(state(readAt = 0))))
        assertNull(selectSmangaHistoryState(7, listOf(state(readAt = -1))))
    }

    @Test
    fun `equal newest timestamps do not guess from chapter ids or input order`() {
        val older = state(chapterId = 1, readAt = 50)
        val first = state(chapterId = 3, readAt = 100)
        val second = state(chapterId = 99, readAt = 100)

        assertNull(selectSmangaHistoryState(7, listOf(older, first, second)))
        assertNull(selectSmangaHistoryState(7, listOf(second, first, older)))
    }

    @Test
    fun `explicit unread invalid page positions and other manga cannot become history targets`() {
        val valid = state(chapterId = 3, readAt = 100)
        val states = listOf(
            valid,
            state(chapterId = 4, readAt = 200).copy(explicitUnread = true),
            state(chapterId = 5, readAt = 300, pageIndex = 10),
            state(chapterId = 6, readAt = 400).copy(mangaId = 8),
        )

        assertEquals(valid, selectSmangaHistoryState(7, states))
    }

    @Test
    fun `explicit completion can identify a chapter with an unknown page count`() {
        val completed = state().copy(pageIndex = -1, totalPages = 0, completed = true)

        assertEquals(completed, selectSmangaHistoryState(7, listOf(completed)))
    }

    private fun state(chapterId: Long = 3, readAt: Long = 100, pageIndex: Int = 2) = SmangaReadState(
        chapterId = chapterId,
        mangaId = 7,
        pageIndex = pageIndex,
        totalPages = 10,
        completed = false,
        readAt = readAt,
        revision = 1,
        pending = false,
    )
}
