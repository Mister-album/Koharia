package koharia.epub.session

import koharia.pdf.reflow.PdfReflowManifest
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.util.concurrent.atomic.AtomicBoolean

data class EpubReaderSession(
    val chapterId: Long,
    val title: String,
    val publication: Publication,
    val navigatorFactory: EpubNavigatorFactory,
    val initialLocator: Locator?,
    val positionsController: EpubPositionsController,
    val prefetchNextResource: suspend (Locator?) -> Unit = {},
    val pdfReflow: PdfReflowManifest? = null,
    val onClosed: () -> Unit = {},
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                publication.close()
            } finally {
                onClosed()
            }
        }
    }
}
