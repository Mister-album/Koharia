package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.connection.ConnectionLocalFileAdapter
import koharia.core.archive.archiveReader
import koharia.core.archive.epubReader
import koharia.document.DocumentEngines
import koharia.document.DocumentHeading
import koharia.document.DocumentRenderSettings
import koharia.media.LocalMediaFormats
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

internal class LocalPageLoader(
    private val chapter: ReaderChapter,
    private val source: Source,
    private val fileAdapter: ConnectionLocalFileAdapter,
    private val documentSettingsProvider: () -> DocumentRenderSettings = { DocumentRenderSettings.DEFAULT },
) : PageLoader() {

    private val context: Application by injectLazy()

    private var archiveLoader: ArchivePageLoader? = null
    private var epubLoader: EpubPageLoader? = null
    private var pdfLoader: PdfPageLoader? = null
    private var documentLoader: DocumentPageLoader? = null
    private var comicPageCount: Int? = null

    override var isLocal: Boolean = true

    override val pdfFile: UniFile? get() = pdfLoader?.pdfFile

    override val supportsRemoteProgress: Boolean
        get() = documentLoader?.supportsRemoteProgress ?: true

    override val progressPageCount: Int?
        get() = pdfLoader?.progressPageCount ?: documentLoader?.progressPageCount ?: comicPageCount

    override val documentHeadings: List<DocumentHeading>
        get() = documentLoader?.documentHeadings ?: emptyList()

    override suspend fun getPages(): List<ReaderPage> {
        var stage = "resolve"
        var format: String? = null
        var sizeBytes: Long? = null
        try {
            val file = fileAdapter.localChapterFile(chapter.chapter.url)
                ?: error("Local chapter file is unavailable: ${chapter.chapter.url}")
            format = file.extension
            sizeBytes = file.length()
            stage = "enumerate"
            val pages = when {
                file.isDirectory -> DirectoryPageLoader(file).getPages()
                file.extension.equals("epub", true) -> getPagesFromEpub(file)
                file.extension.equals("pdf", true) -> getPagesFromPdf(file)
                DocumentEngines.forExtension(file.extension) != null -> getPagesFromDocument(file)
                LocalMediaFormats.isImage(file.extension) -> getPagesFromImage(file)
                else -> getPagesFromArchive(file)
            }
            if (file.isDirectory || LocalMediaFormats.isArchive(format) || LocalMediaFormats.isImage(format)) {
                comicPageCount = pages.size.takeIf { it > 0 }
            }
            return pages
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) {
                "Local media load failed: stage=$stage, format=$format, bytes=$sizeBytes"
            }
            throw error
        }
    }

    override fun recycle() {
        super.recycle()
        archiveLoader?.recycle()
        epubLoader?.recycle()
        pdfLoader?.recycle()
        documentLoader?.recycle()
    }

    override suspend fun loadPage(page: ReaderPage) {
        archiveLoader?.loadPage(page)
        epubLoader?.loadPage(page)
        pdfLoader?.loadPage(page)
        documentLoader?.loadPage(page)
    }

    override fun setActivePage(page: ReaderPage) {
        pdfLoader?.setActivePage(page)
    }

    override fun setActivePages(pages: List<ReaderPage>) {
        pdfLoader?.setActivePages(pages)
    }

    override fun onPageDisplayed(page: ReaderPage) {
        pdfLoader?.onPageDisplayed(page)
    }

    override fun onPagesDisplayed(pages: List<ReaderPage>) {
        pdfLoader?.onPagesDisplayed(pages)
    }

    override suspend fun refreshPages(): List<ReaderPage>? = documentLoader?.refreshPages()

    private fun getPagesFromImage(file: UniFile): List<ReaderPage> {
        return listOf(
            ReaderPage(0).apply {
                stream = { file.openInputStream() }
                status = Page.State.Ready
            },
        )
    }

    private suspend fun getPagesFromDocument(file: UniFile): List<ReaderPage> {
        return DocumentPageLoader(context, file, documentSettingsProvider).also { documentLoader = it }.getPages()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        return ArchivePageLoader(file.archiveReader(context)).also { archiveLoader = it }.getPages()
    }

    private suspend fun getPagesFromEpub(file: UniFile): List<ReaderPage> {
        return EpubPageLoader(file.epubReader(context)).also { epubLoader = it }.getPages()
    }

    private suspend fun getPagesFromPdf(file: UniFile): List<ReaderPage> {
        return PdfPageLoader(context, file).also { pdfLoader = it }.getPages()
    }
}
