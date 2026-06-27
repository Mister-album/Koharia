package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Loader used to load a chapter from a .pdf file.
 */
internal class PdfPageLoader(
    context: Context,
    file: UniFile,
) : PageLoader() {

    private val renderLock = Any()
    private val fileDescriptor: android.os.ParcelFileDescriptor
    private val renderer: PdfRenderer

    init {
        val fd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("Failed to open pdf file descriptor: ${file.uri}")

        try {
            fileDescriptor = fd
            renderer = PdfRenderer(fd)
        } catch (e: Throwable) {
            fd.close()
            throw e
        }
    }

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return List(renderer.pageCount) { index ->
            ReaderPage(index).apply {
                stream = { renderPage(index) }
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    private fun renderPage(index: Int): ByteArrayInputStream {
        check(!isRecycled)

        val imageBytes = synchronized(renderLock) {
            renderer.openPage(index).use { page ->
                val width = (page.width * RENDER_SCALE).roundToInt().coerceAtLeast(1)
                val height = (page.height * RENDER_SCALE).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        return ByteArrayInputStream(imageBytes)
    }

    override fun recycle() {
        super.recycle()
        synchronized(renderLock) {
            renderer.close()
            fileDescriptor.close()
        }
    }

    private companion object {
        const val RENDER_SCALE = 2f
    }
}
