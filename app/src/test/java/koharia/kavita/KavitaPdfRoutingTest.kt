package koharia.kavita

import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.every
import io.mockk.mockk
import koharia.source.kavita.KavitaSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KavitaPdfRoutingTest {
    private val source = mockk<KavitaSource> {
        every { isPdfChapter(any<String>()) } answers { firstArg<String>().endsWith(".pdf") }
        every { isPdfChapter(any<SChapter>()) } answers { callOriginal() }
    }

    @Test
    fun multiFilePdfUsesServerPagesAndSingleFilePdfKeepsDocumentReader() {
        val chapter = SChapter.create().apply {
            url = "chapter.pdf"
            memo = buildJsonObject { put("kavitaFileCount", 2) }
        }
        assertFalse(source.isPdfChapter(chapter))
        chapter.memo = buildJsonObject { put("kavitaFileCount", 1) }
        assertTrue(source.isPdfChapter(chapter))
        chapter.memo = buildJsonObject { }
        assertTrue(source.isPdfChapter(chapter))
        chapter.url = "chapter.cbz"
        assertFalse(source.isPdfChapter(chapter))
    }
}
