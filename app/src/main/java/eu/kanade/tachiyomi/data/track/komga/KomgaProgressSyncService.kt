package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.track.TrackerManager
import koharia.connection.ChapterFetchOrigin
import koharia.connection.fetchChapters
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.domain.epub.interactor.GetEpubRemoteProgressCache
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.komga.KomgaRemoteProgressChange
import koharia.domain.komga.KomgaRemoteProgressRepository
import koharia.domain.komga.KomgaRemoteProgressSnapshot
import koharia.komga.download.KomgaChapterMemo
import koharia.komga.progress.KomgaProgressOrdering
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaProgressSession
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

class KomgaProgressSyncService(
    private val trackerManager: TrackerManager,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val mangaRepository: MangaRepository,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val sourceManager: SourceManager,
    private val komgaServerPreferences: KomgaServerPreferences,
    private val getEpubProgress: GetEpubProgress,
    private val getEpubRemoteProgressCache: GetEpubRemoteProgressCache,
    private val remoteProgress: KomgaRemoteProgressRepository,
) {

    private val syncMutex = Mutex()
    private val pageWriteMutex = Mutex()

    suspend fun syncFromServer(manga: Manga) {
        syncMutex.withLock {
            if (!manga.isKomgaSeries()) return@withLock

            runCatchingCancellable {
                val source = sourceManager.get(manga.source) as? KomgaSource ?: return@runCatchingCancellable
                val session = captureKomgaProgressSession(source, sourceManager)
                loadLocalChapters(manga, session)
                val snapshot = remoteProgress.capture(manga.source, session.account.key, listOf(manga.id)) {
                    checkBackgroundCurrent(session)
                }
                val remoteBooks = trackerManager.komga.api.getSeriesBookProgress(manga.url, session)
                applyRemoteProgress(
                    syncName = "series sync",
                    remoteBooks = remoteBooks,
                    snapshot = snapshot,
                    session = session,
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
        ownerKey: String,
    ): KomgaApi.BookProgressSnapshot? {
        if (sourceManager.get(sourceId) !is KomgaSource || !chapterUrl.contains("/api/v1/books/")) return null
        return trackerManager.komga.api.getBookProgress(chapterUrl, sourceId, ownerKey)
    }

    internal suspend fun pullBookProgress(
        session: koharia.source.komga.KomgaProgressSession,
        chapterUrl: String,
        checkCurrent: suspend () -> Unit,
    ): KomgaApi.BookProgressSnapshot {
        val remote = trackerManager.komga.api.getBookProgress(chapterUrl, session, checkCurrent)
        checkCurrent()
        return remote
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
                val source = sourceManager.get(sourceId) as? KomgaSource ?: return@runCatchingCancellable
                val session = captureKomgaProgressSession(source, sourceManager)
                val key = KomgaProgressOrdering.key(sourceId, session.account.key, chapterUrl)
                var snapshot = remoteProgress.captureBook(key, chapterUrl, { checkBackgroundCurrent(session) })
                var remote = trackerManager.komga.api.getBookProgress(chapterUrl, session) {
                    checkBackgroundCurrent(session)
                }
                val seriesUrl = remote.seriesUrl ?: return@runCatchingCancellable
                val manga = mangaRepository.getMangaByUrlAndSourceId(seriesUrl, sourceId)
                    ?: return@runCatchingCancellable
                if (snapshot.chapters.isEmpty()) {
                    loadLocalChapters(manga, session)
                    snapshot = remoteProgress.captureBook(key, chapterUrl, { checkBackgroundCurrent(session) })
                    if (snapshot.chapters.isEmpty()) return@runCatchingCancellable
                    // The first response predates chapter materialization; fetch against the new snapshot.
                    remote = trackerManager.komga.api.getBookProgress(chapterUrl, session) {
                        checkBackgroundCurrent(session)
                    }
                }
                val applied = applyRemoteProgress(
                    syncName = "book sync",
                    remoteBooks = listOf(
                        KomgaApi.SeriesBookProgress(
                            seriesUrl = remote.seriesUrl,
                            url = remote.url,
                            readProgress = remote.readProgress,
                            isEpub = remote.isEpub,
                            isDivinaCompatibleEpub = remote.isDivinaCompatibleEpub,
                        ),
                    ),
                    snapshot = snapshot,
                    session = session,
                )
                if (applied.isNotEmpty() && remote.isEpub && remote.readProgress != null) {
                    syncBookEpubProgress(session, manga, remote.url)
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
            val source = sourceManager.get(sourceId) as? KomgaSource ?: return@withLock
            val session = captureKomgaProgressSession(source, sourceManager)
            val remoteInProgressBookUrls = runCatchingCancellable {
                trackerManager.komga.api.getInProgressBookProgress(session, includeCompleted = false)
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

                coroutineScope {
                    val semaphore = Semaphore(2)
                    relevantChapters.map { chapter ->
                        async {
                            semaphore.withPermit {
                                runCatchingCancellable {
                                    checkBackgroundCurrent(session)
                                    val captured = source.captureEpubProgressSession(chapter)
                                    checkBackgroundCurrent(session)
                                    captured.adapter.syncMangaEpubProgress(manga.id, listOf(chapter), force = true)
                                }.onFailure { error ->
                                    logcat(LogPriority.WARN, error) {
                                        "Failed to sync Komga EPUB progress sourceId=$sourceId mangaId=${manga.id}"
                                    }
                                }
                            }
                        }
                    }.awaitAll()
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
            val source = sourceManager.get(sourceId) as? KomgaSource ?: return@withLock
            runCatchingCancellable {
                val session = captureKomgaProgressSession(source, sourceManager)
                val mangas = mangaRepository.getMangaBySourceId(sourceId)
                var snapshot = remoteProgress.capture(sourceId, session.account.key, mangas.map { it.id }) {
                    checkBackgroundCurrent(session)
                }
                var remoteBooks = trackerManager.komga.api.getInProgressBookProgress(session, includeCompleted)
                val remoteSeriesUrls = remoteBooks.mapNotNull { it.seriesUrl }.toSet()
                val indexedMangaIds = snapshot.chapters.values.mapTo(mutableSetOf()) { it.chapter.mangaId }
                val missing = mangas.filter { it.url in remoteSeriesUrls && it.id !in indexedMangaIds }
                var materialized = false
                missing.forEach { manga ->
                    if (loadLocalChapters(manga, session).isNotEmpty()) materialized = true
                }
                if (materialized) {
                    snapshot = remoteProgress.capture(sourceId, session.account.key, mangas.map { it.id }) {
                        checkBackgroundCurrent(session)
                    }
                    remoteBooks = trackerManager.komga.api.getInProgressBookProgress(session, includeCompleted)
                }
                applyRemoteProgress("history sync", remoteBooks, snapshot, session)
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
        ownerKey: String,
    ) {
        val source = sourceManager.get(sourceId) as? KomgaSource ?: throw KomgaProgressOwnerChanged()
        val session = koharia.source.komga.captureKomgaProgressSession(source, sourceManager)
        if (session.account.key != ownerKey) throw KomgaProgressOwnerChanged()
        val ordering = uy.kohesive.injekt.Injekt.get<koharia.komga.progress.KomgaProgressOrdering>()
        val token = ordering.capture(koharia.komga.progress.KomgaProgressOrdering.key(sourceId, ownerKey, chapterUrl))
        ordering.remote(token, session.guard::requireCurrent) {
            pushPageProgress(session, chapterUrl, pageIndex, totalPages, token.revision) {
                session.guard.requireCurrent()
                ordering.checkCurrent(token)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun pushPageProgress(
        session: koharia.source.komga.KomgaProgressSession,
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        revision: Long,
        checkCurrent: suspend () -> Unit,
    ) {
        checkCurrent()
        uy.kohesive.injekt.Injekt.get<koharia.komga.progress.KomgaPageJournal>().wake(
            KomgaProgressOrdering.key(session.sourceId, session.account.key, chapterUrl),
        )
    }

    internal suspend fun retryPageProgress(
        sourceId: Long,
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        expectedPage: Int,
        expectedCompleted: Boolean,
        expectedDate: String?,
        localReadAt: Long,
        ownerKey: String?,
        revision: Long?,
        ordering: koharia.komga.progress.KomgaProgressOrdering,
    ): Boolean {
        if (ownerKey.isNullOrBlank()) throw KomgaProgressOwnerChanged()
        if (revision == null || revision < 0) throw koharia.komga.progress.KomgaProgressRevisionMissing()
        val token = koharia.komga.progress.KomgaProgressRevision(
            koharia.komga.progress.KomgaProgressOrdering.key(sourceId, ownerKey, chapterUrl),
            revision,
        )
        ordering.checkCurrent(token)
        if (koharia.connection.ConnectionRestoreState.isRestoring) {
            throw java.io.IOException("Restore in progress")
        }
        if (sourceManager.get(sourceId) !is KomgaSource) {
            if (uy.kohesive.injekt.Injekt.get<koharia.connection.ConnectionPreferences>()
                    .getProfiles().any { it.id == sourceId }
            ) {
                throw java.io.IOException("Connection is still loading")
            }
            throw KomgaProgressOwnerChanged()
        }
        val source = sourceManager.get(sourceId) as? KomgaSource ?: throw KomgaProgressOwnerChanged()
        val session = koharia.source.komga.captureKomgaProgressSession(source, sourceManager)
        if (session.account.key != ownerKey) throw KomgaProgressOwnerChanged()
        val checkCurrent: suspend () -> Unit = {
            session.guard.requireCurrent()
            ordering.checkCurrent(token)
        }
        return ordering.remote(token, session.guard::requireCurrent) {
            pageWriteMutex.withLock {
                checkCurrent()
                val remote = trackerManager.komga.api.getBookProgress(chapterUrl, session, checkCurrent)
                if (remote.totalPages != totalPages || totalPages <= 0) return@withLock true
                if (localReadAt > 0L) {
                    val remoteReadAt = remote.readDate?.let(::parseReadDate)?.time
                    val hasUnparseableRemoteDate = remote.readDate != null && remoteReadAt == null
                    if (hasUnparseableRemoteDate ||
                        !shouldUploadPendingKomgaPageProgress(
                            remotePage = remote.pageIndex,
                            remoteCompleted = remote.completed,
                            targetPage = pageIndex,
                            targetCompleted = pageIndex + 1 >= totalPages,
                            remoteReadAt = remoteReadAt,
                            localReadAt = localReadAt,
                        )
                    ) {
                        return@withLock true
                    }
                } else if (!canRetryKomgaPageProgress(remote, expectedPage, expectedCompleted, expectedDate)) {
                    return@withLock true
                }
                if (koharia.connection.ConnectionRestoreState.isRestoring) {
                    throw java.io.IOException("Restore in progress")
                }
                trackerManager.komga.api.updateBookProgress(
                    session,
                    chapterUrl,
                    pageIndex + 1,
                    pageIndex + 1 >= totalPages,
                    checkCurrent,
                )
                checkCurrent()
                true
            }
        }
    }

    private fun Manga.isKomgaSeries(): Boolean {
        return sourceManager.get(source) is KomgaSource && url.contains("/api/v1/series/")
    }

    private suspend fun applyRemoteProgress(
        syncName: String,
        remoteBooks: List<KomgaApi.SeriesBookProgress>,
        snapshot: KomgaRemoteProgressSnapshot,
        session: KomgaProgressSession,
    ): Set<Long> {
        checkBackgroundCurrent(session)
        val byUrl = snapshot.chapters.values.groupBy { it.chapter.url }
        val changes = remoteBooks.distinctBy { it.url }.flatMap { remote ->
            byUrl[remote.url].orEmpty().map { state ->
                val chapter = state.chapter
                val update = remote.toChapterUpdate(chapter)
                val cleared = if (remote.isEpub && remote.readProgress == null &&
                    hasEpubProgressToClear(state.epub, state.remoteEpub)
                ) {
                    EpubRemoteProgressCache(
                        chapterId = chapter.id,
                        mangaId = chapter.mangaId,
                        bookUrl = remote.url.normalizedBookUrl(),
                        locatorJson = null,
                        progression = null,
                        positionIndex = null,
                        modifiedAt = null,
                        checkedAt = Date(),
                        serverDate = null,
                    )
                } else {
                    null
                }
                KomgaRemoteProgressChange(
                    chapterId = chapter.id,
                    bookId = KomgaProgressOrdering.key(session.sourceId, session.account.key, remote.url).bookId,
                    read = update?.read,
                    lastPageRead = update?.lastPageRead,
                    readAt = remote.readProgress?.readDate?.let(::parseReadDate),
                    epubCache = cleared,
                )
            }
        }
        return remoteProgress.apply(snapshot, changes, { checkBackgroundCurrent(session) }).also { applied ->
            logcat(LogPriority.INFO) {
                "Komga $syncName: remoteBooks=${remoteBooks.size}, matched=${changes.size}, applied=${applied.size}"
            }
        }
    }

    private fun parseReadDate(value: String): Date? {
        return runCatching { Date.from(Instant.parse(value)) }
            .recoverCatching { Date.from(OffsetDateTime.parse(value).toInstant()) }
            .getOrNull()
    }

    private suspend fun loadLocalChapters(manga: Manga, session: KomgaProgressSession): List<Chapter> {
        checkBackgroundCurrent(session)
        var chapters = getChaptersByMangaId.await(manga.id)
        if (chapters.isEmpty()) {
            runCatchingCancellable {
                val sourceChapters = session.source.fetchChapters(
                    manga.toSManga(),
                    ChapterFetchOrigin.PROGRESS,
                    sourceManager = sourceManager,
                )
                checkBackgroundCurrent(session)
                syncChaptersWithSource.await(sourceChapters, manga, manualFetch = false)
                checkBackgroundCurrent(session)
                chapters = getChaptersByMangaId.await(manga.id)
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Komga history sync: failed to materialize chapters for mangaId=${manga.id}"
                }
            }
        }
        checkBackgroundCurrent(session)
        return chapters
    }

    private suspend fun syncBookEpubProgress(session: KomgaProgressSession, manga: Manga, chapterUrl: String) {
        val chapter = getChaptersByMangaId.await(manga.id).firstOrNull { it.url == chapterUrl } ?: return
        runCatchingCancellable {
            checkBackgroundCurrent(session)
            val captured = session.source.captureEpubProgressSession(chapter)
            checkBackgroundCurrent(session)
            captured.adapter.refreshEpubProgress(manga.id, chapter)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) {
                "Failed to sync Komga EPUB book progression sourceId=${session.sourceId}"
            }
        }
    }
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

internal fun canRetryKomgaPageProgress(
    remote: KomgaApi.BookProgressSnapshot,
    expectedPage: Int,
    expectedCompleted: Boolean,
    expectedDate: String?,
): Boolean = (remote.pageIndex ?: -1) == expectedPage && remote.completed == expectedCompleted &&
    (expectedDate == null || remote.readDate == expectedDate)

internal fun shouldUploadPendingKomgaPageProgress(
    remotePage: Int?,
    remoteCompleted: Boolean,
    targetPage: Int,
    targetCompleted: Boolean,
    remoteReadAt: Long?,
    localReadAt: Long,
): Boolean {
    if (remotePage == targetPage && remoteCompleted == targetCompleted) return false
    return remoteReadAt == null || localReadAt >= remoteReadAt
}

private fun checkBackgroundCurrent(session: KomgaProgressSession) {
    if (koharia.connection.ConnectionRestoreState.isRestoring) throw java.io.IOException("Restore in progress")
    session.guard.requireCurrent()
}
