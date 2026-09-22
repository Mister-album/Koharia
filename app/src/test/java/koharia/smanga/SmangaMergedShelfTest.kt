package koharia.smanga

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SmangaMergedShelfTest {
    @Test
    fun `database name collation preserves each library order across pages and restored cursors`() = runTest {
        val first = (1L..150).map {
            manga(it, 1, (if (it <= 50) "alpha" else "Beta") + it.toString().padStart(3, '0'))
        }
        val second = (151L..300).map {
            manga(it, 2, (if (it <= 200) "carrot" else "Duck") + it.toString().padStart(3, '0'))
        }
        for (descending in listOf(false, true)) {
            val libraries = mapOf(
                1L to if (descending) first.reversed() else first,
                2L to if (descending) second.reversed() else second,
            )
            val remote = RemotePages(libraries)
            val order = if (descending) "mangaName desc" else NAME_ASC
            var previous: SmangaMergedShelfPage? = null
            val items = mutableListOf<SmangaManga>()
            repeat(3) { index ->
                val result = mergeSmangaShelfPage(listOf(1, 2), order, index + 1, previous, remote::load)
                if (index == 0) assertEquals(listOf(1L to 1, 2L to 1), remote.requests.sortedBy { it.first })
                items += result.page.data
                previous = Json.decodeFromString<SmangaMergedShelfPage>(Json.encodeToString(result))
            }
            assertEquals((1L..300).toSet(), items.map { it.id }.toSet())
            assertEquals(300, items.size)
            assertEquals(libraries, items.groupBy { it.mediaId })
            assertFalse(previous!!.page.hasNext)
        }
    }

    @Test
    fun `three interleaved libraries cross remote page boundaries without omissions or duplicates`() = runTest {
        val all = (1L..390).map { manga(it, (it - 1) % 3 + 1) }
        val remote = RemotePages(all.groupBy { it.mediaId })
        var cursor: SmangaMergedShelfPage? = null
        val collected = mutableListOf<SmangaManga>()
        repeat(4) { index ->
            cursor = mergeSmangaShelfPage(listOf(3, 1, 2), NAME_ASC, index + 1, cursor, remote::load)
            assertEquals(390, cursor.page.total)
            assertEquals(if (index < 3) 100 else 90, cursor.page.data.size)
            collected += cursor.page.data
        }
        assertEquals(all, collected)
        assertEquals(390, collected.map { it.id }.toSet().size)
        assertEquals(mapOf(1L to 130, 2L to 130, 3L to 130), cursor!!.offsets)
        assertFalse(cursor.page.hasNext)
        assertTrue(remote.requests.all { it.second in 1..2 })
    }

    @Test
    fun `a full output page does not fetch the winning library next remote page`() = runTest {
        val remote = RemotePages(
            mapOf(1L to (1L..300).map { manga(it, 1) }, 2L to listOf(manga(999, 2))),
        )
        val first = mergeSmangaShelfPage(listOf(1, 2), NAME_ASC, 1, null, remote::load)
        assertEquals((1L..100).toList(), first.page.data.map { it.id })
        assertEquals(listOf(1L to 1, 2L to 1), remote.requests.sortedBy { it.first })
        assertEquals(mapOf(1L to 100, 2L to 0), first.offsets)
        remote.requests.clear()

        val second = mergeSmangaShelfPage(listOf(1, 2), NAME_ASC, 2, first, remote::load)
        assertEquals((101L..200).toList(), second.page.data.map { it.id })
        assertEquals(listOf(1L to 2, 2L to 1), remote.requests.sortedBy { it.first })
        assertEquals(mapOf(1L to 200, 2L to 0), second.offsets)
    }

    @Test
    fun `descending name ties use media ID and preserve server order within a library`() = runTest {
        val remote = RemotePages(
            mapOf(
                1L to listOf(manga(90, 1, "z"), manga(10, 1, "z"), manga(30, 1, "a")),
                2L to listOf(manga(80, 2, "z"), manga(20, 2, "z"), manga(40, 2, "a")),
            ),
        )
        val result = mergeSmangaShelfPage(listOf(2, 1), "mangaName desc", 1, null, remote::load)
        assertEquals(listOf(90L, 10L, 80L, 20L, 30L, 40L), result.page.data.map { it.id })
    }

    @Test
    fun `name order uses raw mangaName and unsigned UTF8 bytes instead of display name or UTF16`() = runTest {
        val supplementary = manga(1, 1, "display-a").copy(sortName = "\uD800\uDC00")
        val privateUse = manga(2, 2, "display-z").copy(sortName = "\uE000")
        val remote = RemotePages(mapOf(1L to listOf(supplementary), 2L to listOf(privateUse)))
        val result = mergeSmangaShelfPage(listOf(1, 2), NAME_ASC, 1, null, remote::load)
        assertEquals(listOf(2L, 1L), result.page.data.map { it.id })
    }

    @Test
    fun `date ordering supports both fields and directions with stable media ties`() = runTest {
        val records = listOf(
            manga(1, 1).copy(createdAt = 30, updatedAt = 10),
            manga(2, 1).copy(createdAt = 10, updatedAt = 20),
            manga(3, 2).copy(createdAt = 20, updatedAt = 30),
            manga(4, 2).copy(createdAt = 30, updatedAt = 20),
        )
        val cases = listOf(
            "createTime asc" to listOf(2L, 3L, 1L, 4L),
            "createTime desc" to listOf(1L, 4L, 3L, 2L),
            "updateTime asc" to listOf(1L, 2L, 4L, 3L),
            "updateTime desc" to listOf(3L, 2L, 4L, 1L),
        )
        for ((order, expected) in cases) {
            val ascending = compareBy<SmangaManga> {
                if (order.startsWith("createTime")) it.createdAt else it.updatedAt
            }
            val comparator = if (order.endsWith("desc")) ascending.reversed() else ascending
            val remote = RemotePages(
                records.groupBy { it.mediaId }.mapValues { (_, items) -> items.sortedWith(comparator) },
            )
            val result = mergeSmangaShelfPage(listOf(2, 1), order, 1, null, remote::load)
            assertEquals(expected, result.page.data.map { it.id }, order)
        }
    }

    @Test
    fun `serialized cursor resumes consumed offsets across both current and next remote pages`() = runTest {
        val remote = RemotePages(
            mapOf(1L to (1L..150).map { manga(it, 1) }, 2L to (151L..300).map { manga(it, 2) }),
        )
        val first = mergeSmangaShelfPage(listOf(1, 2), "id asc", 1, null, remote::load)
        val serialized = Json.encodeToString(first)
        val restored = Json.decodeFromString<SmangaMergedShelfPage>(serialized)
        assertEquals(first, restored)
        remote.requests.clear()

        val second = mergeSmangaShelfPage(listOf(2, 1), "id asc", 2, restored, remote::load)
        assertEquals((101L..200).toList(), second.page.data.map { it.id })
        assertEquals(mapOf(1L to 150, 2L to 50), second.offsets)
        assertEquals(mapOf(1L to 150, 2L to 150), second.totals)
        assertEquals(listOf(1L to 2, 2L to 1), remote.requests.sortedBy { it.first })
        assertEquals(serialized, Json.encodeToString(restored))
    }

    @Test
    fun `empty authorization needs no requests and exhausted libraries are not fetched again`() = runTest {
        val empty = mergeSmangaShelfPage(emptyList(), NAME_ASC, 1, null) { _, _ ->
            throw AssertionError("An empty authorized set must not issue a request")
        }
        assertEquals(SmangaMangaPage(emptyList(), 1, 100, 0), empty.page)
        assertTrue(empty.offsets.isEmpty())
        assertTrue(empty.totals.isEmpty())

        val remote = RemotePages(mapOf(1L to emptyList(), 2L to listOf(manga(5, 2))))
        val first = mergeSmangaShelfPage(listOf(1, 2), NAME_ASC, 1, null, remote::load)
        assertEquals(listOf(5L), first.page.data.map { it.id })
        assertEquals(mapOf(1L to 0, 2L to 1), first.totals)
        remote.requests.clear()
        val finished = mergeSmangaShelfPage(listOf(1, 2), NAME_ASC, 2, first, remote::load)
        assertTrue(finished.page.data.isEmpty())
        assertEquals(1, finished.page.total)
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `one library failure propagates instead of returning a partial shelf`() = runTest {
        val unavailable = IOException("Fixture unavailable")
        val error = runCatching {
            mergeSmangaShelfPage(listOf(1, 2), NAME_ASC, 1, null) { mediaId, page ->
                if (mediaId == 2L) throw unavailable
                SmangaMangaPage(listOf(manga(1, 1)), page, 100, 1)
            }
        }.exceptionOrNull()
        assertEquals(IOException::class.java, error?.javaClass)
        assertEquals(unavailable.message, error?.message)
    }

    @Test
    fun `changed remote totals fail without mutating the previous cursor`() = runTest {
        val remote = RemotePages(mapOf(1L to (1L..200).map { manga(it, 1) }))
        val first = mergeSmangaShelfPage(listOf(1), NAME_ASC, 1, null, remote::load)
        val before = Json.encodeToString(first)
        expectFailure(SmangaException.Reason.INCOMPLETE) {
            mergeSmangaShelfPage(listOf(1), NAME_ASC, 2, first) { id, page ->
                remote.load(id, page).copy(total = 201)
            }
        }
        assertEquals(before, Json.encodeToString(first))
    }

    @Test
    fun `malformed and incomplete remote pages fail closed`() = runTest {
        val good = SmangaMangaPage((1L..100).map { manga(it, 1) }, 1, 100, 101)
        val cases = listOf(
            good.copy(page = 2) to SmangaException.Reason.PROTOCOL,
            good.copy(pageSize = 50) to SmangaException.Reason.PROTOCOL,
            good.copy(total = -1) to SmangaException.Reason.PROTOCOL,
            good.copy(data = good.data.dropLast(1)) to SmangaException.Reason.INCOMPLETE,
            good.copy(data = good.data + manga(101, 1)) to SmangaException.Reason.INCOMPLETE,
            good.copy(data = good.data.map { it.copy(mediaId = 2) }) to SmangaException.Reason.INCOMPLETE,
            good.copy(data = good.data.dropLast(1) + good.data.first()) to SmangaException.Reason.INCOMPLETE,
            SmangaMangaPage(listOf(manga(1, 1)), 1, 100, 0) to SmangaException.Reason.INCOMPLETE,
        )
        for ((response, reason) in cases) {
            expectFailure(reason) {
                mergeSmangaShelfPage(listOf(1), NAME_ASC, 1, null) { _, _ -> response }
            }
        }
    }

    @Test
    fun `numeric and date sequences still reject reordered pages and cursors`() = runTest {
        val records = (1L..101).map { manga(it, 1).copy(createdAt = it, updatedAt = it) }
        for (field in listOf("id", "createTime", "updateTime")) {
            for (direction in listOf("asc", "desc")) {
                val order = "$field $direction"
                val ordered = if (direction == "asc") records else records.reversed()
                val remote = RemotePages(mapOf(1L to ordered))
                expectFailure(SmangaException.Reason.INCOMPLETE) {
                    mergeSmangaShelfPage(listOf(1), order, 1, null) { id, page ->
                        remote.load(id, page).let { it.copy(data = it.data.reversed()) }
                    }
                }
                val first = mergeSmangaShelfPage(listOf(1), order, 1, null, remote::load)
                val invalid = first.copy(page = first.page.copy(data = first.page.data.reversed()))
                expectFailure(SmangaException.Reason.PROTOCOL) {
                    mergeSmangaShelfPage(listOf(1), order, 2, invalid) { _, _ ->
                        throw AssertionError("Invalid cursor must not request data")
                    }
                }
            }
        }
    }

    @Test
    fun `missing final item and backward numeric next page cannot silently finish`() = runTest {
        val remote = RemotePages(mapOf(1L to (101L..201).map { manga(it, 1) }))
        val first = mergeSmangaShelfPage(listOf(1), "id asc", 1, null, remote::load)
        for (items in listOf(emptyList(), listOf(manga(50, 1)))) {
            expectFailure(SmangaException.Reason.INCOMPLETE) {
                mergeSmangaShelfPage(listOf(1), "id asc", 2, first) { _, page ->
                    SmangaMangaPage(items, page, 100, 101)
                }
            }
        }
    }

    @Test
    fun `invalid cursor or unsupported order fails before any request`() = runTest {
        val remote = RemotePages(mapOf(1L to (1L..150).map { manga(it, 1) }))
        val first = mergeSmangaShelfPage(listOf(1), NAME_ASC, 1, null, remote::load)
        val cursors = listOf(
            null,
            first.copy(offsets = mapOf(1L to 99)),
            first.copy(totals = mapOf(2L to 150)),
            first.copy(page = first.page.copy(page = 2)),
        )
        for (previous in cursors) {
            expectFailure(SmangaException.Reason.PROTOCOL) {
                mergeSmangaShelfPage(listOf(1), NAME_ASC, 2, previous) { _, _ ->
                    throw AssertionError("Invalid cursor must not request data")
                }
            }
        }
        expectFailure(SmangaException.Reason.PROTOCOL) {
            mergeSmangaShelfPage(listOf(1), "random", 1, null) { _, _ ->
                throw AssertionError("Invalid sort must not request data")
            }
        }
    }

    @Test
    fun `initial head requests run with at most two concurrent fetches`() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val result = mergeSmangaShelfPage((1L..9).toList(), NAME_ASC, 1, null) { id, page ->
            val concurrent = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, concurrent) }
            calls.incrementAndGet()
            try {
                delay(10)
                SmangaMangaPage(listOf(manga(id, id)), page, 100, 1)
            } finally {
                active.decrementAndGet()
            }
        }
        assertEquals(2, peak.get())
        assertEquals(9, calls.get())
        assertEquals(0, active.get())
        assertEquals((1L..9).toList(), result.page.data.map { it.id })
    }

    @Test
    fun `cancellation stops active heads and does not start the remaining libraries`() = runTest {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val loading = async {
            mergeSmangaShelfPage(listOf(1, 2, 3), NAME_ASC, 1, null) { _, _ ->
                calls.incrementAndGet()
                active.incrementAndGet()
                try {
                    awaitCancellation()
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        runCurrent()
        assertEquals(2, active.get())
        loading.cancelAndJoin()
        assertEquals(2, calls.get())
        assertEquals(0, active.get())
    }

    private suspend fun expectFailure(reason: SmangaException.Reason, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is SmangaException, "Expected a typed Smanga failure")
        assertEquals(reason, (error as SmangaException).reason)
    }

    private class RemotePages(private val libraries: Map<Long, List<SmangaManga>>) {
        val requests = mutableListOf<Pair<Long, Int>>()

        suspend fun load(mediaId: Long, page: Int): SmangaMangaPage {
            requests += mediaId to page
            val items = libraries.getValue(mediaId)
            return SmangaMangaPage(items.drop((page - 1) * 100).take(100), page, 100, items.size)
        }
    }

    private companion object {
        const val NAME_ASC = "mangaName asc"
        fun manga(id: Long, mediaId: Long, name: String = id.toString().padStart(5, '0')) =
            SmangaManga(id, mediaId, name)
    }
}
