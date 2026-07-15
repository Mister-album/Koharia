package koharia.data.epub

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.epub.model.EpubPaginationCache
import koharia.domain.epub.repository.EpubPaginationCacheRepository
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import java.util.Date

class EpubPaginationCacheRepositoryImpl(
    private val database: Database,
) : EpubPaginationCacheRepository {

    override suspend fun getCache(
        chapterId: Long,
        publicationKey: String,
        layoutKey: String,
    ): EpubPaginationCache? {
        return withIOContext {
            database.epub_pagination_cacheQueries
                .getByKey(chapterId, publicationKey, layoutKey, ::mapCache)
                .awaitAsOneOrNull()
        }
    }

    override suspend fun upsertCache(cache: EpubPaginationCache) {
        withIOContext {
            try {
                database.epub_pagination_cacheQueries.upsert(
                    chapterId = cache.chapterId,
                    publicationKey = cache.publicationKey,
                    layoutKey = cache.layoutKey,
                    layoutSnapshotJson = cache.layoutSnapshotJson,
                    resourcePageCountsJson = cache.resourcePageCountsJson,
                    currentLocatorJson = cache.currentLocatorJson,
                    currentVisualPage = cache.currentVisualPage,
                    totalVisualPages = cache.totalVisualPages,
                    isComplete = cache.isComplete,
                    measuredResourceCount = cache.measuredResourceCount,
                    updatedAt = cache.updatedAt,
                )
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error) {
                    "Failed to upsert EPUB pagination cache for chapterId=${cache.chapterId}"
                }
            }
        }
    }

    override suspend fun trimCaches(chapterId: Long, keepCount: Long) {
        withIOContext {
            database.epub_pagination_cacheQueries.deleteOlderThanLatest(chapterId, keepCount)
        }
    }

    private fun mapCache(
        chapterId: Long,
        publicationKey: String,
        layoutKey: String,
        layoutSnapshotJson: String,
        resourcePageCountsJson: String,
        currentLocatorJson: String?,
        currentVisualPage: Long?,
        totalVisualPages: Long?,
        isComplete: Boolean,
        measuredResourceCount: Long,
        updatedAt: Date,
    ): EpubPaginationCache {
        return EpubPaginationCache(
            chapterId = chapterId,
            publicationKey = publicationKey,
            layoutKey = layoutKey,
            layoutSnapshotJson = layoutSnapshotJson,
            resourcePageCountsJson = resourcePageCountsJson,
            currentLocatorJson = currentLocatorJson,
            currentVisualPage = currentVisualPage,
            totalVisualPages = totalVisualPages,
            isComplete = isComplete,
            measuredResourceCount = measuredResourceCount,
            updatedAt = updatedAt,
        )
    }
}
