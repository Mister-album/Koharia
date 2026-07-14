package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class EpubPaginationModelsTest {

    @Test
    fun `layout key is stable for the same effective layout`() {
        val first = snapshot()
        val second = snapshot()

        assertEquals(first.json, second.json)
        assertEquals(first.key, second.key)
    }

    @Test
    fun `layout key changes with typography viewport and WebView`() {
        val original = snapshot()

        assertNotEquals(original.key, snapshot(fontSize = 1.25f).key)
        assertNotEquals(original.key, snapshot(paragraphSpacing = 0.5f).key)
        assertNotEquals(original.key, snapshot(verticalMargins = 1.3f).key)
        assertNotEquals(original.key, snapshot(viewportWidthPx = 1080).key)
        assertNotEquals(original.key, snapshot(webViewVersion = "2").key)
    }

    @Test
    fun `page count cache round trips normalized resource data`() {
        val counts = linkedMapOf(
            "item/chapter-1.xhtml" to 12,
            "item/chapter-2.xhtml" to 18,
        )

        assertEquals(counts, counts.toPageCountsJson().toPageCounts())
    }

    private fun snapshot(
        fontSize: Float = 1.0f,
        paragraphSpacing: Float = 0.0f,
        verticalMargins: Float = 1.0f,
        viewportWidthPx: Int = 720,
        webViewVersion: String = "1",
    ): EpubPaginationLayoutSnapshot {
        return EpubPaginationLayoutSnapshot(
            readingMode = "PAGINATED",
            pageDirection = "LEFT_TO_RIGHT",
            fontSize = fontSize,
            lineHeight = 1.2f,
            paragraphSpacing = paragraphSpacing,
            paragraphIndent = 2.0f,
            pageMargins = 1.0f,
            verticalMargins = verticalMargins,
            fontFamily = "ORIGINAL",
            publisherStyles = true,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = 1280,
            densityDpi = 420,
            fontScale = 1.0f,
            webViewVersion = webViewVersion,
        )
    }
}
