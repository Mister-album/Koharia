package koharia.epub.session

import koharia.pdf.reflow.PdfReflowManifest
import logcat.LogPriority
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import tachiyomi.core.common.util.system.logcat

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
    private var closing = false
    private var closed = false
    private var releasing = false
    private var referenceCount = 0
    private val releaseCallbacks = mutableListOf<() -> Unit>()

    @Synchronized
    fun retain(): Boolean {
        if (closing) return false
        referenceCount++
        return true
    }

    fun release() {
        val shouldClose = synchronized(this) {
            check(referenceCount > 0)
            referenceCount--
            closing && referenceCount == 0
        }
        if (shouldClose) {
            runCatching(::closePublication).onFailure { error ->
                logcat(LogPriority.WARN, error) { "Failed to close EPUB navigator session" }
            }
        }
    }

    fun close() = close { }

    fun close(onReleased: () -> Unit) {
        val alreadyClosed = synchronized(this) {
            if (!closed) releaseCallbacks += onReleased
            closing = true
            closed
        }
        if (alreadyClosed) {
            onReleased()
        } else {
            closePublication()
        }
    }

    private fun closePublication() {
        synchronized(this) {
            if (closed || releasing || referenceCount > 0) return
            releasing = true
        }
        try {
            try {
                publication.close()
            } finally {
                onClosed()
            }
        } finally {
            val callbacks = synchronized(this) {
                closed = true
                releaseCallbacks.toList().also { releaseCallbacks.clear() }
            }
            callbacks.forEach { callback ->
                runCatching(callback).onFailure { error ->
                    logcat(LogPriority.WARN, error) { "Failed to release EPUB session resources" }
                }
            }
        }
    }
}
