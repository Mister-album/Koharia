package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.tachiyomi.data.track.TrackerManager
import logcat.LogPriority
import koharia.source.komga.KomgaSource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga

class KomgaProgressSyncService(
    private val trackerManager: TrackerManager,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun syncFromServer(manga: Manga) {
        if (!manga.isKomgaSeries()) return

        runCatching {
            val localChapters = getChaptersByMangaId.await(manga.id).associateBy { it.url }
            val chapterUpdates = trackerManager.komga.api.getSeriesBookProgress(manga.url)
                .mapNotNull { remote ->
                    val localChapter = localChapters[remote.url] ?: return@mapNotNull null
                    val remoteProgress = remote.readProgress ?: return@mapNotNull null
                    val newRead = remoteProgress.completed
                    val newLastPageRead = ((remoteProgress.page ?: 1) - 1).coerceAtLeast(0)

                    if (newRead == localChapter.read && newLastPageRead.toLong() == localChapter.lastPageRead) {
                        null
                    } else {
                        ChapterUpdate(
                            id = localChapter.id,
                            read = newRead,
                            lastPageRead = newLastPageRead.toLong(),
                        )
                    }
                }

            if (chapterUpdates.isNotEmpty()) {
                updateChapter.awaitAll(chapterUpdates)
            }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Failed to sync Komga progress from server for mangaId=${manga.id}" }
        }
    }

    suspend fun pushPageProgress(
        sourceId: Long,
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) {
        if (sourceId != KomgaSource.ID || !chapterUrl.contains("/api/v1/books/")) return
        if (totalPages <= 0) return

        runCatching {
            val page = (pageIndex + 1).coerceIn(1, totalPages)
            val completed = page >= totalPages
            trackerManager.komga.api.updateBookProgress(
                bookUrl = chapterUrl,
                page = page,
                completed = completed,
            )
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Failed to push Komga page progress for $chapterUrl" }
        }
    }

    private fun Manga.isKomgaSeries(): Boolean {
        return source == KomgaSource.ID && url.contains("/api/v1/series/")
    }
}
