package koharia.data.epub

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.epub.repository.EpubRemoteProgressCacheRepository
import tachiyomi.data.Database
import java.util.Date

class EpubRemoteProgressCacheRepositoryImpl(
    private val database: Database,
) : EpubRemoteProgressCacheRepository {
    override suspend fun getByChapterId(chapterId: Long): EpubRemoteProgressCache? =
        database.epub_remote_progress_cacheQueries.getByChapterId(chapterId, ::map).awaitAsOneOrNull()

    override suspend fun getByMangaId(mangaId: Long): List<EpubRemoteProgressCache> =
        database.epub_remote_progress_cacheQueries.getByMangaId(mangaId, ::map).awaitAsList()

    override suspend fun upsert(cache: EpubRemoteProgressCache) {
        database.epub_remote_progress_cacheQueries.upsert(
            chapterId = cache.chapterId,
            mangaId = cache.mangaId,
            bookUrl = cache.bookUrl,
            locatorJson = cache.locatorJson,
            progression = cache.progression,
            positionIndex = cache.positionIndex,
            modifiedAt = cache.modifiedAt,
            checkedAt = cache.checkedAt,
            serverDate = cache.serverDate,
        )
    }

    private fun map(
        chapterId: Long,
        mangaId: Long,
        bookUrl: String,
        locatorJson: String?,
        progression: Double?,
        positionIndex: Long?,
        modifiedAt: Date?,
        checkedAt: Date,
        serverDate: Date?,
    ) = EpubRemoteProgressCache(
        chapterId = chapterId,
        mangaId = mangaId,
        bookUrl = bookUrl,
        locatorJson = locatorJson,
        progression = progression,
        positionIndex = positionIndex,
        modifiedAt = modifiedAt,
        checkedAt = checkedAt,
        serverDate = serverDate,
    )
}
