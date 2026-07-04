package eu.kanade.tachiyomi.data.cache

import android.content.Context
import android.text.format.Formatter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.network.NetworkHelper
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import tachiyomi.domain.manga.model.Manga

class LocalCacheCleaner(
    private val context: Context,
    private val chapterCache: ChapterCache,
    private val coverCache: CoverCache,
    private val downloadCache: DownloadCache,
    private val networkHelper: NetworkHelper,
) {

    fun temporaryCacheReadableSize(): String {
        return Formatter.formatFileSize(context, LocalTempCacheDirectoryProvider.temporaryCacheSize(context))
    }

    fun clearDeletedMangaCache(activeManga: Collection<Manga>): Int {
        return coverCache.deleteOrphaned(activeManga)
    }

    fun clearChapterCache(): Int {
        return chapterCache.clear() + LocalTempCacheDirectoryProvider.clearLegacyChapterCache(context)
    }

    fun clearCoverCache(): Int {
        return coverCache.clear()
    }

    fun clearAllTemporaryCache(): Int {
        var deleted = 0
        deleted += clearChapterCache()
        deleted += networkHelper.clearDiskCache()
        deleted += LocalTempCacheDirectoryProvider.clearMetadataCache(context)
        deleted += downloadCache.clearDiskCache()
        deleted += LocalTempCacheDirectoryProvider.clearCoilDiskCache(context)
        deleted += LocalTempCacheDirectoryProvider.clearSharedImageCache(context)
        return deleted
    }
}
