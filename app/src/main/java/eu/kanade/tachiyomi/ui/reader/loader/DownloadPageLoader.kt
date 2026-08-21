package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.core.archive.archiveReader
import koharia.core.archive.epubReader
import koharia.document.DocumentEngines
import koharia.document.DocumentRenderSettings
import koharia.media.LocalMediaFormats
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val documentSettingsProvider: () -> DocumentRenderSettings = { DocumentRenderSettings.DEFAULT },
) : PageLoader() {

    private val context: Application by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null
    private var epubPageLoader: EpubPageLoader? = null
    private var pdfPageLoader: PdfPageLoader? = null
    private var documentPageLoader: DocumentPageLoader? = null

    override var isLocal: Boolean = true

    override val supportsRemoteProgress: Boolean
        get() = documentPageLoader?.supportsRemoteProgress ?: true

    override val progressPageCount: Int?
        get() = documentPageLoader?.progressPageCount

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        logcat {
            "KohariaOfflineDebug: using download page loader " +
                "mangaId=${manga.id} mangaTitle=${manga.title} " +
                "chapterId=${dbChapter.id} chapterName=${dbChapter.name} " +
                "chapterUrl=${dbChapter.url} " +
                "downloadPath=${chapterPath?.name ?: "<missing>"} " +
                "isFile=${chapterPath?.isFile}"
        }
        return if (chapterPath?.isFile == true) {
            when {
                chapterPath.extension.equals("epub", true) -> getPagesFromEpub(chapterPath)
                chapterPath.extension.equals("pdf", true) -> getPagesFromPdf(chapterPath)
                DocumentEngines.forExtension(chapterPath.extension) != null ->
                    getPagesFromDocument(chapterPath)
                LocalMediaFormats.isImage(chapterPath.extension) -> getPagesFromImage(chapterPath)
                else -> getPagesFromArchive(chapterPath)
            }
        } else {
            getPagesFromDirectory()
        }
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
        epubPageLoader?.recycle()
        pdfPageLoader?.recycle()
        documentPageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        return loader.getPages()
    }

    private suspend fun getPagesFromEpub(file: UniFile): List<ReaderPage> {
        val loader = EpubPageLoader(file.epubReader(context)).also { epubPageLoader = it }
        return loader.getPages()
    }

    private suspend fun getPagesFromPdf(file: UniFile): List<ReaderPage> {
        val loader = PdfPageLoader(context, file).also { pdfPageLoader = it }
        return loader.getPages()
    }

    private suspend fun getPagesFromDocument(file: UniFile): List<ReaderPage> {
        val loader = DocumentPageLoader(context, file, documentSettingsProvider).also { documentPageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromImage(file: UniFile): List<ReaderPage> {
        return listOf(
            ReaderPage(0).apply {
                stream = { file.openInputStream() }
                status = Page.State.Ready
            },
        )
    }

    private fun getPagesFromDirectory(): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
        epubPageLoader?.loadPage(page)
        pdfPageLoader?.loadPage(page)
        documentPageLoader?.loadPage(page)
    }

    override fun setActivePage(page: ReaderPage) {
        pdfPageLoader?.setActivePage(page)
    }

    override fun setActivePages(pages: List<ReaderPage>) {
        pdfPageLoader?.setActivePages(pages)
    }

    override fun onPageDisplayed(page: ReaderPage) {
        pdfPageLoader?.onPageDisplayed(page)
    }

    override fun onPagesDisplayed(pages: List<ReaderPage>) {
        pdfPageLoader?.onPagesDisplayed(pages)
    }

    override suspend fun refreshPages(): List<ReaderPage>? = documentPageLoader?.refreshPages()
}
