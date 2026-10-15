package koharia.connection

import io.mockk.every
import io.mockk.mockk
import koharia.komga.api.dto.SeriesDto
import koharia.komga.api.dto.SeriesMetadataDto
import koharia.komga.api.dto.toSManga
import koharia.smanga.SmangaApi
import koharia.smanga.SmangaManga
import koharia.source.smanga.SmangaSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionShelfUnitCountMappingTest {
    @Test
    fun `Komga and Smanga catalogue mappers retain empty totals after remote unit removal`() {
        val api = mockk<SmangaApi> { every { coverUrl(any()) } returns "http://fixture.invalid/cover" }
        val session = mockk<SmangaSource.Session> {
            every { mangaUrl(any()) } returns "/smanga/42/owned/manga/7"
            every { this@mockk.api } returns api
        }
        val source = mockk<SmangaSource> { every { toSManga(any(), any()) } answers { callOriginal() } }
        for (total in listOf(2, 0)) {
            val komga = SeriesDto(
                id = "7",
                libraryId = "1",
                name = "Series",
                fileLastModified = "2026-01-01T00:00:00Z",
                booksCount = total,
                metadata = SeriesMetadataDto(title = "Series"),
            ).toSManga("http://fixture.invalid")
            val smanga = source.toSManga(SmangaManga(7, 1, "Series", chapterCount = total), session)
            for (mapped in listOf(komga, smanga)) {
                val count = ConnectionSeriesMetadata.fromMemo(mapped.memo).booksCount
                assertEquals(total, count)
                val state = ConnectionShelfEntryState(downloaded = 2, total = 2, read = 0)
                assertEquals(
                    if (total == 0) MangaDownloadStatus.UNKNOWN_TOTAL else MangaDownloadStatus.COMPLETE,
                    state.downloads(count?.toLong()).status,
                )
            }
        }
    }
}
