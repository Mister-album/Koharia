package koharia.suwayomi

import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionSeriesMetadata
import koharia.connection.ConnectionShelfEntryState
import koharia.connection.MangaDownloadStatus
import koharia.source.suwayomi.SuwayomiSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuwayomiShelfMetadataTest {
    private val owner = SuwayomiIdentity(42, "a".repeat(64))
    private val session = mockk<SuwayomiSource.Session> {
        every { identity } returns owner
    }
    private val source = mockk<SuwayomiSource> {
        every { id } returns owner.connectionId
        every { toSManga(any(), any()) } answers { callOriginal() }
        every { toManga(any(), any()) } answers { callOriginal() }
        every { mergeShelfLocalState(any(), any()) } answers { callOriginal() }
    }

    @Test
    fun `zero and absent source totals do not preserve an obsolete saved total`() {
        for (total in listOf(0, null)) {
            val remote = source.toManga(
                SuwayomiManga(7, "Series", chapters = SuwayomiNodes(totalCount = total)),
                session,
            )
            val local = remote.copy(memo = JsonObject(mapOf("booksCount" to JsonPrimitive(2))))
            assertEquals(total, ConnectionSeriesMetadata.fromMemo(remote.memo).booksCount)
            assertEquals(
                total,
                ConnectionSeriesMetadata.fromMemo(source.mergeShelfLocalState(remote, local).memo).booksCount,
            )
        }
    }

    @Test
    fun `source search retains remote unit total when old local chapters are all downloaded`() {
        val remote = SuwayomiManga(7, "Series", chapters = SuwayomiNodes(totalCount = 2), downloadCount = 100)
        val manga = source.toManga(remote, session)
        assertEquals(2, ConnectionSeriesMetadata.fromMemo(manga.memo).booksCount)
        assertEquals(2, ConnectionSeriesMetadata.fromMemo(source.toSManga(remote, session).memo).booksCount)
        val local = ConnectionShelfEntryState(downloaded = 1, total = 1, read = 0)
        val expected = ConnectionSeriesMetadata.fromMemo(manga.memo).booksCount?.toLong() ?: local.total
        assertEquals(MangaDownloadStatus.PARTIAL, local.downloads(expected).status)
        assertEquals(MangaDownloadStatus.NONE, local.copy(downloaded = 0).downloads(expected).status)
    }

    @Test
    fun `remote total reduction stays unknown with retained local units and keeps unrelated memo`() {
        val remote = source.toManga(SuwayomiManga(7, "Series", chapters = SuwayomiNodes(totalCount = 1)), session)
        val local = remote.copy(
            id = 10,
            favorite = true,
            memo = JsonObject(mapOf("readerSetting" to JsonPrimitive("preserved"), "booksCount" to JsonPrimitive(2))),
        )
        val merged = source.mergeShelfLocalState(remote, local)
        assertEquals(10L, merged.id)
        assertEquals(true, merged.favorite)
        assertEquals(JsonPrimitive("preserved"), merged.memo["readerSetting"])
        assertEquals(1, ConnectionSeriesMetadata.fromMemo(merged.memo).booksCount)
        val state = ConnectionShelfEntryState(downloaded = 2, total = 2, read = 0)
        assertEquals(MangaDownloadStatus.UNKNOWN_TOTAL, state.downloads(1).status)
    }
}
