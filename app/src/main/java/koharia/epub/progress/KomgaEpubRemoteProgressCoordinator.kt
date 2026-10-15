package koharia.epub.progress

import koharia.connection.SharedAppPreferences
import koharia.domain.epub.interactor.GetEpubRemoteProgressCache
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.komga.KomgaRemoteProgressChange
import koharia.domain.komga.KomgaRemoteProgressRepository
import koharia.komga.download.KomgaChapterMemo
import koharia.komga.progress.KomgaProgressOrdering
import koharia.komga.progress.KomgaProgressSuperseded
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.chapter.model.Chapter
import java.util.Date

class KomgaEpubRemoteProgressCoordinator(
    private val syncService: KomgaEpubProgressSyncService,
    private val getCache: GetEpubRemoteProgressCache,
    private val remoteProgress: KomgaRemoteProgressRepository,
    private val sharedAppPreferences: SharedAppPreferences,
) {
    suspend fun syncManga(
        mangaId: Long,
        source: KomgaSource,
        chapters: List<Chapter>,
        force: Boolean = false,
        includeUnknownChapters: Boolean = false,
    ): List<EpubRemoteProgressCache> {
        if (sharedAppPreferences.basePreferences().incognitoMode.get()) return emptyList()
        return syncManga(mangaId, syncService.captureSession(source), chapters, force, includeUnknownChapters)
    }

    internal suspend fun syncManga(
        mangaId: Long,
        session: koharia.source.komga.KomgaProgressSession,
        chapters: List<Chapter>,
        force: Boolean = false,
        includeUnknownChapters: Boolean = false,
        checkCurrent: suspend () -> Unit = { requireActive(session) },
    ): List<EpubRemoteProgressCache> = coroutineScope {
        if (sharedAppPreferences.basePreferences().incognitoMode.get()) {
            return@coroutineScope emptyList()
        }
        checkCurrent()
        val existing = if (chapters.size == 1) {
            listOfNotNull(getCache.await(chapters.single().id))
        } else {
            getCache.awaitByMangaId(mangaId)
        }.associateBy { it.chapterId }
        checkCurrent()
        val now = System.currentTimeMillis()
        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        chapters.filter { chapter ->
            KomgaChapterMemo.isEpub(chapter.memo) == true ||
                existing.containsKey(chapter.id) ||
                includeUnknownChapters
        }.map { chapter ->
            async {
                semaphore.withPermit {
                    checkCurrent()
                    val bookUrl = KomgaChapterMemo.readFingerprint(chapter.memo)?.bookUrl
                        ?: chapter.url.substringBefore('#').removeSuffix("/")
                    val key = KomgaProgressOrdering.key(session.sourceId, session.account.key, bookUrl)
                    val snapshot = remoteProgress.captureChapter(key, chapter.id, checkCurrent)
                    val state = snapshot.chapters[chapter.id] ?: return@withPermit null
                    if (state.chapter.mangaId != mangaId || state.chapter.url != chapter.url ||
                        KomgaProgressOrdering.key(session.sourceId, session.account.key, state.chapter.url) != key
                    ) {
                        return@withPermit null
                    }
                    val cached = state.remoteEpub
                    val validateResponse: suspend () -> Unit = {
                        if (!remoteProgress.isCurrent(snapshot, chapter.id, key.bookId, checkCurrent)) {
                            throw KomgaProgressSuperseded()
                        }
                    }
                    if (!remoteProgress.isCurrent(
                            snapshot,
                            chapter.id,
                            key.bookId,
                            checkCurrent,
                        )
                    ) {
                        return@withPermit null
                    }
                    if (!force && cached != null && now - cached.checkedAt.time < CACHE_TTL_MS) {
                        return@withPermit cached
                    }
                    runCatching { syncService.pullProgression(session, bookUrl, validateResponse) }
                        .map { result ->
                            val remote = result.progression
                            val cache = EpubRemoteProgressCache(
                                chapterId = chapter.id,
                                mangaId = mangaId,
                                bookUrl = state.chapter.url.substringBefore('#').removeSuffix("/"),
                                locatorJson = remote?.locator?.toJSON()?.toString(),
                                progression = remote?.locator?.totalProgression(),
                                positionIndex = remote?.locator?.positionIndex(),
                                modifiedAt = remote?.modifiedAt,
                                checkedAt = Date(),
                                serverDate = result.serverDate,
                            )
                            val applied = remoteProgress.apply(
                                snapshot,
                                listOf(KomgaRemoteProgressChange(chapter.id, key.bookId, null, null, null, cache)),
                                checkCurrent,
                            )
                            cache.takeIf { chapter.id in applied }
                        }
                        .getOrElse { error ->
                            if (error is CancellationException || error is KomgaProgressOwnerChanged) throw error
                            checkCurrent()
                            cached.takeIf { remoteProgress.isCurrent(snapshot, chapter.id, key.bookId, checkCurrent) }
                        }
                }
            }
        }.awaitAll().filterNotNull().also { checkCurrent() }
    }

    suspend fun get(chapterId: Long, source: KomgaSource): EpubRemoteProgressCache? {
        return get(chapterId, syncService.captureSession(source))
    }

    internal suspend fun get(
        chapterId: Long,
        session: koharia.source.komga.KomgaProgressSession,
        checkCurrent: suspend () -> Unit = { requireActive(session) },
    ): EpubRemoteProgressCache? {
        checkCurrent()
        val cached = getCache.await(chapterId) ?: return null
        checkCurrent()
        val key = KomgaProgressOrdering.key(session.sourceId, session.account.key, cached.bookUrl)
        val snapshot = remoteProgress.captureChapter(key, chapterId, checkCurrent)
        val state = snapshot.chapters[chapterId] ?: return null
        if (KomgaProgressOrdering.key(session.sourceId, session.account.key, state.chapter.url) != key) return null
        return state.remoteEpub.takeIf { remoteProgress.isCurrent(snapshot, chapterId, key.bookId, checkCurrent) }
    }

    suspend fun refreshChapter(
        mangaId: Long,
        chapter: Chapter,
        source: KomgaSource,
    ): EpubRemoteProgressCache? = syncManga(
        mangaId = mangaId,
        source = source,
        chapters = listOf(chapter),
        force = true,
        includeUnknownChapters = true,
    ).firstOrNull()

    private fun Locator.totalProgression(): Double? =
        (locations.totalProgression as? Number)?.toDouble()

    private fun Locator.positionIndex(): Long? =
        (locations.position as? Number)?.toLong()

    private fun requireActive(session: koharia.source.komga.KomgaProgressSession) {
        if (koharia.connection.ConnectionRestoreState.isRestoring) throw java.io.IOException("Restore in progress")
        session.guard.requireCurrent()
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 2
        const val CACHE_TTL_MS = 60_000L
    }
}
