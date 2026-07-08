package eu.kanade.tachiyomi.data.download

import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import koharia.komga.download.KomgaBookFingerprint
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.DownloadDirectoryMode
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

class KomgaSharedDownloadIndexManager(
    private val repository: KomgaSharedDownloadRepository = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val komgaServerPreferences: KomgaServerPreferences = Injekt.get(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeWarmups = Collections.synchronizedSet(mutableSetOf<String>())
    private val recentWarmups = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val sharedSourceDirsCache = Collections.synchronizedMap(mutableMapOf<String, CacheEntry<List<UniFile>>>())
    private val mangaDownloadsCache =
        Collections.synchronizedMap(mutableMapOf<String, CacheEntry<Map<String, UniFile>>>())

    @Volatile
    private var warmupJob: Job? = null

    fun findSharedChapterDir(
        chapterUrl: String,
        mangaTitle: String,
        source: KomgaSource,
    ): UniFile? {
        if (!shouldUseSharedMatching(source)) return null

        return runBlocking(Dispatchers.IO) {
            val fingerprint = resolveStoredFingerprint(
                chapterUrl = chapterUrl,
            )

            if (fingerprint == null) {
                scheduleWarmup(source, mangaTitle)
                return@runBlocking null
            }

            findSharedChapterDir(source.id, fingerprint).also { file ->
                if (file == null) {
                    scheduleWarmup(source, mangaTitle)
                }
            }
        }
    }

    suspend fun indexDownloadedChapter(
        chapter: Chapter,
        source: KomgaSource,
        localFile: UniFile,
    ) {
        if (!shouldUseSharedMatching(source)) return

        val fingerprint = KomgaChapterMemo.readFingerprint(chapter.memo)
            ?: resolveFingerprintForIndexing(
                chapterUrl = chapter.url,
                source = source,
                chapterId = chapter.id,
                existingMemo = chapter.memo,
            )
            ?: return

        val relativePath = relativePathOf(localFile) ?: return
        upsertMatch(
            serverId = source.id,
            fingerprint = fingerprint,
            relativePath = relativePath,
            fileName = localFile.name.orEmpty(),
            fileKind = if (localFile.isDirectory) FILE_KIND_DIRECTORY else FILE_KIND_FILE,
        )
        invalidateLocalLookupCaches()
    }

    suspend fun deleteIndexedPath(localItem: UniFile) {
        val relativePath = relativePathOf(localItem) ?: return
        if (localItem.isDirectory) {
            deleteIndexedPathPrefix(relativePath)
        } else {
            repository.deleteByLocalRelativePath(relativePath)
        }
        invalidateLocalLookupCaches()
    }

    suspend fun deleteIndexedPathPrefix(relativePathPrefix: String) {
        repository.findByLocalRelativePathPrefix(relativePathPrefix)
            .map(KomgaSharedDownloadMatch::localRelativePath)
            .distinct()
            .forEach { repository.deleteByLocalRelativePath(it) }
        invalidateLocalLookupCaches()
    }

    suspend fun updateIndexedPath(
        oldRelativePath: String,
        newLocalItem: UniFile,
    ) {
        val newRelativePath = relativePathOf(newLocalItem) ?: return
        repository.updateLocalRelativePath(
            oldLocalRelativePath = oldRelativePath,
            newLocalRelativePath = newRelativePath,
            newFileName = newLocalItem.name.orEmpty(),
            lastVerifiedAt = now(),
        )
        invalidateLocalLookupCaches()
    }

    suspend fun updateIndexedPathPrefix(
        oldRelativePathPrefix: String,
        newRelativePathPrefix: String,
    ) {
        repository.updateLocalRelativePathPrefix(
            oldPrefix = oldRelativePathPrefix,
            newPrefix = newRelativePathPrefix,
            lastVerifiedAt = now(),
        )
        invalidateLocalLookupCaches()
    }

    suspend fun removeServerIndexes(serverId: Long) {
        repository.deleteByServerId(serverId)
    }

    suspend fun cleanupServerDownloads(
        serverId: Long,
        cleanupMode: koharia.source.komga.DownloadCleanupMode,
    ) {
        val matches = repository.findByServerId(serverId)
        val relativePaths = matches.map(KomgaSharedDownloadMatch::localRelativePath).distinct()

        when (cleanupMode) {
            koharia.source.komga.DownloadCleanupMode.Preserve -> {
                repository.deleteByServerId(serverId)
            }
            koharia.source.komga.DownloadCleanupMode.DeleteExclusiveMatches -> {
                repository.deleteByServerId(serverId)
                relativePaths.forEach { relativePath ->
                    if (repository.countByLocalRelativePath(relativePath) == 0L) {
                        deleteLocalRelativePath(relativePath)
                        repository.deleteByLocalRelativePath(relativePath)
                    }
                }
            }
            koharia.source.komga.DownloadCleanupMode.DeleteAllMatchedFiles -> {
                relativePaths.forEach { relativePath ->
                    deleteLocalRelativePath(relativePath)
                    repository.deleteByLocalRelativePath(relativePath)
                }
                repository.deleteByServerId(serverId)
            }
        }
        invalidateLocalLookupCaches()
    }

    fun relativePathOf(localItem: UniFile): String? {
        val downloadsDir = storageManager.getDownloadsDirectory() ?: return null
        return relativePathFromDocumentIds(downloadsDir, localItem)
            ?: relativePathFromUriPath(downloadsDir, localItem)
            ?: relativePathFromFilePath(downloadsDir, localItem)
    }

    private fun relativePathFromDocumentIds(root: UniFile, item: UniFile): String? {
        val rootDocumentId = root.documentIdOrNull() ?: return null
        val itemDocumentId = item.documentIdOrNull() ?: return null
        if (!itemDocumentId.startsWith(rootDocumentId)) return null
        return itemDocumentId.removePrefix(rootDocumentId)
            .trimStart('/', '\\')
            .replace('\\', '/')
            .takeIf { it.isNotBlank() }
    }

    private fun relativePathFromUriPath(root: UniFile, item: UniFile): String? {
        val rootPath = root.uri.path?.trimEnd('/', '\\') ?: return null
        val itemPath = item.uri.path ?: return null
        if (!itemPath.startsWith(rootPath)) return null
        return itemPath.removePrefix(rootPath)
            .trimStart('/', '\\')
            .replace('\\', '/')
            .takeIf { it.isNotBlank() }
    }

    private fun relativePathFromFilePath(root: UniFile, item: UniFile): String? {
        val rootPath = root.filePath?.trimEnd('/', '\\') ?: return null
        val itemPath = item.filePath ?: return null
        if (!itemPath.startsWith(rootPath)) return null
        return itemPath.removePrefix(rootPath)
            .trimStart('/', '\\')
            .replace('\\', '/')
            .takeIf { it.isNotBlank() }
    }

    private suspend fun findSharedChapterDir(
        serverId: Long,
        fingerprint: KomgaBookFingerprint,
    ): UniFile? {
        repository.findByServerIdAndBookUrl(serverId, fingerprint.bookUrl)?.let { directMatch ->
            resolveVerifiedFile(directMatch)?.let { file ->
                touchMatch(directMatch)
                return file
            }
        }

        findCandidateMatches(fingerprint).forEach { match ->
            val file = resolveVerifiedFile(match) ?: return@forEach
            touchMatch(match)
            upsertMatch(
                serverId = serverId,
                fingerprint = fingerprint,
                relativePath = match.localRelativePath,
                fileName = match.fileName,
                fileKind = match.fileKind,
            )
            return file
        }

        return null
    }

    private suspend fun resolveStoredFingerprint(
        chapterUrl: String,
    ): KomgaBookFingerprint? {
        val seed = repository.getChapterSeedByUrl(chapterUrl)
        return seed?.memo?.let(KomgaChapterMemo::readFingerprint)
    }

    private suspend fun resolveFingerprintForIndexing(
        chapterUrl: String,
        source: KomgaSource,
        chapterId: Long = -1L,
        existingMemo: JsonObject = JsonObject(emptyMap()),
    ): KomgaBookFingerprint? {
        val seed = repository.getChapterSeedByUrl(chapterUrl)
        KomgaChapterMemo.readFingerprint(seed?.memo ?: existingMemo)?.let { return it }

        val book = source.getBookDetails(chapterUrl) ?: return null
        val fingerprint = KomgaChapterMemo.buildFingerprint(source.baseUrl, book)
        val mergedMemo = KomgaChapterMemo.mergeInto(seed?.memo ?: existingMemo, fingerprint)
        val targetChapterId = seed?.chapterId ?: chapterId
        if (targetChapterId > 0L) {
            repository.updateChapterMemo(targetChapterId, mergedMemo)
        }
        return fingerprint
    }

    private suspend fun findCandidateMatches(fingerprint: KomgaBookFingerprint): List<KomgaSharedDownloadMatch> {
        val ordered = linkedMapOf<String, KomgaSharedDownloadMatch>()

        if (fingerprint.fileHash.isNotBlank()) {
            repository.findByFileHash(fingerprint.fileHash).forEach { ordered.putIfAbsent(it.localRelativePath, it) }
        }
        if (ordered.isNotEmpty()) return ordered.values.toList()

        if (fingerprint.isbn.isNotBlank()) {
            repository.findByIsbn(fingerprint.isbn).forEach { ordered.putIfAbsent(it.localRelativePath, it) }
        }
        if (ordered.isNotEmpty()) return ordered.values.toList()

        if (fingerprint.seriesTitle.isNotBlank() && fingerprint.sizeBytes > 0L) {
            repository.findBySeriesNumberSize(
                seriesTitle = fingerprint.seriesTitle,
                numberSort = fingerprint.numberSort,
                sizeBytes = fingerprint.sizeBytes,
            ).forEach { ordered.putIfAbsent(it.localRelativePath, it) }
        }
        if (ordered.isNotEmpty()) return ordered.values.toList()

        if (fingerprint.bookTitle.isNotBlank() && fingerprint.sizeBytes > 0L) {
            repository.findByBookTitleSize(
                bookTitle = fingerprint.bookTitle,
                sizeBytes = fingerprint.sizeBytes,
            ).forEach { ordered.putIfAbsent(it.localRelativePath, it) }
        }

        return ordered.values.toList()
    }

    private suspend fun resolveVerifiedFile(match: KomgaSharedDownloadMatch): UniFile? {
        val file = resolveLocalRelativePath(match.localRelativePath)
        if (file == null || !file.exists()) {
            repository.deleteByLocalRelativePath(match.localRelativePath)
            return null
        }
        return file
    }

    private suspend fun touchMatch(match: KomgaSharedDownloadMatch) {
        repository.upsert(match.copy(lastVerifiedAt = now()))
    }

    private suspend fun upsertMatch(
        serverId: Long,
        fingerprint: KomgaBookFingerprint,
        relativePath: String,
        fileName: String,
        fileKind: String,
    ) {
        val existing = repository.findByServerIdAndBookUrl(serverId, fingerprint.bookUrl)
        val timestamp = now()
        repository.upsert(
            KomgaSharedDownloadMatch(
                serverId = serverId,
                bookUrl = fingerprint.bookUrl,
                seriesUrl = fingerprint.seriesUrl,
                fileHash = fingerprint.fileHash,
                sizeBytes = fingerprint.sizeBytes,
                isbn = fingerprint.isbn,
                seriesTitle = fingerprint.seriesTitle,
                bookTitle = fingerprint.bookTitle,
                numberSort = fingerprint.numberSort,
                localRelativePath = relativePath,
                fileName = fileName,
                fileKind = fileKind,
                createdAt = existing?.createdAt ?: timestamp,
                lastVerifiedAt = timestamp,
            ),
        )
    }

    private fun resolveLocalRelativePath(relativePath: String): UniFile? {
        val downloadsDir = storageManager.getDownloadsDirectory() ?: return null
        return relativePath.split('/')
            .filter { it.isNotBlank() }
            .fold(downloadsDir as UniFile?) { current, segment ->
                current?.findFile(segment)
            }
    }

    private fun deleteLocalRelativePath(relativePath: String) {
        val target = resolveLocalRelativePath(relativePath) ?: return
        target.delete()
        pruneEmptyParents(relativePath)
    }

    private fun pruneEmptyParents(relativePath: String) {
        val downloadsDir = storageManager.getDownloadsDirectory() ?: return
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return

        for (depth in segments.size - 1 downTo 1) {
            val parent = segments.take(depth).fold(downloadsDir as UniFile?) { current, segment ->
                current?.findFile(segment)
            } ?: return
            if (parent.listFiles().orEmpty().isEmpty()) {
                parent.delete()
            } else {
                break
            }
        }
    }

    private fun scheduleWarmup(source: KomgaSource, mangaTitle: String) {
        val warmupKey = "${source.id}::$mangaTitle"
        val now = now()
        val lastWarmupAt = recentWarmups[warmupKey]
        if (lastWarmupAt != null && now - lastWarmupAt < WARMUP_COOLDOWN_MS) return
        if (!activeWarmups.add(warmupKey)) return

        warmupJob = scope.launch {
            runCatching {
                warmupMangaDownloads(source, mangaTitle)
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Komga shared download warmup failed for sourceId=${source.id} mangaTitle=$mangaTitle"
                }
            }.also {
                recentWarmups[warmupKey] = now()
                activeWarmups.remove(warmupKey)
            }
        }
    }

    private suspend fun warmupMangaDownloads(
        source: KomgaSource,
        mangaTitle: String,
    ) {
        val seeds = repository.getChapterSeedsBySourceId(source.id)
            .filter { it.mangaTitle == mangaTitle }
        val localDownloadsByName = buildLocalDownloadsByName(mangaTitle)

        seeds.forEach { seed ->
            val fingerprint = KomgaChapterMemo.readFingerprint(seed.memo)
                ?: resolveFingerprintForIndexing(
                    chapterUrl = seed.chapterUrl,
                    source = source,
                    chapterId = seed.chapterId,
                    existingMemo = seed.memo,
                )
                ?: return@forEach

            if (repository.findByServerIdAndBookUrl(source.id, fingerprint.bookUrl) != null) {
                return@forEach
            }

            val localFile = findExistingLocalDownload(seed, localDownloadsByName) ?: return@forEach
            val relativePath = relativePathOf(localFile) ?: return@forEach
            upsertMatch(
                serverId = source.id,
                fingerprint = fingerprint,
                relativePath = relativePath,
                fileName = localFile.name.orEmpty(),
                fileKind = if (localFile.isDirectory) FILE_KIND_DIRECTORY else FILE_KIND_FILE,
            )
        }
    }

    private fun buildLocalDownloadsByName(mangaTitle: String): Map<String, UniFile> {
        val downloadsDir = storageManager.getDownloadsDirectory() ?: return emptyMap()
        val mangaDirName = getMangaDirName(mangaTitle)
        val cacheKey = "${downloadsDir.uri}|$mangaDirName"

        mangaDownloadsCache[cacheKey]
            ?.takeUnless { it.isExpired() }
            ?.value
            ?.let { return it }

        return sharedSourceDirs()
            .asSequence()
            .mapNotNull { it.findFile(mangaDirName) }
            .flatMap { mangaDir ->
                mangaDir.listFiles()
                    .orEmpty()
                    .asSequence()
                    .mapNotNull { file ->
                        val name = file.name ?: return@mapNotNull null
                        name to file
                    }
            }
            .distinctBy { (name, _) -> name }
            .associate { (name, file) -> name to file }
            .also { files ->
                mangaDownloadsCache[cacheKey] = CacheEntry(files)
            }
    }

    private fun findExistingLocalDownload(
        seed: KomgaChapterSeed,
        localDownloadsByName: Map<String, UniFile>,
    ): UniFile? {
        return getValidChapterDirNames(seed.chapterName, seed.chapterScanlator, seed.chapterUrl)
            .asSequence()
            .mapNotNull(localDownloadsByName::get)
            .firstOrNull()
    }

    private fun sharedSourceDirs(): List<UniFile> {
        val downloadsDir = storageManager.getDownloadsDirectory() ?: return emptyList()
        val sourceDirNames = buildList {
            add(KomgaSource.SOURCE_NAME)
            addAll(legacySharedSourceDirNames())
        }
            .distinct()
        val cacheKey = "${downloadsDir.uri}|${sourceDirNames.joinToString(separator = "|")}"

        sharedSourceDirsCache[cacheKey]
            ?.takeUnless { it.isExpired() }
            ?.value
            ?.let { return it }

        return sourceDirNames
            .mapNotNull(downloadsDir::findFile)
            .distinctBy { it.uri.toString() }
            .also { dirs ->
                sharedSourceDirsCache[cacheKey] = CacheEntry(dirs)
            }
    }

    private fun legacySharedSourceDirNames(): List<String> {
        return buildList {
            add(legacySourceDirName(KomgaSource.SOURCE_NAME))
            komgaServerPreferences.getProfiles().forEach { profile ->
                add(legacySourceDirName(profile.name))
            }
        }.distinct()
    }

    private fun legacySourceDirName(sourceName: String): String {
        return DiskUtil.buildValidFilename(
            "$sourceName (${KomgaSource.SOURCE_LANG.uppercase()})",
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    private fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(
            mangaTitle,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    private fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAscii: Boolean = libraryPreferences.disallowNonAsciiFilenames.get(),
    ): String {
        var dirName = chapterName.ifBlank { "Chapter" }
        if (!chapterScanlator.isNullOrBlank()) {
            dirName = chapterScanlator + "_" + dirName
        }
        dirName = DiskUtil.buildValidFilename(dirName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAscii)
        dirName += "_" + md5(chapterUrl).take(6)
        return dirName
    }

    private fun getLegacyChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
    ): List<String> {
        val sanitizedChapterName = chapterName.ifBlank { "Chapter" }
        val legacyName = DiskUtil.buildValidFilename(
            when {
                !chapterScanlator.isNullOrBlank() -> "${chapterScanlator}_$sanitizedChapterName"
                else -> sanitizedChapterName
            },
        )
        val otherName = getChapterDirName(
            chapterName = chapterName,
            chapterScanlator = chapterScanlator,
            chapterUrl = chapterUrl,
            disallowNonAscii = !libraryPreferences.disallowNonAsciiFilenames.get(),
        )
        return listOf(legacyName, otherName)
    }

    private fun getValidChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
    ): List<String> {
        val chapterDirName = getChapterDirName(chapterName, chapterScanlator, chapterUrl)
        val legacyChapterDirNames = getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl)
        return buildList {
            add(chapterDirName)
            DownloadProvider.SUPPORTED_CHAPTER_FILE_EXTENSIONS.forEach { extension ->
                add("$chapterDirName.$extension")
            }
            legacyChapterDirNames.forEach { legacyName ->
                add(legacyName)
                DownloadProvider.SUPPORTED_CHAPTER_FILE_EXTENSIONS.forEach { extension ->
                    add("$legacyName.$extension")
                }
            }
        }
    }

    private fun shouldUseSharedMatching(source: KomgaSource): Boolean {
        return komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared &&
            source.hasValidBaseUrl()
    }

    private fun invalidateLocalLookupCaches() {
        sharedSourceDirsCache.clear()
        mangaDownloadsCache.clear()
    }

    private fun now(): Long = System.currentTimeMillis()

    private data class CacheEntry<T>(
        val value: T,
        val cachedAt: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - cachedAt >= LOCAL_LOOKUP_CACHE_TTL_MS
        }
    }

    companion object {
        private const val FILE_KIND_FILE = "file"
        private const val FILE_KIND_DIRECTORY = "directory"
        private const val WARMUP_COOLDOWN_MS = 30_000L
        private const val LOCAL_LOOKUP_CACHE_TTL_MS = 10_000L
    }
}

private fun UniFile.documentIdOrNull(): String? {
    return runCatching {
        DocumentsContract.getDocumentId(uri)
    }.getOrElse {
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    }
}
