package koharia.kavita

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KavitaChapterTitleTest {
    @Test fun keepsRangesAndSpecialTitlesAsText() {
        val memo = buildJsonObject {
            put("kavitaVolume", "01")
            put("kavitaRange", "1-3")
            put("kavitaTitle", "特别篇")
            put("kavitaFilename", "book.cbz")
        }
        assertEquals(
            "01 · 1-3 · 特别篇 (book.cbz)",
            KavitaChapterTitle.render("{volume} · {chapter} · {title} ({filename})", memo),
        )
        assertNull(KavitaChapterTitle.render("", memo))
        assertNull(KavitaChapterTitle.render("{title}", buildJsonObject {}))
    }

    @Test fun rejectsUnknownAndIncompleteTokens() {
        listOf("{unknown}", "{file_name}", "{title", "{{title}}", "}", "a".repeat(301)).forEach {
            assertFalse(KavitaChapterTitle.valid(it), it)
        }
    }
}
