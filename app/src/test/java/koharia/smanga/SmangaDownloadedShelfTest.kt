package koharia.smanga

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class SmangaDownloadedShelfTest {
    private val prefix = "/smanga/7/account/"
    private fun entry(id: Long, media: Long?, name: String = "Same", updated: Long = id): Manga =
        Manga.create().copy(
            id = id,
            source = 7,
            url = "${prefix}manga/$id",
            title = name,
            genre = listOf("Tag"),
            memo = JsonObject(
                if (media == null) {
                    emptyMap()
                } else {
                    mapOf(
                        SMANGA_SHELF_METADATA to SmangaManga(id, media, name, updatedAt = updated).shelfMetadata(),
                    )
                },
            ),
        )

    @Test
    fun `downloaded catalogue refreshes unit totals while absent or older metadata retains the known count`() {
        val original = entry(1, 10, updated = 10).let {
            it.copy(memo = JsonObject(it.memo + ("booksCount" to JsonPrimitive(2))))
        }
        fun count(cache: Map<Long, SmangaManga>) = downloadedSmangaShelf(
            listOf(original),
            prefix,
            10,
            "",
            "mangaName asc",
            cache,
        ).single().memo["booksCount"]
        assertEquals(JsonPrimitive(2), count(emptyMap()))
        assertEquals(JsonPrimitive(2), count(mapOf(1L to SmangaManga(1, 10, "Old", updatedAt = 5, chapterCount = 1))))
        assertEquals(JsonPrimitive(3), count(mapOf(1L to SmangaManga(1, 10, "New", updatedAt = 20, chapterCount = 3))))
        assertEquals(
            JsonPrimitive(0),
            count(mapOf(1L to SmangaManga(1, 10, "Empty", updatedAt = 20, chapterCount = 0))),
        )
    }

    @Test
    fun `known total does not require media ownership metadata`() {
        val original = entry(1, null)
        val result = downloadedSmangaShelf(
            listOf(original),
            prefix,
            0,
            "",
            "mangaName asc",
            mapOf(1L to SmangaManga(1, 0, "Unknown media", chapterCount = 3)),
        ).single()
        assertEquals(JsonPrimitive(3), result.memo["booksCount"])
        assertEquals(original.memo[SMANGA_SHELF_METADATA], result.memo[SMANGA_SHELF_METADATA])
    }

    @Test
    fun `selected library excludes unknown ownership and other accounts but all keeps unknown downloads`() {
        val entries =
            listOf(entry(1, 10), entry(2, 20), entry(3, null), entry(4, 10).copy(url = "/smanga/7/other/manga/4"))
        assertEquals(
            listOf(1L),
            downloadedSmangaShelf(entries, prefix, 10, "", "mangaName asc", emptyMap()).map {
                it.id
            },
        )
        assertEquals(
            listOf(3L, 1L, 2L),
            downloadedSmangaShelf(entries, prefix, 0, "", "mangaName asc", emptyMap()).map {
                it.id
            },
        )
        assertTrue(downloadedSmangaShelf(entries, prefix, 99, "", "mangaName asc", emptyMap()).isEmpty())
    }

    @Test
    fun `cached catalogue backfills legacy downloads and sorts by server metadata`() {
        val entries = listOf(entry(1, null), entry(2, null), entry(3, 20))
        val cached = mapOf(
            1L to SmangaManga(1, 10, "Same", createdAt = 30, updatedAt = 40, sortName = "Z"),
            2L to SmangaManga(2, 10, "Same", createdAt = 40, updatedAt = 20, sortName = "A"),
        )
        fun result(order: String) = downloadedSmangaShelf(entries, prefix, 10, "tAg", order, cached)
        assertEquals(listOf(2L, 1L), result("mangaName asc").map { it.id })
        assertEquals(listOf(1L, 2L), result("updateTime desc").map { it.id })
        assertEquals(listOf(2L, 1L), result("createTime desc").map { it.id })
        assertTrue(result("mangaName asc").all { SMANGA_SHELF_METADATA in it.memo })
        assertTrue(downloadedSmangaShelf(entries, prefix, 10, "missing", "mangaName asc", cached).isEmpty())
        assertEquals(
            listOf(2L),
            downloadedSmangaShelf(entries.drop(1), prefix, 10, "", "mangaName asc", cached).map {
                it.id
            },
        )
    }

    @Test
    fun `backfill batches writes preserves unrelated metadata and does not overwrite newer ownership`() = runTest {
        val repository = mockk<MangaRepository>()
        val original = entry(1, null).copy(memo = JsonObject(mapOf("other" to JsonPrimitive("preserved"))))
        val newer = entry(2, 20, updated = 200)
        coEvery { repository.getMangaBySourceId(7) } returns listOf(original, newer)
        coEvery { repository.updateAll(any()) } answers {
            val updates = firstArg<List<tachiyomi.domain.manga.model.MangaUpdate>>()
            assertEquals(listOf(1L), updates.map { it.id })
            assertEquals(JsonPrimitive("preserved"), updates.single().memo!!["other"])
            true
        }
        persistSmangaDownloadedMetadata(repository, listOf(entry(1, 10), entry(2, 10, updated = 100))) {}
        coVerify(exactly = 1) { repository.getMangaBySourceId(7) }
        coVerify(exactly = 1) { repository.updateAll(any()) }
        persistSmangaDownloadedMetadata(repository, emptyList()) {}
        coVerify(exactly = 1) { repository.updateAll(any()) }
    }

    @Test
    fun `stale catalogue does not overwrite newer ownership and foreign memo is not adopted`() {
        val fresh = entry(1, 20, updated = 100)
        val forged = entry(2, null).copy(memo = fresh.memo)
        val old = mapOf(1L to SmangaManga(1, 10, "Old", updatedAt = 50))
        assertTrue(downloadedSmangaShelf(listOf(fresh, forged), prefix, 10, "", "mangaName asc", old).isEmpty())
        assertEquals(
            listOf(1L),
            downloadedSmangaShelf(listOf(fresh, forged), prefix, 20, "", "mangaName asc", old).map {
                it.id
            },
        )
    }
}
