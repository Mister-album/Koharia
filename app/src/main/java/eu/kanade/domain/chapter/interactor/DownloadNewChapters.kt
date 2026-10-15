package eu.kanade.domain.chapter.interactor

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.connection.FetchedChapters
import koharia.domain.chapter.interactor.FilterChaptersForDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** Automatic downloads retain the fetched entry instead of resolving the currently displayed one. */
class DownloadNewChapters(
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
) {
    suspend fun await(
        manga: Manga,
        fetched: FetchedChapters,
        chapters: List<Chapter>,
        autoStart: Boolean = true,
    ): Boolean {
        if (koharia.connection.DevReadOnlyValidation.enabled) return false
        val source = fetched.source
        require(source.id == manga.source && chapters.all { it.mangaId == manga.id })
        if (chapters.isEmpty()) return false
        if (fetched.origin != ChapterFetchOrigin.EXPLICIT_REFRESH &&
            fetched.origin != ChapterFetchOrigin.SERVER_UPDATE &&
            fetched.origin != ChapterFetchOrigin.LEGACY_UPDATE
        ) {
            return false
        }
        fetched.checkCurrent()
        checkSource(source)
        if (!source.allowsDownloads()) return false
        val current = getCurrentManga(manga) ?: return false
        val toDownload = filterChaptersForDownload.await(current, chapters)
        if (toDownload.isEmpty()) return false

        // Filtering suspends for category/read-state queries. Recheck removal, ownership and permission.
        val latest = getCurrentManga(manga) ?: return false
        currentCoroutineContext().ensureActive()
        fetched.checkCurrent()
        checkSource(source)
        if (!source.allowsDownloads()) return false
        downloadManager.downloadChapters(latest, toDownload, autoStart)
        return true
    }

    private suspend fun getCurrentManga(expected: Manga): Manga? = getManga.await(expected.id)?.takeIf {
        it.favorite && it.source == expected.source && it.url == expected.url
    }

    private fun checkSource(source: Source) {
        if (sourceManager.get(source.id) !== source) throw CancellationException("Download source replaced")
    }

    private fun Source.allowsDownloads(): Boolean = !isLocalOrStub() &&
        (this as? ConnectionMangaBehaviorAdapter)?.mangaBehavior?.allowsChapterDownloads != false
}
