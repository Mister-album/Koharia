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
        return findMangaDirs(mangaTitle, source).firstOrNull()
    }

    fun findMangaDirs(mangaTitle: String, source: Source): List<UniFile> {
        val mangaDirName = getMangaDirName(mangaTitle)
        return findSourceDirs(source)
            .asSequence()
            .mapNotNull { it.findFile(mangaDirName) }
            .distinctBy { it.uri.toString() }
            .toList()
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
    fun findChapterDirs(chapters: List<Chapter>, manga: Manga, source: Source): Pair<List<UniFile>, List<UniFile>> {
        val mangaDirs = findMangaDirs(manga.title, source)
        val chapterDirs = chapters.flatMap { chapter ->
            val validNames = getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
            val directMatches = mangaDirs.flatMap { mangaDir ->
                validNames.mapNotNull(mangaDir::findFile)
            }
            directMatches.ifEmpty {
                listOfNotNull(findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source))
            }
        }.distinctBy { it.uri.toString() }
        return mangaDirs to chapterDirs
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return when {
            isKomgaSource(source) &&
                komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared -> {
                getKomgaSharedDirName()
            }
            isKomgaSource(source) -> getKomgaServerDirName(source.name)
            else -> DiskUtil.buildValidFilename(
                source.toString(),
                disallowNonAscii = disallowNonAsciiFilenames,
            )
        }
    }

    fun getSourceDirNames(source: Source): List<String> {
        val primaryName = getSourceDirName(source)
        if (!isKomgaSource(source)) {
            return listOf(primaryName)
        }

        return buildList {
            add(primaryName)
            if (shouldIncludeLegacySharedDirsInLookup(source)) {
                addAll(legacyKomgaSharedSourceDirNames(source))
            } else {
                // Mihon-style source directories included the language suffix. Keep them in
                // lookup until they can be atomically renamed to the sanitized server name.
                addAll(legacyKomgaSourceDirNames(source.name))
                komgaServerPreferences.getDirectoryAliases(source.id).forEach { alias ->
                    add(getKomgaServerDirName(alias))
                    addAll(legacyKomgaSourceDirNames(alias))
                }
                // Downloads created while shared mode was active remain readable after
                // switching back to per-server directories, but are never used for new files.
                add(getKomgaSharedDirName())
            }
        }.distinct()
    }

    fun getKomgaServerDirName(serverName: String): String {
        // Server directories must remain stable if the separate non-ASCII filename preference
        // changes. FAT-invalid characters are still replaced by DiskUtil.
        val sanitizedName = DiskUtil.buildValidFilename(serverName)
        return if (sanitizedName.equals(getKomgaSharedDirName(), ignoreCase = true)) {
            DiskUtil.buildValidFilename("$serverName (Server)")
        } else {
            sanitizedName
        }
    }

    private fun getKomgaSharedDirName(): String {
        return DiskUtil.buildValidFilename("${KomgaSource.SOURCE_NAME} (Shared)")
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

    internal fun findSourceDirs(source: Source): List<UniFile> {
        val downloadsDir = downloadsDir ?: return emptyList()
        val cacheKey = buildSourceDirsCacheKey(downloadsDir, source)
        return synchronized(sourceDirsCache) {
            sourceDirsCache[cacheKey]
                ?.takeUnless { it.isExpired() }
                ?.value
                ?: findExistingSourceDirs(downloadsDir, source)
                    .also { dirs ->
                        sourceDirsCache[cacheKey] = CacheEntry(dirs)
                    }
        }
    }

    internal fun findOwnedSourceDirs(source: Source): List<UniFile> {
        val ownedNames = buildList {
            add(getSourceDirName(source))
            if (isKomgaSource(source) &&
                komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.PerServer
            ) {
                addAll(legacyKomgaSourceDirNames(source.name))
                komgaServerPreferences.getDirectoryAliases(source.id).forEach { alias ->
                    add(getKomgaServerDirName(alias))
                    addAll(legacyKomgaSourceDirNames(alias))
                }
            }
        }.toSet()
        return findSourceDirs(source).filter { it.name in ownedNames }
    }

    private fun findExistingSourceDirs(downloadsDir: UniFile, source: Source): List<UniFile> {
        val names = getSourceDirNames(source)
        val primaryName = names.first()
        val primaryDir = downloadsDir.findFile(primaryName)
        if (
            primaryDir == null &&
            isKomgaSource(source) &&
            komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.PerServer
        ) {
            val legacyDir = names.drop(1)
                .mapNotNull(downloadsDir::findFile)
                .distinctBy { it.uri.toString() }
                .singleOrNull()
            val legacyName = legacyDir?.name
            if (legacyDir != null && legacyDir.renameTo(primaryName)) {
                downloadsDir.findFile(primaryName)?.let { migratedDir ->
                    logcat(LogPriority.INFO) {
                        "Migrated legacy Komga server directory from $legacyName to $primaryName"
                    }
                    return listOf(migratedDir)
                }
            }
        }

        return names
            .mapNotNull(downloadsDir::findFile)
            .distinctBy { it.uri.toString() }
    }

    private fun resolveSourceDir(downloadsDir: UniFile, source: Source, sourceDirName: String): UniFile? {
        downloadsDir.findFile(sourceDirName)?.let { return it }

        val legacyDirNames = when {
            !isKomgaSource(source) -> emptyList()
            komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared ->
                legacyKomgaSharedSourceDirNames(source)
            else -> legacyKomgaSourceDirNames(source.name)
        }
        val legacyDir = legacyDirNames
            .mapNotNull(downloadsDir::findFile)
            .distinctBy { it.uri.toString() }
            .singleOrNull()

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

    fun migrateLegacyKomgaDirectories(): Result<Boolean> = runCatching {
        val downloadsDir = downloadsDir ?: throw IOException("Downloads directory is unavailable")
        var migrated = false
        val sharedName = getKomgaSharedDirName()
        val legacySharedName = DiskUtil.buildValidFilename(KomgaSource.SOURCE_NAME)
        if (downloadsDir.findFile(sharedName) == null) {
            downloadsDir.findFile(legacySharedName)?.let { legacySharedDir ->
                if (!legacySharedDir.renameTo(sharedName)) {
                    throw IOException("Failed to migrate shared Komga directory: $legacySharedName")
                }
                migrated = true
                logcat(LogPriority.INFO) {
                    "Migrated shared Komga directory from $legacySharedName to $sharedName"
                }
            }
        }

        if (komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.PerServer) {
            komgaServerPreferences.getProfiles().forEach { profile ->
                migrated = migrateLegacyKomgaServerDir(
                    downloadsDir = downloadsDir,
                    source = KomgaSource(profile.id, profile.name),
                ) || migrated
            }
        }

        if (migrated) invalidateSourceDirCache()
        migrated
    }

    private fun migrateLegacyKomgaServerDir(downloadsDir: UniFile, source: KomgaSource): Boolean {
        val primaryName = getKomgaServerDirName(source.name)
        if (downloadsDir.findFile(primaryName) != null) {
            return false
        }

        val legacyDir = legacyKomgaSourceDirNames(source.name)
            .mapNotNull(downloadsDir::findFile)
            .distinctBy { it.uri.toString() }
            .singleOrNull() ?: return false
        val legacyName = legacyDir.name
        if (!legacyDir.renameTo(primaryName)) {
            throw IOException("Failed to migrate legacy Komga server directory ${legacyDir.name} to $primaryName")
        }

        logcat(LogPriority.INFO) {
            "Migrated legacy Komga server directory from $legacyName to $primaryName"
        }
        return true
    }

    fun renameKomgaServerDir(source: KomgaSource, newServerName: String): Result<UniFile?> = runCatching {
        if (komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared) {
            return@runCatching null
        }

        val downloadsDir = downloadsDir ?: throw IOException("Downloads directory is unavailable")
        val oldDir = findOwnedSourceDirs(source).firstOrNull() ?: return@runCatching null
        val oldName = oldDir.name ?: return@runCatching null
        val newName = getKomgaServerDirName(newServerName)
        if (oldName == newName) return@runCatching oldDir

        val existingTarget = downloadsDir.findFile(newName)
        if (existingTarget != null && existingTarget.uri != oldDir.uri) {
            throw IOException("Download directory already exists: $newName")
        }

        var currentDir = oldDir
        val capitalizationChanged = oldName.equals(newName, ignoreCase = true)
        if (capitalizationChanged) {
            val tempName = newName + Downloader.TMP_DIR_SUFFIX
            if (!currentDir.renameTo(tempName)) {
                throw IOException("Failed to prepare download directory rename: $oldName")
            }
            currentDir = downloadsDir.findFile(tempName)
                ?: throw IOException("Failed to resolve temporary download directory: $tempName")
        }

        if (!currentDir.renameTo(newName)) {
            if (capitalizationChanged) {
                currentDir.renameTo(oldName)
            }
            throw IOException("Failed to rename download directory from $oldName to $newName")
        }

        invalidateSourceDirCache()
        downloadsDir.findFile(newName)
            ?: throw IOException("Renamed download directory is unavailable: $newName")
    }

    private fun legacyKomgaSharedSourceDirNames(source: Source): List<String> {
        return buildList {
            add(DiskUtil.buildValidFilename(KomgaSource.SOURCE_NAME))
            addAll(legacyKomgaSourceDirNames(KomgaSource.SOURCE_NAME))
            komgaServerPreferences.getProfiles().forEach { profile ->
                add(getKomgaServerDirName(profile.name))
                addAll(legacyKomgaSourceDirNames(profile.name))
            }
            add(getKomgaServerDirName(source.name))
            addAll(legacyKomgaSourceDirNames(source.name))
            komgaServerPreferences.getDirectoryAliases(source.id).forEach { alias ->
                add(getKomgaServerDirName(alias))
                addAll(legacyKomgaSourceDirNames(alias))
            }
        }.distinct()
    }

    private fun legacyKomgaSourceDirNames(sourceName: String): List<String> {
        val legacyName = "$sourceName (${KomgaSource.SOURCE_LANG.uppercase()})"
        return listOf(
            DiskUtil.buildValidFilename(legacyName),
            DiskUtil.buildValidFilename(legacyName, disallowNonAscii = true),
        ).distinct()
    }

    private fun shouldIncludeLegacySharedDirsInLookup(source: Source): Boolean {
        return isKomgaSource(source) &&
            komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared
    }

    private fun isKomgaSource(source: Source): Boolean {
        return source is KomgaSource || komgaServerPreferences.isKnownServerId(source.id)
    }

    private fun buildSourceDirsCacheKey(downloadsDir: UniFile, source: Source): String {
        return "${downloadsDir.uri}|${getSourceDirNames(source).joinToString(separator = "|")}"
    }

    fun invalidateSourceDirCache() {
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
