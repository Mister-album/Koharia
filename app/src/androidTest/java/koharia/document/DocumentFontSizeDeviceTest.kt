package koharia.document

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class DocumentFontSizeDeviceTest {
    @Test
    fun defaultAndReaderScaleUseScreenDensity() {
        val metrics = contextAt(1f).resources.displayMetrics
        val settings = DocumentRenderSettings()
        assertEquals(42f, settings.createTextPaint(metrics).textSize, 0.01f)
        assertEquals(63f, settings.copy(fontSizeScale = 1.5f).createTextPaint(metrics).textSize, 0.01f)
        assertEquals(2.625f, settings.createTextPaint(metrics).density, 0.01f)
    }

    @Test
    fun systemFontScalingUsesThePlatformSpConversion() {
        val settings = DocumentRenderSettings()
        val normal = settings.createTextPaint(contextAt(1f).resources.displayMetrics).textSize
        for (scale in listOf(1.3f, 2f)) {
            val metrics = contextAt(scale).resources.displayMetrics
            val actual = settings.createTextPaint(metrics).textSize
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics), actual, 0.01f)
            assertTrue("System font scale $scale must enlarge the glyphs", actual > normal)
        }
    }

    @Test
    fun txtBitmapUsesTheExpectedGlyphHeight() = assertRenderedGlyphHeight("txt")

    @Test
    fun mobiBitmapUsesTheExpectedGlyphHeight() = assertRenderedGlyphHeight("mobi")

    @Test
    fun largerFontReflowsPagesAndReturningToDefaultRestoresPagination() = runBlocking {
        val context = contextAt(1f)
        val file = File.createTempFile("font-pagination-", ".txt", context.cacheDir)
        try {
            file.writeText("A paragraph with enough words to test line wrapping and pagination.\n".repeat(200))
            TextDocumentEngine.open(
                context,
                checkNotNull(UniFile.fromFile(file)),
                DocumentRenderSettings(),
            ).use { session ->
                val reflowable = session as ReflowableDocumentSession
                reflowable.reflow(DocumentRenderSettings(fontSizeScale = 1.5f)).use { larger ->
                    assertTrue(larger.pageCount > session.pageCount)
                }
                reflowable.reflow(DocumentRenderSettings()).use { restored ->
                    assertEquals(session.pageCount, restored.pageCount)
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun assertRenderedGlyphHeight(extension: String) {
        val context = contextAt(1f)
        val text = "HHHH"
        val file = File.createTempFile("font-glyph-", ".$extension", context.cacheDir)
        try {
            if (extension == "mobi") {
                val bytes = text.encodeToByteArray()
                file.writeBytes(
                    ByteBuffer.allocate(110 + bytes.size).order(ByteOrder.BIG_ENDIAN).apply {
                        putShort(76, 2)
                        putInt(78, 94)
                        putInt(86, 110)
                        putShort(94, 1)
                        putInt(98, bytes.size)
                        putShort(102, 1)
                        position(110)
                        put(bytes)
                    }.array(),
                )
            } else {
                file.writeText(text)
            }
            val settings = DocumentRenderSettings(textColor = Color.BLACK, paragraphIndent = 0f)
            DocumentEngines.open(context, checkNotNull(UniFile.fromFile(file)), settings).use { session ->
                val bitmap = session.page(0).render()
                try {
                    val row = IntArray(bitmap.width)
                    var first = bitmap.height
                    var last = -1
                    for (y in 0 until bitmap.height) {
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        if (row.any { Color.red(it) < 128 }) {
                            first = minOf(first, y)
                            last = y
                        }
                    }
                    val bounds = Rect()
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 42f
                        typeface = settings.typeface
                    }.getTextBounds(text, 0, text.length, bounds)
                    assertTrue("Expected ink on the $extension page", last >= first)
                    assertEquals(
                        "Rendered $extension glyph height",
                        bounds.height().toFloat(),
                        (last - first + 1).toFloat(),
                        2f,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun contextAt(fontScale: Float): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                densityDpi = 420
                this.fontScale = fontScale
            },
        )
    }
}
