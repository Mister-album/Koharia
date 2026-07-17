package koharia.epub.progress

import koharia.domain.epub.interactor.GetEpubRemoteProgressCache
import koharia.domain.epub.interactor.UpsertEpubRemoteProgressCache
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
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
    private val upsertCache: UpsertEpubRemoteProgressCache,
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory,
) {
    suspend fun syncManga(
        mangaId: Long,
        sourceId: Long,
        chapters: List<Chapter>,
        force: Boolean = false,
    ): List<EpubRemoteProgressCache> = coroutineScope {
        if (scopedPreferenceStoreFactory.basePreferences(sourceId).incognitoMode.get()) {
            return@coroutineScope emptyList()
        }
        val existing = getCache.awaitByMangaId(mangaId).associateBy { it.chapterId }
        val now = System.currentTimeMillis()
        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        chapters.filter { chapter ->
            KomgaChapterMemo.isEpub(chapter.memo) == true || existing.containsKey(chapter.id)
        }.map { chapter ->
            async {
                semaphore.withPermit {
                    val cached = existing[chapter.id]
                    if (!force && cached != null && now - cached.checkedAt.time < CACHE_TTL_MS) {
                        return@withPermit cached
                    }
                    val bookUrl = KomgaChapterMemo.readFingerprint(chapter.memo)?.bookUrl
                        ?: chapter.url.substringBefore('#').removeSuffix("/")
                    runCatching { syncService.pullProgression(sourceId, bookUrl) }
                        .map { result ->
                            val remote = result.progression
                            EpubRemoteProgressCache(
                                chapterId = chapter.id,
                                mangaId = mangaId,
                                bookUrl = bookUrl,
                                locatorJson = remote?.locator?.toJSON()?.toString(),
                                progression = remote?.locator?.totalProgression(),
                                positionIndex = remote?.locator?.positionIndex(),
                                modifiedAt = remote?.modifiedAt,
                                checkedAt = Date(),
                                serverDate = result.serverDate,
                            ).also { upsertCache.await(it) }
                        }
                        .getOrElse { cached }
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun get(chapterId: Long): EpubRemoteProgressCache? = getCache.await(chapterId)

    suspend fun refreshChapter(
        mangaId: Long,
        chapter: Chapter,
        sourceId: Long,
    ): EpubRemoteProgressCache? = syncManga(mangaId, sourceId, listOf(chapter), force = true).firstOrNull()

    private fun Locator.totalProgression(): Double? =
        (locations.totalProgression as? Number)?.toDouble()

    private fun Locator.positionIndex(): Long? =
        (locations.position as? Number)?.toLong()

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 2
        const val CACHE_TTL_MS = 60_000L
    }
}
