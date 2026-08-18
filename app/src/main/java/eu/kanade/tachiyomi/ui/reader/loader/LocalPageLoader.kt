package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.connection.ConnectionLocalFileAdapter
import koharia.core.archive.archiveReader
import koharia.core.archive.epubReader
import tachiyomi.core.common.storage.extension
import uy.kohesive.injekt.injectLazy

internal class LocalPageLoader(
    private val chapter: ReaderChapter,
    private val source: Source,
    private val fileAdapter: ConnectionLocalFileAdapter,
) : PageLoader() {

    private val context: Application by injectLazy()

    private var archiveLoader: ArchivePageLoader? = null
    private var epubLoader: EpubPageLoader? = null
    private var pdfLoader: PdfPageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val file = fileAdapter.localChapterFile(chapter.chapter.url)
            ?: error("Local chapter file is unavailable: ${chapter.chapter.url}")
        return when {
            file.isDirectory -> DirectoryPageLoader(file).getPages()
            file.extension.equals("epub", true) -> getPagesFromEpub(file)
            file.extension.equals("pdf", true) -> getPagesFromPdf(file)
            else -> getPagesFromArchive(file)
        }
    }

    override fun recycle() {
        super.recycle()
        archiveLoader?.recycle()
        epubLoader?.recycle()
        pdfLoader?.recycle()
    }

    override suspend fun loadPage(page: ReaderPage) {
        archiveLoader?.loadPage(page)
        epubLoader?.loadPage(page)
        pdfLoader?.loadPage(page)
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
