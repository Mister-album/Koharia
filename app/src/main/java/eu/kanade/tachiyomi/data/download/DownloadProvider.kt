package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.core.os.trace
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import koharia.media.LocalMediaFormats
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
import java.util.concurrent.ConcurrentHashMap

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
    private val connectionPreferences: ConnectionPreferences = Injekt.get(),
    private val connectionRegistry: ConnectionRegistry = Injekt.get(),
) {
    private val sourceDirsCache = ConcurrentHashMap<String, CacheEntry<List<UniFile>>>()

    // Accessed under sourceDirsCache's migration lock. One SAF listing serves all source aliases.
    private var downloadsListing: Pair<String, CacheEntry<Map<String, UniFile>>>? = null

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
        val storageAdapter = storageAdapter(source)
        val aliasResult = if (directResult == null) {
            (source as? koharia.connection.ConnectionDownloadAliasAdapter)?.downloadAliases(chapterUrl).orEmpty()
                .asSequence().flatMap { alias ->
                    val names = getValidChapterDirNames(alias.chapterName, alias.scanlator, alias.chapterUrl)
                    findMangaDirs(alias.mangaTitle, source).asSequence().flatMap { directory ->
                        names.asSequence().mapNotNull(directory::findFile)
                    }
                }.firstOrNull()
        } else {
            null
        }
        val result = directResult ?: aliasResult ?: when {
            storageAdapter?.usesSharedDownloadStorage == true -> {
                storageAdapter.findSharedChapterFile(chapterUrl, mangaTitle)
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
        return storageAdapter(source)?.downloadDirectoryName()
            ?: fallbackSourceDirName(source)
    }

    fun getSourceDirNames(source: Source): List<String> {
        return storageAdapter(source)?.downloadDirectoryNames() ?: listOf(fallbackSourceDirName(source))
    }

    private fun fallbackSourceDirName(source: Source) = DiskUtil.buildValidFilename(
        source.toString(),
        disallowNonAscii = disallowNonAsciiFilenames,
    )

    fun usesSharedDownloadStorage(source: Source): Boolean {
        return storageAdapter(source)?.usesSharedDownloadStorage == true
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

    fun getKomgaSharedDirName(): String {
        return DiskUtil.buildValidFilename("Komga (Shared)")
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
            LocalMediaFormats.allExtensions

        private const val CACHE_TTL_MS = 10_000L

        fun isSupportedChapterFileExtension(extension: String?): Boolean {
            return extension?.lowercase() in SUPPORTED_CHAPTER_FILE_EXTENSIONS
        }
    }

    internal fun findSourceDirs(source: Source): List<UniFile> {
        val downloadsDir = downloadsDirForRead() ?: return emptyList()
        return findSourceDirs(downloadsDir, source, storageAdapter(source))
    }

    private fun downloadsDirForRead() = trace("Koharia.downloadRoot") { storageManager.getDownloadsDirectoryForRead() }

    private fun findSourceDirs(
        downloadsDir: UniFile,
        source: Source,
        adapter: ConnectionDownloadStorageAdapter?,
    ): List<UniFile> {
        val names = adapter?.downloadDirectoryNames() ?: listOf(fallbackSourceDirName(source))
        val cacheKey = buildSourceDirsCacheKey(downloadsDir, names)
        sourceDirsCache[cacheKey]?.takeUnless { it.isExpired() }?.let { return it.value }
        // Keep discovery/migration serialized: different connections can share legacy aliases.
        return synchronized(sourceDirsCache) {
            sourceDirsCache[cacheKey]
                ?.takeUnless { it.isExpired() }
                ?.value
                ?: trace("Koharia.downloadDirectoryDiscovery") { findExistingSourceDirs(downloadsDir, names, adapter) }
                    .also { dirs ->
                        // A per-source result must not extend the lifetime of its shared snapshot.
                        val listedAt = downloadsListing
                            ?.takeIf { it.first == downloadsDir.uri.toString() }
                            ?.second?.cachedAt
                        sourceDirsCache[cacheKey] = CacheEntry(dirs, listedAt ?: System.currentTimeMillis())
                    }
        }
    }

    internal fun findOwnedSourceDirs(source: Source): List<UniFile> {
        val downloadsDir = downloadsDirForRead() ?: return emptyList()
        val adapter = storageAdapter(source)
        val ownedNames = adapter?.ownedDownloadDirectoryNames() ?: setOf(fallbackSourceDirName(source))
        return findSourceDirs(downloadsDir, source, adapter).filter { it.name in ownedNames }
    }

    private fun findExistingSourceDirs(
        downloadsDir: UniFile,
        names: List<String>,
        adapter: ConnectionDownloadStorageAdapter?,
    ): List<UniFile> {
        val primaryName = names.first()
        // Native file lookups are cheap and preserve discovery of externally added aliases.
        val directories = if (downloadsDir.uri.scheme == "file") {
            names.associateWith(downloadsDir::findFile)
        } else {
            val listing = listDownloadDirectories(downloadsDir)
            names.associateWith(listing::get)
        }
        if (
            directories[primaryName] == null &&
            adapter?.usesSharedDownloadStorage == false
        ) {
            val ownedNames = adapter.ownedDownloadDirectoryNames()
            val legacyDir = names.drop(1)
                .filter { it in ownedNames }
                .mapNotNull(directories::get)
                .distinctBy { it.uri.toString() }
                .singleOrNull()
            val legacyName = legacyDir?.name
            if (legacyDir != null && legacyDir.renameTo(primaryName)) {
                invalidateSourceDirCache()
                logcat(LogPriority.INFO) {
                    "Migrated legacy Komga server directory from $legacyName to $primaryName"
                }
                return names.mapNotNull(downloadsDir::findFile).distinctBy { it.uri.toString() }
            }
        }

        return directories.values
            .filterNotNull()
            .distinctBy { it.uri.toString() }
    }

    private fun resolveSourceDir(downloadsDir: UniFile, source: Source, sourceDirName: String): UniFile? {
        downloadsDir.findFile(sourceDirName)?.let { return it }

        val legacyDirNames = storageAdapter(source)?.legacyDownloadDirectoryNames().orEmpty()
        val legacyDir = legacyDirNames
            .mapNotNull(downloadsDir::findFile)
            .distinctBy { it.uri.toString() }
            .singleOrNull()

        if (legacyDir != null) {
            if (legacyDir.name != sourceDirName && legacyDir.renameTo(sourceDirName)) {
                invalidateSourceDirCache()
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
        val legacySharedName = DiskUtil.buildValidFilename("Komga")
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

        connectionPreferences.getProfiles().forEach { profile ->
            connectionRegistry.createSource(profile)
                ?.takeIf { (it as? ConnectionDownloadStorageAdapter)?.usesSharedDownloadStorage == false }
                ?.let { source ->
                    migrated = migrateLegacyKomgaServerDir(
                        downloadsDir = downloadsDir,
                        source = source,
                    ) || migrated
                }
        }

        if (migrated) invalidateSourceDirCache()
        migrated
    }

    private fun migrateLegacyKomgaServerDir(downloadsDir: UniFile, source: Source): Boolean {
        val adapter = storageAdapter(source) ?: return false
        val primaryName = adapter.downloadDirectoryName()
        if (downloadsDir.findFile(primaryName) != null) {
            return false
        }

        val legacyDir = adapter.legacyDownloadDirectoryNames()
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

    fun renameKomgaServerDir(source: Source, newServerName: String): Result<UniFile?> = runCatching {
        if (storageAdapter(source)?.usesSharedDownloadStorage == true) {
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

    fun storageAdapter(source: Source): ConnectionDownloadStorageAdapter? {
        (source as? ConnectionDownloadStorageAdapter)?.let { return it }
        val providerId = connectionPreferences.providerIdForSource(source.id) ?: return null
        val detachedProfile = LibraryConnectionProfile(
            id = source.id,
            providerId = providerId,
            name = source.name,
        )
        return connectionRegistry.createSource(detachedProfile) as? ConnectionDownloadStorageAdapter
    }

    private fun buildSourceDirsCacheKey(downloadsDir: UniFile, names: List<String>): String {
        return "${downloadsDir.uri}|${names.joinToString(separator = "|")}"
    }

    private fun listDownloadDirectories(downloadsDir: UniFile): Map<String, UniFile> {
        val root = downloadsDir.uri.toString()
        downloadsListing?.takeIf { it.first == root && !it.second.isExpired() }?.let { return it.second.value }
        val entries = trace("Koharia.downloadRootListing") {
            buildMap {
                downloadsDir.listFiles().orEmpty().forEach { file ->
                    file.name?.let { name -> putIfAbsent(name, file) }
                }
            }
        }
        downloadsListing = root to CacheEntry(entries)
        return entries
    }

    fun invalidateSourceDirCache() {
        synchronized(sourceDirsCache) {
            sourceDirsCache.clear()
            downloadsListing = null
            storageManager.invalidateDownloadsDirectoryCache()
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
