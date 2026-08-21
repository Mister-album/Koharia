package koharia.document

import android.graphics.Typeface
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DocumentRenderSettingsTest {

    @Test
    fun `document text uses a readable default base size`() {
        assertEquals(24f, DocumentRenderSettings.DEFAULT_BASE_FONT_SIZE_SP)
    }

    @Test
    fun `paint colors do not invalidate pagination layout`() {
        val settings = settings()
        val themed = settings.copy(backgroundColor = 0x123456, textColor = 0x654321)

        assertEquals(
            DocumentPaginationLayoutSnapshot(settings),
            DocumentPaginationLayoutSnapshot(themed),
        )
    }

    @Test
    fun `typography and publisher styles invalidate pagination layout`() {
        val settings = settings()

        assertNotEquals(
            DocumentPaginationLayoutSnapshot(settings),
            DocumentPaginationLayoutSnapshot(settings.copy(fontSizeScale = 1.25f)),
        )
        assertNotEquals(
            DocumentPaginationLayoutSnapshot(settings),
            DocumentPaginationLayoutSnapshot(settings.copy(publisherStyles = false)),
        )
    }

    private fun settings() = DocumentRenderSettings(typeface = mockk<Typeface>())
}
