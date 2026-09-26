package koharia.smanga

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmangaSearchPageTest {
    private fun item(id: Long, media: Long = 2) = SmangaManga(id, media, "item-$id", createdAt = id)

    @Test
    fun `title and tag requests cannot wait for one another to complete`() = runTest {
        val titleStarted = CompletableDeferred<Unit>()
        val tagStarted = CompletableDeferred<Unit>()
        val result = withTimeout(2000) {
            mergeSmangaSearchPage(listOf(2), "createTime asc", 1, null, {
                titleStarted.complete(Unit)
                tagStarted.await()
                SmangaMangaPage(listOf(item(1), item(2)), 1, 100, 2)
            }, { page ->
                tagStarted.complete(Unit)
                titleStarted.await()
                if (page == 1) listOf(item(2), item(3)) else emptyList()
            })
        }
        assertEquals(listOf(1L, 2L, 3L), result.page.data.map { it.id })
        assertFalse(result.page.hasNext)
        assertEquals(3, result.page.total)
    }

    @Test
    fun `cross page duplicates sparse tag pages and other media do not truncate either direction`() = runTest {
        for (descending in listOf(false, true)) {
            val title = (1L..150L).map(::item).let { if (descending) it.reversed() else it }
            val tags = (50L..220L).flatMap { listOf(item(it), item(it)) }
                .let { if (descending) it.reversed() else it }.chunked(23)
            var previous: SmangaSearchPage? = null
            val actual = mutableListOf<Long>()
            do {
                val next = (previous?.page?.page ?: 0) + 1
                previous = mergeSmangaSearchPage(
                    listOf(2),
                    "createTime ${if (descending) "desc" else "asc"}",
                    next,
                    previous,
                    { page -> SmangaMangaPage(title.drop((page - 1) * 100).take(100), page, 100, title.size) },
                    { page ->
                        // An out-of-scope page is not the end of the tag stream.
                        if (page == 1) listOf(item(999, 3)) else tags.getOrNull(page - 2).orEmpty()
                    },
                )
                actual += previous.page.data.map { it.id }
            } while (previous!!.page.hasNext)
            val expected = (1L..220L).toList().let { if (descending) it.reversed() else it }
            assertEquals(expected, actual)
            assertEquals(220, previous!!.page.total)
        }
    }

    @Test
    fun `failed append leaves checkpoint reusable without skipped or repeated entries`() = runTest {
        val titles = (1L..120L).map(::item)
        val loadTitle: suspend (Int) -> SmangaMangaPage = { page ->
            SmangaMangaPage(titles.drop((page - 1) * 100).take(100), page, 100, titles.size)
        }
        val first = mergeSmangaSearchPage(listOf(2), "createTime asc", 1, null, loadTitle) {
            listOf(item(100))
        }
        assertTrue(first.page.hasNext)
        val failed = runCatching {
            mergeSmangaSearchPage(listOf(2), "createTime asc", 2, first, loadTitle) { error("offline") }
        }
        assertTrue(failed.isFailure)
        val retried = mergeSmangaSearchPage(listOf(2), "createTime asc", 2, first, loadTitle) {
            emptyList()
        }
        assertEquals((101L..120L).toList(), retried.page.data.map { it.id })
        assertEquals(100, first.seen.size)
    }

    @Test
    fun `cancelling search cancels both outstanding queries`() = runTest {
        val titleStarted = CompletableDeferred<Unit>()
        val tagStarted = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val cancelled = mutableSetOf<String>()
        val request = async {
            mergeSmangaSearchPage(listOf(2), "createTime asc", 1, null, {
                titleStarted.complete(Unit)
                try {
                    never.await()
                } finally {
                    cancelled += "title"
                }
                SmangaMangaPage(emptyList(), 1, 100, 0)
            }, {
                tagStarted.complete(Unit)
                try {
                    never.await()
                } finally {
                    cancelled += "tag"
                }
                emptyList()
            })
        }
        titleStarted.await()
        tagStarted.await()
        request.cancelAndJoin()
        assertEquals(setOf("title", "tag"), cancelled)
    }
}
