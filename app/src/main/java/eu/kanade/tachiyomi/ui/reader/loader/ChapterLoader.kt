package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePolicy
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val manga: Manga,
    private val source: Source,
    private val epubCacheManager: EpubCacheManager = Injekt.get(),
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter, initialPageIndex: Int? = null) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val initialLoader = getPageLoader(chapter)
                chapter.pageLoader = initialLoader
                val (loader, loadedPages) = loadPagesWithCacheFallback(chapter, initialLoader)
                chapter.pageLoader = loader

                val pages = loadedPages
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (initialPageIndex != null) {
                    chapter.requestedPage = initialPageIndex
                } else if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
                loader.setActivePage(pages[chapter.requestedPage.coerceIn(0, pages.lastIndex)])
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    private suspend fun loadPagesWithCacheFallback(
        chapter: ReaderChapter,
        initialLoader: PageLoader,
    ): Pair<PageLoader, List<ReaderPage>> {
        val pages = try {
            initialLoader.getPages()
        } catch (error: Throwable) {
            if (error is CancellationException || initialLoader !is CompleteEpubCachePageLoader ||
                source !is HttpSource
            ) {
                throw error
            }
            logcat(LogPriority.WARN, error) {
                "Unable to read cached EPUB pages; falling back to the network"
            }
            return loadPagesFromNetwork(chapter, initialLoader, source)
        }

        if (pages.isEmpty() && initialLoader is CompleteEpubCachePageLoader && source is HttpSource) {
            logcat(LogPriority.WARN) {
                "Cached EPUB pages were rejected; falling back to the network"
            }
            return loadPagesFromNetwork(chapter, initialLoader, source)
        }
        return initialLoader to pages
    }

    private suspend fun loadPagesFromNetwork(
        chapter: ReaderChapter,
        cacheLoader: CompleteEpubCachePageLoader,
        httpSource: HttpSource,
    ): Pair<PageLoader, List<ReaderPage>> {
        cacheLoader.recycle()
        val networkLoader = HttpPageLoader(chapter, httpSource)
        chapter.pageLoader = networkLoader
        return networkLoader to networkLoader.getPages()
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            manga.source,
            skipCache = true,
        )
        val completeEpubCache = if (!isDownloaded) findCompleteEpubCache(dbChapter) else null
        logcat {
            "KohariaOfflineDebug: chapter loader selected " +
                "mangaId=${manga.id} mangaTitle=${manga.title} " +
                "chapterId=${dbChapter.id} chapterName=${dbChapter.name} " +
                "chapterUrl=${dbChapter.url} source=${source.name} " +
                "downloadedOnDisk=$isDownloaded completeEpubCache=${completeEpubCache != null}"
        }
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            completeEpubCache != null -> CompleteEpubCachePageLoader(
                file = completeEpubCache,
                cacheManager = epubCacheManager,
                expectedPageCount = checkNotNull(KomgaChapterMemo.pagesCount(dbChapter.memo)),
            )
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }

    private fun findCompleteEpubCache(chapter: eu.kanade.tachiyomi.data.database.models.Chapter): File? {
        val komgaSource = source as? KomgaSource ?: return null
        if (!KomgaChapterMemo.canOpenEpubAsPages(chapter.memo)) return null
        val fingerprint = KomgaChapterMemo.readFingerprint(chapter.memo)
        val publicationKey = EpubCachePolicy.publicationKey(
            fileHash = fingerprint?.fileHash,
            fileLastModified = KomgaChapterMemo.fileLastModified(chapter.memo),
            sizeBytes = fingerprint?.sizeBytes ?: 0L,
            fallback = "book:${chapter.id}:${chapter.url}",
        )
        return epubCacheManager.completeBookFile(komgaSource.id, publicationKey)
    }
}
