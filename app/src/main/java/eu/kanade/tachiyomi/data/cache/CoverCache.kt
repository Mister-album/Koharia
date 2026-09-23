package eu.kanade.tachiyomi.data.cache

import android.content.Context
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * Class used to create cover cache.
 * It is used to store the covers of the library.
 * Names of files are created with the md5 of the thumbnail URL.
 *
 * @param context the application context.
 * @constructor creates an instance of the cover cache.
 */
class CoverCache(private val context: Context) {

    companion object {
        private const val COVERS_DIR = "covers"
    }

    /**
     * Cache directory used for cache management.
     */
    private val cacheDir = getCacheDir(COVERS_DIR)

    /**
     * Returns the cover from cache.
     *
     * @param mangaThumbnailUrl thumbnail url for the manga.
     * @return cover image.
     */
    fun getCoverFile(mangaThumbnailUrl: String?): File? {
        return mangaThumbnailUrl?.let {
            File(cacheDir, DiskUtil.hashKeyForDisk(it))
        }
    }

    /**
     * Delete the cover files of the manga from the cache.
     *
     * @param manga the manga.
     * @return number of files that were deleted.
     */
    fun deleteFromCache(manga: Manga): Int {
        var deleted = 0

        getCoverFile(manga.thumbnailUrl)?.let {
            if (it.exists() && it.delete()) ++deleted
        }

        return deleted
    }

    fun deleteOrphaned(activeManga: Collection<Manga>): Int {
        val activeCoverNames = activeManga.mapNotNull { manga ->
            manga.thumbnailUrl?.let(DiskUtil::hashKeyForDisk)
        }.toSet()

        var deleted = 0
        cacheDir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name !in activeCoverNames && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    fun clear(): Int {
        return clearDirectory(cacheDir)
    }

    private fun getCacheDir(dir: String): File {
        return context.getExternalFilesDir(dir)
            ?: File(context.filesDir, dir).also { it.mkdirs() }
    }

    private fun clearDirectory(directory: File): Int {
        var deleted = 0
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.delete()) {
                deleted++
            }
        }
        return deleted
    }
}
