package koharia.kavita

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KavitaSeriesProgressTest {
    @Test
    fun `book progress counts completed chapters and ignores duplicate chapter ids`() {
        val progress = listOf(
            KavitaVolume(
                id = 1,
                chapters = listOf(
                    KavitaChapter(id = 10, pages = 100, pagesRead = 100),
                    KavitaChapter(id = 11, pages = 80, pagesRead = 20),
                ),
            ),
            KavitaVolume(
                id = 2,
                chapters = listOf(
                    KavitaChapter(id = 10, pages = 100, pagesRead = 100),
                    KavitaChapter(id = 12, pages = 60, pagesRead = 60),
                ),
            ),
        ).bookProgress()

        assertEquals(KavitaSeriesBookProgress(readCount = 2, totalCount = 3), progress)
    }
}
