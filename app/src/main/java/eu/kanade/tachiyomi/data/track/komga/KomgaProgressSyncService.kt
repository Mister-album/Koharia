package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.track.TrackerManager
import koharia.connection.ConnectionEpubProgressAdapter
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.domain.epub.interactor.GetEpubRemoteProgressCache
import koharia.domain.epub.interactor.UpsertEpubRemoteProgressCache
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val getEpubProgress: GetEpubProgress,
    private val getEpubRemoteProgressCache: GetEpubRemoteProgressCache,
    private val upsertEpubRemoteProgressCache: UpsertEpubRemoteProgressCache,
) {

    private val syncMutex = Mutex()

    suspend fun syncFromServer(manga: Manga) {
        syncMutex.withLock {
            if (!manga.isKomgaSeries()) return@withLock

            runCatchingCancellable {
                val remoteBooks = trackerManager.komga.api.getSeriesBookProgress(manga.url)
                applyRemoteProgress(
                    syncName = "series sync",
                    remoteBooks = remoteBooks,
                    localMangaBySeriesUrl = mapOf(manga.url to manga),
                )
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to sync Komga progress from server for mangaId=${manga.id}"
                }
            }
        }
    }

    suspend fun pullBookProgress(
        sourceId: Long,
        chapterUrl: String,
    ): KomgaApi.BookProgressSnapshot? {
        if (sourceManager.get(sourceId) !is KomgaSource || !chapterUrl.contains("/api/v1/books/")) return null
        return trackerManager.komga.api.getBookProgress(chapterUrl, sourceId)
    }

    /**
     * Applies a single book change received from Komga SSE. Keeping this path
     * separate from the full history scan avoids downloading every book when
     * only one book changed.
     */
    suspend fun syncBookProgress(
        sourceId: Long,
        chapterUrl: String,
    ) {
        syncMutex.withLock {
            if (sourceManager.get(sourceId) !is KomgaSource || !chapterUrl.contains("/api/v1/books/")) return@withLock

            runCatchingCancellable {
                val remote = trackerManager.komga.api.getBookProgress(chapterUrl, sourceId)
                    ?: return@runCatchingCancellable
                val seriesUrl = remote.seriesUrl ?: return@runCatchingCancellable
                val manga = mangaRepository.getMangaByUrlAndSourceId(seriesUrl, sourceId)
                    ?: run {
                        logcat(LogPriority.INFO) {
                            "Komga book sync: local series is not indexed sourceId=$sourceId seriesUrl=$seriesUrl"
                        }
                        return@runCatchingCancellable
                    }
                applyRemoteProgress(
                    syncName = "book sync",
                    remoteBooks = listOf(
                        KomgaApi.SeriesBookProgress(
                            seriesUrl = seriesUrl,
                            url = remote.url,
                            readProgress = remote.readProgress,
                            isEpub = remote.isEpub,
                            isDivinaCompatibleEpub = remote.isDivinaCompatibleEpub,
                        ),
                    ),
                    localMangaBySeriesUrl = mapOf(seriesUrl to manga),
                )

                if (remote.isEpub && remote.readProgress != null) {
                    syncBookEpubProgress(sourceId, manga, remote.url)
                }
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to sync Komga book progress sourceId=$sourceId chapterUrl=$chapterUrl"
                }
            }
        }
    }

    /** Refresh EPUB locator data for locally indexed Komga books during an explicit full refresh. */
    suspend fun syncEpubProgressFromServer(sourceId: Long) {
        syncMutex.withLock {
            val source = sourceManager.get(sourceId) as? ConnectionEpubProgressAdapter ?: return@withLock
            val remoteInProgressBookUrls = runCatchingCancellable {
                trackerManager.komga.api.getInProgressBookProgress(sourceId)
                    .asSequence()
                    .filter { it.isEpub }
                    .map { it.url.normalizedBookUrl() }
                    .toSet()
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to load active Komga EPUB progress sourceId=$sourceId"
                }
            }.getOrDefault(emptySet())

            mangaRepository.getMangaBySourceId(sourceId).forEach { manga ->
                val chapters = getChaptersByMangaId.await(manga.id)
                if (chapters.isEmpty()) return@forEach
                val localProgress = getEpubProgress.awaitByMangaId(manga.id)
                val remoteProgress = getEpubRemoteProgressCache.awaitByMangaId(manga.id)
                val remoteProgressByChapter = remoteProgress.associateBy(EpubRemoteProgressCache::chapterId)
                val localProgressChapterIds = localProgress
                    .filter { progress ->
                        val remote = remoteProgressByChapter[progress.chapterId]
                        remote == null || remote.hasProgress() || remote.checkedAt.before(progress.updatedAt)
                    }
                    .mapTo(mutableSetOf()) { it.chapterId }
                val remoteProgressChapterIds = remoteProgress
                    .filter(EpubRemoteProgressCache::hasProgress)
                    .mapTo(mutableSetOf(), EpubRemoteProgressCache::chapterId)
                val relevantChapters = selectRelevantKomgaEpubChapters(
                    chapters = chapters,
                    remoteInProgressBookUrls = remoteInProgressBookUrls,
                    localProgressChapterIds = localProgressChapterIds,
                    remoteProgressChapterIds = remoteProgressChapterIds,
                )
                if (relevantChapters.isEmpty()) return@forEach

                runCatchingCancellable {
                    source.syncMangaEpubProgress(
                        mangaId = manga.id,
                        chapters = relevantChapters,
                        force = true,
                    )
                }.onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "Failed to sync Komga EPUB progress sourceId=$sourceId mangaId=${manga.id}"
                    }
                }
            }
        }
    }

    suspend fun syncHistoryFromServer(includeCompleted: Boolean = false) {
        val activeServerId = komgaServerPreferences.activeServerId.get()
        if (activeServerId == KomgaServerPreferences.NO_ACTIVE_SERVER) return
        syncHistoryFromServer(activeServerId, includeCompleted)
    }

    suspend fun syncHistoryFromServer(sourceId: Long, includeCompleted: Boolean = false) {
        syncMutex.withLock {
            if (sourceManager.get(sourceId) !is KomgaSource) return@withLock
            runCatchingCancellable {
                val remoteBooks = trackerManager.komga.api.getInProgressBookProgress(
                    sourceId = sourceId,
                    includeCompleted = includeCompleted,
                )
                if (remoteBooks.isEmpty()) {
                    return@runCatchingCancellable
                }
                val remoteSeriesUrls = remoteBooks
                    .mapNotNull { it.seriesUrl }
                    .distinct()

                val localMangaBySeriesUrl = remoteSeriesUrls
                    .mapNotNull { seriesUrl ->
                        mangaRepository.getMangaByUrlAndSourceId(seriesUrl, sourceId)?.let { seriesUrl to it }
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
    }

    suspend fun pushPageProgress(
        sourceId: Long,
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) {
        if (sourceManager.get(sourceId) !is KomgaSource || !chapterUrl.contains("/api/v1/books/")) return
        if (totalPages <= 0) return

        runCatchingCancellable {
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
        val epubCacheClears = mutableListOf<EpubRemoteProgressCache>()
        val localEpubProgressByMangaId = mutableMapOf<Long, Map<Long, EpubProgress>>()
        val remoteEpubProgressByMangaId = mutableMapOf<Long, Map<Long, EpubRemoteProgressCache>>()
        val missingSeriesSamples = mutableListOf<String>()
        val missingChapterSamples = mutableListOf<String>()
        var matchedSeriesCount = 0
        var matchedChapterCount = 0
        var missingSeriesCount = 0
        var missingChapterCount = 0
        var refreshedChapterSets = 0

        remoteBooks.forEach { remote ->
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
            remote.readProgress?.readDate
                ?.let(::parseReadDate)
                ?.let { readAt ->
                    historyUpdates += HistoryUpdate(
                        chapterId = localChapter.id,
                        readAt = readAt,
                        sessionReadDuration = 0L,
                    )
                }

            remote.toChapterUpdate(localChapter)?.let(chapterUpdates::add)
            if (remote.isEpub && remote.readProgress == null) {
                val localEpubProgress = localEpubProgressByMangaId[localManga.id]
                    ?: getEpubProgress.awaitByMangaId(localManga.id)
                        .associateBy(EpubProgress::chapterId)
                        .also { localEpubProgressByMangaId[localManga.id] = it }
                val remoteEpubProgress = remoteEpubProgressByMangaId[localManga.id]
                    ?: getEpubRemoteProgressCache.awaitByMangaId(localManga.id)
                        .associateBy(EpubRemoteProgressCache::chapterId)
                        .also { remoteEpubProgressByMangaId[localManga.id] = it }
                if (hasEpubProgressToClear(localEpubProgress[localChapter.id], remoteEpubProgress[localChapter.id])) {
                    epubCacheClears += EpubRemoteProgressCache(
                        chapterId = localChapter.id,
                        mangaId = localManga.id,
                        bookUrl = remote.url.normalizedBookUrl(),
                        locatorJson = null,
                        progression = null,
                        positionIndex = null,
                        modifiedAt = null,
                        checkedAt = Date(),
                        serverDate = null,
                    )
                }
            }
        }

        if (chapterUpdates.isNotEmpty()) {
            updateChapter.awaitAll(chapterUpdates)
        }
        historyUpdates.forEach { upsertHistory.await(it) }
        epubCacheClears.forEach { upsertEpubRemoteProgressCache.await(it) }
        if (missingSeriesCount > 0 || missingChapterCount > 0 || chapterUpdates.isNotEmpty() ||
            historyUpdates.isNotEmpty() ||
            epubCacheClears.isNotEmpty() ||
            refreshedChapterSets > 0
        ) {
            logcat(LogPriority.INFO) {
                "Komga $syncName result: remoteBooks=${remoteBooks.size}, matchedSeries=$matchedSeriesCount, matchedChapters=$matchedChapterCount, missingSeries=$missingSeriesCount, missingChapters=$missingChapterCount, refreshedChapterSets=$refreshedChapterSets, chapterUpdates=${chapterUpdates.size}, historyUpdates=${historyUpdates.size}, epubCacheClears=${epubCacheClears.size}"
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
            runCatchingCancellable {
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

    private suspend fun syncBookEpubProgress(
        sourceId: Long,
        manga: Manga,
        chapterUrl: String,
    ) {
        val source = sourceManager.get(sourceId) as? ConnectionEpubProgressAdapter ?: return
        val chapter = getChaptersByMangaId.await(manga.id).firstOrNull { it.url == chapterUrl } ?: return
        runCatchingCancellable {
            source.refreshEpubProgress(manga.id, chapter)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) {
                "Failed to sync Komga EPUB book progression sourceId=$sourceId chapterUrl=$chapterUrl"
            }
        }
    }

    private data class LocalChapterLoadResult(
        val chapters: Map<String, Chapter>,
        val refreshed: Boolean,
    )
}

internal fun selectRelevantKomgaEpubChapters(
    chapters: List<Chapter>,
    remoteInProgressBookUrls: Set<String>,
    localProgressChapterIds: Set<Long>,
    remoteProgressChapterIds: Set<Long>,
): List<Chapter> {
    return chapters.filter { chapter ->
        val bookUrl = chapter.url.normalizedBookUrl()
        val hasRelevantProgress = bookUrl in remoteInProgressBookUrls ||
            chapter.id in localProgressChapterIds ||
            chapter.id in remoteProgressChapterIds
        val isKnownEpub = KomgaChapterMemo.isEpub(chapter.memo) == true || hasRelevantProgress
        isKnownEpub && hasRelevantProgress && (!chapter.read || bookUrl in remoteInProgressBookUrls)
    }
}

private fun EpubRemoteProgressCache.hasProgress(): Boolean =
    locatorJson != null || progression != null || positionIndex != null

internal fun hasEpubProgressToClear(
    local: EpubProgress?,
    remote: EpubRemoteProgressCache?,
): Boolean {
    return remote?.hasProgress() == true ||
        (local != null && (remote == null || remote.checkedAt.before(local.updatedAt)))
}

private fun String.normalizedBookUrl(): String = substringBefore('#').removeSuffix("/")

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

internal fun KomgaApi.SeriesBookProgress.toChapterUpdate(localChapter: Chapter): ChapterUpdate? {
    // Komga represents an unread book by omitting readProgress entirely.
    // Treat that as an explicit unread state so a server-side reset also
    // clears the local read flag and page position.
    val newRead = readProgress?.completed ?: false
    val newLastPageRead = if (readProgress == null) {
        0L
    } else {
        readProgress.page?.let { (it - 1).coerceAtLeast(0).toLong() }
    }
    val usesPageProgress = !isEpub ||
        isDivinaCompatibleEpub ||
        KomgaChapterMemo.canOpenEpubAsPages(localChapter.memo)
    val shouldUpdatePage = usesPageProgress &&
        newLastPageRead != null &&
        newLastPageRead != localChapter.lastPageRead

    return if (newRead != localChapter.read || shouldUpdatePage) {
        ChapterUpdate(
            id = localChapter.id,
            read = newRead,
            lastPageRead = newLastPageRead?.takeIf { usesPageProgress },
        )
    } else {
        null
    }
}

private fun MutableList<String>.addSample(value: String) {
    if (size < 3) {
        add(value)
    }
}
