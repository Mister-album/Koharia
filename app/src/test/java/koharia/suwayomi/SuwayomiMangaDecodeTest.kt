package koharia.suwayomi

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every manga payload the app decodes is validated here. A field requested by a query but absent from
 * the model — or required by the model but absent from the response — makes the *whole* manga fail to
 * decode, which silently strips titles and covers from shelves and grids.
 */
class SuwayomiMangaDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a manga without a nested lastReadChapter decodes`() {
        val payload = """
            {"id":564,"title":"Series","sourceId":"10","genre":["Action"],"status":"ONGOING",
             "thumbnailUrl":"/api/v1/manga/564/thumbnail","inLibrary":true,"inLibraryAt":1,
             "categories":{"nodes":[]},"chapters":{"totalCount":67},"unreadCount":3,
             "downloadCount":0,"bookmarkCount":0,"meta":[]}
        """.trimIndent()

        val manga = json.decodeFromString<SuwayomiManga>(payload)

        assertEquals(564, manga.id)
        assertEquals("Series", manga.title)
        assertEquals(67, manga.chapters.totalCount)
    }

    /**
     * The shelf requests `lastReadChapter{id}` only. The nested chapter therefore arrives without the
     * fields the full chapter query selects, and must still decode.
     */
    @Test
    fun `a nested lastReadChapter with only an id decodes`() {
        val payload = """
            {"id":564,"title":"Series","sourceId":"10","chapters":{"totalCount":67},
             "unreadCount":3,"lastReadChapter":{"id":9001}}
        """.trimIndent()

        val manga = json.decodeFromString<SuwayomiManga>(payload)

        assertEquals(9001, manga.lastReadChapter?.id)
        // The owning manga is implied by the parent, so the field may be absent.
        assertEquals(0, manga.lastReadChapter?.mangaId)
        assertTrue(manga.libraryFacts.hasProgress)
    }

    @Test
    fun `a direct manga query decode with a partial nested chapter succeeds`() {
        // Mirrors `SuwayomiApi.manga(id)`, which selects the same fields as the shelf.
        val payload = """
            {"id":564,"title":"Series","sourceId":"10","chapters":{"totalCount":67},
             "unreadCount":3,"lastReadChapter":{"id":9001}}
        """.trimIndent()

        val manga = json.decodeFromString<SuwayomiManga>(payload)

        assertEquals(564, manga.id)
        assertEquals(9001, manga.lastReadChapter?.id)
    }

    @Test
    fun `every chapter field is optional except the identifier`() {
        val minimal = json.decodeFromString<SuwayomiChapter>("""{"id":5}""")

        assertEquals(5, minimal.id)
        assertEquals(0, minimal.mangaId)
        assertEquals("", minimal.name)
        assertEquals(-1f, minimal.chapterNumber)
    }

    /**
     * Guards the query against the model: the fields the shelf selects must all be decodable from a
     * response that omits the nullable ones, and the nested chapter must not require more than the
     * query asks for.
     */
    @Test
    fun `a response omitting the optional shelf fields decodes`() {
        val source = File("src/main/java/koharia/suwayomi/SuwayomiApi.kt").readText()
        val mangaFields = Regex("""MANGA_FIELDS = "([^"]+)"""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: error("MANGA_FIELDS not found")

        assertTrue(mangaFields.contains("lastReadChapter{id}"), "the shelf selects the last read chapter")

        // The minimum the server always returns, plus the nested chapter with only the id selected.
        val minimal = """
            {"id":1,"title":"t","sourceId":"10","status":"ONGOING","inLibrary":true,"inLibraryAt":0,
             "categories":{"nodes":[]},"chapters":{"totalCount":3},"unreadCount":0,
             "downloadCount":0,"bookmarkCount":0,"meta":[],"lastReadChapter":{"id":7}}
        """.trimIndent()

        val manga = json.decodeFromString<SuwayomiManga>(minimal)

        assertEquals(1, manga.id)
        assertEquals(7, manga.lastReadChapter?.id)
        assertTrue(manga.libraryFacts.hasProgress)
    }
}
