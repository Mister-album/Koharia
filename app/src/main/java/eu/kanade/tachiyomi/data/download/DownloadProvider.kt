package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import koharia.source.komga.DownloadDirectoryMode
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
class DownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val komgaServerPreferences: KomgaServerPreferences = Injekt.get(),
    private val komgaSharedDownloadIndexManager: KomgaSharedDownloadIndexManager = Injekt.get(),
) {
    private val sourceDirsCache = mutableMapOf<String, CacheEntry<List<UniFile>>>()

    private val disallowNonAsciiFilenames: Boolean
        get() = libraryPreferences.disallowNonAsciiFilenames.get()

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    internal fun getMangaDir(mangaTitle: String, source: Source): Result<UniFile> {
        val downloadsDir = downloadsDir
        if (downloadsDir == null) {
            logcat(LogPriority.ERROR) { "Failed to create download directory" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_download_directory)),
            )
        }

        val sourceDirName = getSourceDirName(source)
        val sourceDir = resolveSourceDir(downloadsDir, source, sourceDirName)
        if (sourceDir == null) {
            val displayablePath = downloadsDir.displayablePath + "/$sourceDirName"
            logcat(LogPriority.ERROR) { "Failed to create source download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        val mangaDirName = getMangaDirName(mangaTitle)
        val mangaDir = sourceDir.createDirectory(mangaDirName)
        if (mangaDir == null) {
            val displayablePath = sourceDir.displayablePath + "/$mangaDirName"
            logcat(LogPriority.ERROR) { "Failed to create manga download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        return Result.success(mangaDir)
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        return findSourceDirs(source)
            .firstOrNull()
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(mangaTitle: String, source: Source): UniFile? {
        val mangaDirName = getMangaDirName(mangaTitle)
        return findSourceDirs(source)
            .asSequence()
            .mapNotNull { it.findFile(mangaDirName) }
            .firstOrNull()
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    fun findChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): UniFile? {
        val mangaDir = findMangaDir(mangaTitle, source)
        val validNames = getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl)
        val directResult = validNames.asSequence()
            .mapNotNull { mangaDir?.findFile(it) }
            .firstOrNull()
        val result = directResult ?: when {
            source is KomgaSource &&
                komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared -> {
                komgaSharedDownloadIndexManager.findSharedChapterDir(chapterUrl, mangaTitle, source)
            }
            else -> null
        }

        logcat {
            buildString {
                append("KohariaOfflineDebug: download lookup ")
                append("source=${source.name} ")
                append("mangaTitle=$mangaTitle ")
                append("chapterName=$chapterName ")
                append("chapterUrl=$chapterUrl ")
                append("mangaDirName=${mangaDir?.name ?: "<missing>"} ")
                append("hit=${result?.name ?: "<none>"} ")
                append("directHit=${directResult?.name ?: "<none>"} ")
                append("candidates=${validNames.joinToString(limit = 8, truncated = "...")}")
                if (result == null) {
                    append(" hasMangaDir=${mangaDir != null}")
                }
            }
        }

        return result
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDirs(chapters: List<Chapter>, manga: Manga, source: Source): Pair<UniFile?, List<UniFile>> {
        val mangaDir = findMangaDir(manga.title, source)
        return mangaDir to chapters.mapNotNull { chapter ->
            findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
        }
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        val sourceName = when {
            source is KomgaSource &&
                komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared -> {
                KomgaSource.SOURCE_NAME
            }
            else -> source.toString()
        }
        return DiskUtil.buildValidFilename(
            sourceName,
            disallowNonAscii = disallowNonAsciiFilenames,
        )
    }

    fun getSourceDirNames(source: Source): List<String> {
        val primaryName = getSourceDirName(source)
        if (!shouldIncludeLegacySharedDirsInLookup(source)) {
            return listOf(primaryName)
        }

        return buildList {
            add(primaryName)
            addAll(legacyKomgaSharedSourceDirNames())
            add(legacyKomgaSourceDirName(source.name))
        }.distinct()
    }

    /**
     * Returns the download directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
    fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(
            mangaTitle,
            disallowNonAscii = disallowNonAsciiFilenames,
        )
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean = libraryPreferences.disallowNonAsciiFilenames.get(),
    ): String {
        var dirName = sanitizeChapterName(chapterName)
        if (!chapterScanlator.isNullOrBlank()) {
            dirName = chapterScanlator + "_" + dirName
        }
        // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
        dirName = DiskUtil.buildValidFilename(dirName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAsciiFilenames)
        dirName += "_" + md5(chapterUrl).take(6)
        return dirName
    }

    /**
     * Returns list of names that might have been previously used as
     * the directory name for a chapter.
     * Add to this list if naming pattern ever changes.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    private fun getLegacyChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
    ): List<String> {
        val sanitizedChapterName = sanitizeChapterName(chapterName)
        val chapterNameV1 = DiskUtil.buildValidFilename(
            when {
                !chapterScanlator.isNullOrBlank() -> "${chapterScanlator}_$sanitizedChapterName"
                else -> sanitizedChapterName
            },
        )

        // Get the filename that would be generated if the user were
        // using the other value for the disallow non-ASCII
        // filenames setting. This ensures that chapters downloaded
        // before the user changed the setting can still be found.
        val otherChapterDirName = getChapterDirName(
            chapterName,
            chapterScanlator,
            chapterUrl,
            !disallowNonAsciiFilenames,
        )

        return buildList(2) {
            // Chapter name without hash (unable to handle duplicate
            // chapter names)
            add(chapterNameV1)
            add(otherChapterDirName)
        }
    }

    /**
     * Return the new name for the chapter (in case it's empty or blank)
     *
     * @param chapterName the name of the chapter
     */
    private fun sanitizeChapterName(chapterName: String): String {
        return chapterName.ifBlank {
            "Chapter"
        }
    }

    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean {
        return getChapterDirName(oldChapter.name, oldChapter.scanlator, oldChapter.url) !=
            getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * @param chapter the domain chapter object.
     */
    fun getValidChapterDirNames(chapterName: String, chapterScanlator: String?, chapterUrl: String): List<String> {
        val chapterDirName = getChapterDirName(chapterName, chapterScanlator, chapterUrl)
        val legacyChapterDirNames = getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl)

        return buildList {
            // Folder of images
            add(chapterDirName)
            // Downloaded chapter files
            SUPPORTED_CHAPTER_FILE_EXTENSIONS.forEach { extension ->
                add("$chapterDirName.$extension")
            }

            // any legacy names
            legacyChapterDirNames.forEach {
                add(it)
                SUPPORTED_CHAPTER_FILE_EXTENSIONS.forEach { extension ->
                    add("$it.$extension")
                }
            }
        }
    }

    companion object {
        val SUPPORTED_CHAPTER_FILE_EXTENSIONS =
            setOf("cbz", "zip", "rar", "cbr", "7z", "cb7", "tar", "cbt", "epub", "pdf")

        private const val CACHE_TTL_MS = 10_000L

        fun isSupportedChapterFileExtension(extension: String?): Boolean {
            return extension?.lowercase() in SUPPORTED_CHAPTER_FILE_EXTENSIONS
        }
    }

    private fun findSourceDirs(source: Source): List<UniFile> {
        val downloadsDir = downloadsDir ?: return emptyList()
        val cacheKey = buildSourceDirsCacheKey(downloadsDir, source)
        return synchronized(sourceDirsCache) {
            sourceDirsCache[cacheKey]
                ?.takeUnless { it.isExpired() }
                ?.value
                ?: getSourceDirNames(source)
                    .mapNotNull(downloadsDir::findFile)
                    .distinctBy { it.uri.toString() }
                    .also { dirs ->
                        sourceDirsCache[cacheKey] = CacheEntry(dirs)
                    }
        }
    }

    private fun resolveSourceDir(downloadsDir: UniFile, source: Source, sourceDirName: String): UniFile? {
        downloadsDir.findFile(sourceDirName)?.let { return it }

        val legacyDir = when {
            source is KomgaSource &&
                komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared -> {
                val legacyDirs = legacyKomgaSharedSourceDirNames()
                    .mapNotNull(downloadsDir::findFile)
                    .distinctBy { it.uri.toString() }
                legacyDirs.singleOrNull()
            }
            else -> null
        }

        if (legacyDir != null) {
            if (legacyDir.name != sourceDirName && legacyDir.renameTo(sourceDirName)) {
                downloadsDir.findFile(sourceDirName)?.let { migratedDir ->
                    logcat(LogPriority.INFO) {
                        "Migrated legacy Komga download directory from ${legacyDir.name} to $sourceDirName"
                    }
                    return migratedDir
                }
            }

            logcat(LogPriority.INFO) {
                "Using legacy Komga download directory ${legacyDir.name} for source ${source.name}"
            }
            return legacyDir
        }

        return downloadsDir.createDirectory(sourceDirName)
            ?.also { invalidateSourceDirCache() }
    }

    private fun legacyKomgaSharedSourceDirNames(): List<String> {
        return buildList {
            add(legacyKomgaSourceDirName(KomgaSource.SOURCE_NAME))
            komgaServerPreferences.getProfiles().forEach { profile ->
                add(legacyKomgaSourceDirName(profile.name))
            }
        }.distinct()
    }

    private fun legacyKomgaSourceDirName(sourceName: String): String {
        return DiskUtil.buildValidFilename(
            "$sourceName (${KomgaSource.SOURCE_LANG.uppercase()})",
            disallowNonAscii = disallowNonAsciiFilenames,
        )
    }

    private fun shouldIncludeLegacySharedDirsInLookup(source: Source): Boolean {
        return source is KomgaSource &&
            komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared
    }

    private fun buildSourceDirsCacheKey(downloadsDir: UniFile, source: Source): String {
        return "${downloadsDir.uri}|${getSourceDirNames(source).joinToString(separator = "|")}"
    }

    private fun invalidateSourceDirCache() {
        synchronized(sourceDirsCache) {
            sourceDirsCache.clear()
        }
    }

    private data class CacheEntry<T>(
        val value: T,
        val cachedAt: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - cachedAt >= CACHE_TTL_MS
        }
    }
}
