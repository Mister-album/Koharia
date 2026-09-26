package koharia.komga.domain.repository

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import koharia.komga.api.KomgaApiClient.SearchType
import koharia.komga.api.dto.KomgaOfflineFilterMetadata
import koharia.komga.api.dto.withOfflineFilterMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class KomgaSortedSearchSessionTest {
    @Test
    fun `books and series heads load concurrently`() = runBlocking {
        val books = CompletableDeferred<Unit>()
        val series = CompletableDeferred<Unit>()
        val session = KomgaSortedSearchSession(2, true) { type, _ ->
            if (type == SearchType.BOOKS) {
                books.complete(Unit)
                series.await()
            } else {
                series.complete(Unit)
                books.await()
            }
            MangasPage(listOf(manga(type, 1, 1)), false)
        }
        assertEquals(2, withTimeout(2000) { session.load(1) }.mangas.size)
    }

    @Test
    fun `account change rejects cached output and buffered pages`() = runBlocking {
        var valid = true
        val session = KomgaSortedSearchSession(2, true, {
            if (!valid) throw CancellationException("Account changed")
        }) { type, _ ->
            MangasPage((1..30).map { manga(type, it, 10L) }, false)
        }
        val first = session.load(1)
        assertEquals((1..25).map { "BOOKS-$it" }, first.mangas.map { it.title })
        valid = false
        assertThrows(CancellationException::class.java) { runBlocking { session.load(1) } }
        assertThrows(CancellationException::class.java) { runBlocking { session.load(2) } }
        valid = true
        val second = session.load(2)
        assertEquals((26..30).map { "BOOKS-$it" } + (1..20).map { "SERIES-$it" }, second.mangas.map { it.title })
    }

    @Test
    fun `modified time is independent of created time`() {
        val memo = JsonObject(emptyMap()).withOfflineFilterMetadata(
            KomgaOfflineFilterMetadata(createdDate = "2020-01-01T00:00:00Z", lastModifiedDate = "2024-01-01T00:00:00Z"),
        )
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), memo.komgaSearchTimestamp(3))
    }

    @Test
    fun `timestamps preserve nanoseconds and offsets and reject malformed values`() {
        val expected = Instant.parse("2026-09-25T10:00:00.123456789Z")
        for (date in listOf(
            "2026-09-25T10:00:00.123456789",
            "2026-09-25T10:00:00.123456789Z",
            "2026-09-25T18:00:00.123456789+08:00",
        )) {
            val memo = JsonObject(emptyMap()).withOfflineFilterMetadata(KomgaOfflineFilterMetadata(createdDate = date))
            assertEquals(expected, memo.komgaSearchTimestamp(2))
        }
        for (date in listOf(null, "", "invalid", "2026-02-30T10:00:00", "2026-09-25T10:00:00Z trailing")) {
            val memo = JsonObject(emptyMap()).withOfflineFilterMetadata(KomgaOfflineFilterMetadata(createdDate = date))
            assertNull(memo.komgaSearchTimestamp(2))
        }
    }

    @Test
    fun `sub millisecond times merge across remote and output pages in both directions`() = runBlocking {
        for (sortIndex in listOf(2, 3)) {
            for (ascending in listOf(true, false)) {
                val chronological = (1..60).map { index ->
                    val type = if (index % 2 == 0) SearchType.BOOKS else SearchType.SERIES
                    manga(type, 61 - index, null).apply {
                        val date = "2026-09-25T10:00:00.${index.toString().padStart(9, '0')}"
                        memo = JsonObject(emptyMap()).withOfflineFilterMetadata(
                            KomgaOfflineFilterMetadata(createdDate = date, lastModifiedDate = date),
                        )
                    }
                }
                val expected = if (ascending) chronological else chronological.reversed()
                val session = KomgaSortedSearchSession(sortIndex, ascending) { type, page ->
                    val stream = expected.filter { it.title.startsWith("$type-") }
                    MangasPage(stream.drop((page - 1) * 7).take(7), page * 7 < stream.size)
                }
                val actual = (1..3).flatMap { page ->
                    session.load(page).also { assertEquals(page < 3, it.hasNextPage) }.mangas
                }
                assertEquals(expected.map { it.url }, actual.map { it.url })
            }
        }
    }

    private fun manga(type: SearchType, id: Int, time: Long?) = SManga.create().apply {
        title = "$type-$id"
        url = "https://server/api/v1/${type.pathSegment}/${id.toString().padStart(4, '0')}"
        memo = JsonObject(emptyMap()).withOfflineFilterMetadata(
            KomgaOfflineFilterMetadata(createdDate = time?.let { Instant.ofEpochSecond(it).toString() }),
        )
    }

    @Test
    fun `uneven streams remain globally ordered across pages in both directions`() = runBlocking {
        for (ascending in listOf(true, false)) {
            val books = (1..81).map { manga(SearchType.BOOKS, it, it.toLong()) }
            val series = listOf(2, 5, 80).map { manga(SearchType.SERIES, it, it.toLong()) }
            val expected = (books + series).sortedWith { a, b ->
                compareKomgaSearchTime(
                    a.memo.komgaSearchTimestamp(2),
                    a.url,
                    b.memo.komgaSearchTimestamp(2),
                    b.url,
                    ascending,
                )
            }
            val session = KomgaSortedSearchSession(2, ascending) { type, page ->
                val items = (
                    if (type ==
                        SearchType.BOOKS
                    ) {
                        books
                    } else {
                        series
                    }
                    ).let { if (ascending) it else it.reversed() }
                MangasPage(items.drop((page - 1) * 7).take(7), page * 7 < items.size)
            }
            val actual = mutableListOf<SManga>()
            for (page in 1..4) {
                val result = session.load(page)
                assertTrue(result.mangas.size <= 25)
                actual += result.mangas
                assertEquals(page < 4, result.hasNextPage)
            }
            assertEquals(expected.map { it.url }, actual.map { it.url })
        }
    }

    @Test
    fun `failure after consuming a partial output retains fetched pages and retries without gaps`() = runBlocking {
        var fail = true
        val calls = mutableMapOf<Pair<SearchType, Int>, Int>()
        val session = KomgaSortedSearchSession(2, true) { type, page ->
            val key = type to page
            calls[key] = calls.getOrDefault(key, 0) + 1
            if (type == SearchType.SERIES) {
                MangasPage(emptyList(), false)
            } else {
                if (page == 2 && fail) throw IOException("retry")
                MangasPage((1..10).map { manga(type, (page - 1) * 10 + it, ((page - 1) * 10 + it).toLong()) }, page < 3)
            }
        }
        assertThrows(IOException::class.java) { runBlocking { session.load(1) } }
        fail = false
        val first = session.load(1)
        assertEquals(25, first.mangas.size)
        assertEquals(first, session.load(1))
        val second = session.load(2)
        assertEquals(30, (first.mangas + second.mangas).map { it.url }.distinct().size)
        assertFalse(second.hasNextPage)
        assertEquals(1, calls[SearchType.BOOKS to 1])
        assertEquals(1, calls[SearchType.SERIES to 1])
    }

    @Test
    fun `valid empty results and independent sessions`() = runBlocking {
        val empty = KomgaSortedSearchSession(2, true) { _, _ -> MangasPage(emptyList(), false) }
        assertTrue(empty.load(1).mangas.isEmpty())
        assertFalse(empty.load(1).hasNextPage)
        val other = KomgaSortedSearchSession(2, true) { type, _ ->
            MangasPage(if (type == SearchType.BOOKS) listOf(manga(type, 9, 9)) else emptyList(), false)
        }
        assertEquals(1, other.load(1).mangas.size)
    }

    @Test
    fun `missing times stay last regardless of direction and ties use resource identity`() {
        for (ascending in listOf(true, false)) {
            val time = Instant.ofEpochSecond(1)
            assertTrue(compareKomgaSearchTime(null, "books/a", time, "series/b", ascending) > 0)
            assertTrue(compareKomgaSearchTime(time, "books/a", time, "series/b", ascending) < 0)
        }
    }

    @Test
    fun `cancellation propagates without committing cursor`() = runBlocking {
        var cancelled = true
        val session = KomgaSortedSearchSession(2, true) { _, _ ->
            if (cancelled) throw CancellationException()
            MangasPage(emptyList(), false)
        }
        assertThrows(CancellationException::class.java) { runBlocking { session.load(1) } }
        cancelled = false
        assertFalse(session.load(1).hasNextPage)
    }
}
