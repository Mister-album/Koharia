package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.track.TrackerManager
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

class KomgaProgressSyncService(
    private val trackerManager: TrackerManager,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val upsertHistory: UpsertHistory,
    private val mangaRepository: MangaRepository,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val sourceManager: SourceManager,
    private val komgaServerPreferences: KomgaServerPreferences,
) {

    suspend fun syncFromServer(manga: Manga) {
        if (!manga.isKomgaSeries()) return

        runCatching {
            val remoteBooks = trackerManager.komga.api.getSeriesBookProgress(manga.url)
            applyRemoteProgress(
                syncName = "series sync",
                remoteBooks = remoteBooks,
                localMangaBySeriesUrl = mapOf(manga.url to manga),
            )
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Failed to sync Komga progress from server for mangaId=${manga.id}" }
        }
    }

    suspend fun syncHistoryFromServer() {
        runCatching {
            val activeServerId = komgaServerPreferences.activeServerId.get()
            if (activeServerId == KomgaServerPreferences.NO_ACTIVE_SERVER) return

            val remoteBooks = trackerManager.komga.api.getInProgressBookProgress(activeServerId)
            if (remoteBooks.isEmpty()) {
                return
            }
            val remoteSeriesUrls = remoteBooks
                .mapNotNull { it.seriesUrl }
                .distinct()

            val localMangaBySeriesUrl = remoteSeriesUrls
                .mapNotNull { seriesUrl ->
                    mangaRepository.getMangaByUrlAndSourceId(seriesUrl, activeServerId)?.let { seriesUrl to it }
                }
                .toMap()

            applyRemoteProgress(
                syncName = "history sync",
                remoteBooks = remoteBooks,
                localMangaBySeriesUrl = localMangaBySeriesUrl,
            )
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Failed to sync Komga continue reading from server" }
        }
    }

    suspend fun pushPageProgress(
        sourceId: Long,
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) {
        if (sourceManager.get(sourceId) !is KomgaSource || !chapterUrl.contains("/api/v1/books/")) return
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
        return sourceManager.get(source) is KomgaSource && url.contains("/api/v1/series/")
    }

    private suspend fun applyRemoteProgress(
        syncName: String,
        remoteBooks: List<KomgaApi.SeriesBookProgress>,
        localMangaBySeriesUrl: Map<String, Manga>,
    ) {
        val localChaptersByMangaId = mutableMapOf<Long, Map<String, Chapter>>()
        val historyUpdates = mutableListOf<HistoryUpdate>()
        val chapterUpdates = mutableListOf<ChapterUpdate>()
        val missingSeriesSamples = mutableListOf<String>()
        val missingChapterSamples = mutableListOf<String>()
        var matchedSeriesCount = 0
        var matchedChapterCount = 0
        var missingSeriesCount = 0
        var missingChapterCount = 0
        var refreshedChapterSets = 0

        remoteBooks.forEach { remote ->
            val remoteProgress = remote.readProgress ?: return@forEach
            val seriesUrl = remote.seriesUrl
            if (seriesUrl == null) {
                missingSeriesCount++
                missingSeriesSamples.addSample(remote.url)
                return@forEach
            }
            val localManga = localMangaBySeriesUrl[seriesUrl]
            if (localManga == null) {
                missingSeriesCount++
                missingSeriesSamples.addSample(seriesUrl)
                return@forEach
            }
            matchedSeriesCount++
            val localChapters = localChaptersByMangaId.getOrPut(localManga.id) {
                loadLocalChapters(localManga, seriesUrl).also { if (it.refreshed) refreshedChapterSets++ }.chapters
            }
            val localChapter = localChapters[remote.url]
            if (localChapter == null) {
                missingChapterCount++
                missingChapterSamples.addSample(remote.url)
                return@forEach
            }
            matchedChapterCount++
            val newRead = remoteProgress.completed
            val newLastPageRead = ((remoteProgress.page ?: 1) - 1).coerceAtLeast(0).toLong()

            remoteProgress.readDate
                ?.let(::parseReadDate)
                ?.let { readAt ->
                    historyUpdates += HistoryUpdate(
                        chapterId = localChapter.id,
                        readAt = readAt,
                        sessionReadDuration = 0L,
                    )
                }

            val shouldUpdatePage = !remote.isEpub &&
                newLastPageRead != localChapter.lastPageRead

            if (newRead != localChapter.read || shouldUpdatePage) {
                chapterUpdates += ChapterUpdate(
                    id = localChapter.id,
                    read = newRead,
                    lastPageRead = newLastPageRead.takeUnless { remote.isEpub },
                )
            }
        }

        if (chapterUpdates.isNotEmpty()) {
            updateChapter.awaitAll(chapterUpdates)
        }
        historyUpdates.forEach { upsertHistory.await(it) }
        if (missingSeriesCount > 0 || missingChapterCount > 0 || chapterUpdates.isNotEmpty() ||
            historyUpdates.isNotEmpty() ||
            refreshedChapterSets > 0
        ) {
            logcat(LogPriority.INFO) {
                "Komga $syncName result: remoteBooks=${remoteBooks.size}, matchedSeries=$matchedSeriesCount, matchedChapters=$matchedChapterCount, missingSeries=$missingSeriesCount, missingChapters=$missingChapterCount, refreshedChapterSets=$refreshedChapterSets, chapterUpdates=${chapterUpdates.size}, historyUpdates=${historyUpdates.size}"
            }
        }
        if (missingSeriesSamples.isNotEmpty()) {
            logcat(LogPriority.INFO) {
                "Komga $syncName: missing series samples=${missingSeriesSamples.joinToString()}"
            }
        }
        if (missingChapterSamples.isNotEmpty()) {
            logcat(LogPriority.INFO) {
                "Komga $syncName: missing chapter samples=${missingChapterSamples.joinToString()}"
            }
        }
        if (matchedChapterCount > 0 && historyUpdates.isEmpty()) {
            logcat(LogPriority.WARN) {
                "Komga $syncName: remote books were returned, but no local history rows were written"
            }
        }
    }

    private fun parseReadDate(value: String): Date? {
        return runCatching { Date.from(Instant.parse(value)) }
            .recoverCatching { Date.from(OffsetDateTime.parse(value).toInstant()) }
            .getOrNull()
    }

    private suspend fun loadLocalChapters(
        manga: Manga,
        seriesUrl: String,
    ): LocalChapterLoadResult {
        var chapters = getChaptersByMangaId.await(manga.id)
        var refreshed = false

        if (chapters.isEmpty()) {
            val source = sourceManager.getOrStub(manga.source)
            runCatching {
                val sourceChapters = source.getChapterList(manga.toSManga())
                syncChaptersWithSource.await(sourceChapters, manga, source, manualFetch = false)
                chapters = getChaptersByMangaId.await(manga.id)
                refreshed = chapters.isNotEmpty()
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Komga history sync: failed to refresh local chapters for mangaId=${manga.id} seriesUrl=$seriesUrl"
                }
            }
        }

        return LocalChapterLoadResult(
            chapters = chapters.associateBy { it.url },
            refreshed = refreshed,
        )
    }

    private data class LocalChapterLoadResult(
        val chapters: Map<String, Chapter>,
        val refreshed: Boolean,
    )
}

private fun MutableList<String>.addSample(value: String) {
    if (size < 3) {
        add(value)
    }
}
