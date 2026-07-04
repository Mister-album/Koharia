package tachiyomi.core.common.storage

import android.content.Context
import java.io.File

object LocalTempCacheDirectoryProvider {

    private const val ROOT_DIR = "local_cache"
    private const val NETWORK_CACHE_DIR = "network_cache"
    private const val CHAPTER_CACHE_DIR = "chapter_disk_cache"
    private const val METADATA_CACHE_DIR = "komga_metadata_cache"
    private const val DOWNLOAD_INDEX_FILE = "dl_index_cache_v3"
    private const val COIL_DISK_CACHE_DIR = "coil3_disk_cache"
    private const val SHARED_IMAGE_DIR = "shared_image"

    fun networkCacheDir(context: Context): File = prepareDirectory(context, NETWORK_CACHE_DIR)

    fun chapterCacheDir(context: Context): File = prepareDirectory(context, CHAPTER_CACHE_DIR)

    fun metadataCacheDir(context: Context): File = prepareDirectory(context, METADATA_CACHE_DIR)

    fun downloadIndexCacheFile(context: Context): File = prepareFile(context, DOWNLOAD_INDEX_FILE)

    fun coilDiskCacheDir(context: Context): File = prepareDirectory(context, COIL_DISK_CACHE_DIR)

    fun sharedImageDir(context: Context): File = prepareDirectory(context, SHARED_IMAGE_DIR)

    fun temporaryCacheSize(context: Context): Long {
        return temporaryCacheEntries(context)
            .distinctBy { it.absolutePath }
            .sumOf(::sizeOf)
    }

    fun clearMetadataCache(context: Context): Int {
        return clearCurrentAndLegacyDirectory(
            current = metadataCacheDir(context),
            legacy = legacyDirectory(context, METADATA_CACHE_DIR),
        )
    }

    fun clearCoilDiskCache(context: Context): Int {
        return clearCurrentAndLegacyDirectory(
            current = coilDiskCacheDir(context),
            legacy = legacyDirectory(context, COIL_DISK_CACHE_DIR),
        )
    }

    fun clearSharedImageCache(context: Context): Int {
        return clearCurrentAndLegacyDirectory(
            current = sharedImageDir(context),
            legacy = legacyDirectory(context, SHARED_IMAGE_DIR),
        )
    }

    fun clearLegacyChapterCache(context: Context): Int {
        val legacy = legacyDirectory(context, CHAPTER_CACHE_DIR)
        return if (legacy.absolutePath == chapterCacheDir(context).absolutePath) {
            0
        } else {
            clearDirectoryContents(legacy, recreate = false)
        }
    }

    fun countNetworkCacheFiles(context: Context): Int {
        return countFiles(networkCacheDir(context)) + countLegacyFiles(context, NETWORK_CACHE_DIR)
    }

    fun countDownloadIndexFiles(context: Context): Int {
        return countFiles(downloadIndexCacheFile(context)) + countLegacyFiles(context, DOWNLOAD_INDEX_FILE)
    }

    fun clearLegacyNetworkCache(context: Context): Int {
        val legacy = legacyDirectory(context, NETWORK_CACHE_DIR)
        return if (legacy.absolutePath == networkCacheDir(context).absolutePath) {
            0
        } else {
            clearDirectoryContents(legacy, recreate = false)
        }
    }

    fun clearLegacyDownloadIndexCache(context: Context): Int {
        val legacy = legacyFile(context, DOWNLOAD_INDEX_FILE)
        return if (legacy.absolutePath == downloadIndexCacheFile(context).absolutePath) {
            0
        } else {
            deleteEntry(legacy)
        }
    }

    private fun temporaryCacheEntries(context: Context): List<File> {
        return listOf(
            chapterCacheDir(context),
            metadataCacheDir(context),
            networkCacheDir(context),
            downloadIndexCacheFile(context),
            coilDiskCacheDir(context),
            sharedImageDir(context),
            legacyDirectory(context, CHAPTER_CACHE_DIR),
            legacyDirectory(context, METADATA_CACHE_DIR),
            legacyDirectory(context, NETWORK_CACHE_DIR),
            legacyFile(context, DOWNLOAD_INDEX_FILE),
            legacyDirectory(context, COIL_DISK_CACHE_DIR),
            legacyDirectory(context, SHARED_IMAGE_DIR),
        )
    }

    @Synchronized
    private fun prepareDirectory(context: Context, name: String): File {
        val target = File(rootDir(context), name)
        migrateDirectory(legacyDirectory(context, name), target)
        target.mkdirs()
        return target
    }

    @Synchronized
    private fun prepareFile(context: Context, name: String): File {
        val target = File(rootDir(context), name)
        target.parentFile?.mkdirs()
        migrateFile(legacyFile(context, name), target)
        return target
    }

    private fun rootDir(context: Context): File {
        return context.getExternalFilesDir(ROOT_DIR)
            ?: File(context.filesDir, ROOT_DIR).also { it.mkdirs() }
    }

    private fun legacyDirectory(context: Context, name: String): File = File(context.cacheDir, name)

    private fun legacyFile(context: Context, name: String): File = File(context.cacheDir, name)

    private fun migrateDirectory(source: File, target: File) {
        if (!source.exists() || source.absolutePath == target.absolutePath) return
        if (target.exists() && target.listFiles()?.isNotEmpty() == true) {
            source.deleteRecursively()
            return
        }

        target.mkdirs()
        source.walkTopDown().forEach { file ->
            val relativePath = file.relativeTo(source).path
            val destination = if (relativePath.isEmpty()) {
                target
            } else {
                File(target, relativePath)
            }
            if (file.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
        }
        source.deleteRecursively()
    }

    private fun migrateFile(source: File, target: File) {
        if (!source.exists() || source.absolutePath == target.absolutePath) return
        if (target.exists() && target.length() > 0L) {
            source.delete()
            return
        }

        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    private fun clearCurrentAndLegacyDirectory(current: File, legacy: File): Int {
        var deleted = clearDirectoryContents(current, recreate = true)
        if (legacy.absolutePath != current.absolutePath) {
            deleted += clearDirectoryContents(legacy, recreate = false)
        }
        return deleted
    }

    private fun countLegacyFiles(context: Context, name: String): Int {
        val legacy = legacyDirectory(context, name)
        val current = File(rootDir(context), name)
        return if (legacy.absolutePath == current.absolutePath) 0 else countFiles(legacy)
    }

    private fun sizeOf(file: File): Long {
        return when {
            !file.exists() -> 0L
            file.isDirectory -> file.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            else -> file.length()
        }
    }

    private fun countFiles(file: File): Int {
        return when {
            !file.exists() -> 0
            file.isDirectory -> file.walkTopDown().count { it.isFile }
            else -> 1
        }
    }

    private fun deleteEntry(file: File): Int {
        return when {
            !file.exists() -> 0
            file.isDirectory -> clearDirectoryContents(file, recreate = false)
            file.delete() -> 1
            else -> 0
        }
    }

    private fun clearDirectoryContents(directory: File, recreate: Boolean): Int {
        if (!directory.exists()) {
            if (recreate) {
                directory.mkdirs()
            }
            return 0
        }

        var deleted = 0
        directory.listFiles().orEmpty().forEach { file ->
            deleted += deleteEntry(file)
        }

        if (recreate) {
            directory.mkdirs()
        }
        return deleted
    }
}
