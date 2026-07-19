package eu.kanade.tachiyomi.data.download

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import koharia.source.komga.DownloadDirectoryMode
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaServerProfile
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
class DownloadCache(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val komgaServerPreferences: KomgaServerPreferences = Injekt.get(),
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = 1.hours.inWholeMilliseconds

    /**
     * The last time the cache was refreshed.
     */
    private val renewalStateLock = Any()

    @Volatile
    private var lastRenew = 0L

    @Volatile
    private var renewalJob: Job? = null
    private val renewalGeneration = AtomicLong(0L)

    private val profileRefreshLock = Any()

    @Volatile
    private var suppressedProfileIds: Set<Long>? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1000L) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: File
        get() = LocalTempCacheDirectoryProvider.downloadIndexCacheFile(context)

    private val rootDownloadsDirMutex = Mutex()
    private var rootDownloadsDir = RootDirectory(storageManager.getDownloadsDirectory())

    init {
        // Attempt to read cache file
        scope.launch {
            rootDownloadsDirMutex.withLock {
                try {
                    if (diskCacheFile.exists()) {
                        val diskCache = diskCacheFile.inputStream().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        rootDownloadsDir = diskCache
                        lastRenew = System.currentTimeMillis()
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to initialize from disk cache" }
                    diskCacheFile.delete()
                }
            }

            if (komgaServerPreferences.needsDownloadDirectoryLayoutMigration()) {
                provider.migrateLegacyKomgaDirectories()
                    .onSuccess { migrated ->
                        komgaServerPreferences.markDownloadDirectoryLayoutMigrated()
                        if (migrated) {
                            invalidateCache("legacy_komga_directories_migrated")
                        }
                    }
                    .onFailure { error ->
                        logcat(LogPriority.WARN, error) {
                            "Failed to migrate legacy Komga download directories; will retry"
                        }
                    }
            }
        }

        storageManager.changes
            .onEach { invalidateCache("storage_changed") }
            .launchIn(scope)

        komgaServerPreferences.downloadDirectoryMode.changes()
            .drop(1)
            .onEach { mode -> invalidateCache("download_directory_mode_changed:$mode") }
            .launchIn(scope)

        komgaServerPreferences.profilesChanges()
            .drop(1)
            .onEach { profiles ->
                val profileIds = profiles.mapTo(mutableSetOf(), KomgaServerProfile::id)
                val suppressRefresh = synchronized(profileRefreshLock) {
                    val expectedProfileIds = suppressedProfileIds
                    suppressedProfileIds = null
                    expectedProfileIds == profileIds
                }
                if (suppressRefresh) {
                    logcat(LogPriority.DEBUG) {
                        "DownloadCache: profile refresh suppressed ids=${profileIds.joinToString(",")}"
                    }
                    return@onEach
                }
                invalidateCache(
                    "server_profiles_changed:ids=${profileIds.joinToString(",")}",
                )
            }
            .launchIn(scope)
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param chapterUrl the url of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean,
        allowSharedLookup: Boolean = true,
    ): Boolean {
        if (skipCache) {
            val source = sourceManager.getOrStub(sourceId)
            return provider.findChapterDir(chapterName, chapterScanlator, chapterUrl, mangaTitle, source) != null
        }

        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(mangaTitle)]
            if (mangaDir != null) {
                return provider.getValidChapterDirNames(
                    chapterName,
                    chapterScanlator,
                    chapterUrl,
                ).any { it in mangaDir.chapterDirs }
            }
        }

        if (allowSharedLookup &&
            komgaServerPreferences.downloadDirectoryMode.get() == DownloadDirectoryMode.Shared
        ) {
            val source = sourceManager.getOrStub(sourceId)
            return provider.findChapterDir(chapterName, chapterScanlator, chapterUrl, mangaTitle, source) != null
        }

        return false
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        return rootDownloadsDir.sourceDirs.values
            .distinctBy { it.dir?.uri.toString() }
            .sumOf { sourceDir ->
                sourceDir.mangaDirs.values.sumOf { mangaDir ->
                    mangaDir.chapterDirs.size
                }
            }
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)]
            if (mangaDir != null) {
                return mangaDir.chapterDirs.size
            }
        }
        return 0
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(manga.title)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                if (it in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= it
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            chapters.forEach { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                    if (it in mangaDir.chapterDirs) {
                        mangaDir.chapterDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(manga.title)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    /**
     * Renames a manga in this cache.
     *
     * @param manga the manga being renamed.
     * @param mangaUniFile the manga's new directory.
     * @param newTitle the manga's new title.
     */
    suspend fun renameManga(manga: Manga, mangaUniFile: UniFile, newTitle: String) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val oldMangaDirName = provider.getMangaDirName(manga.title)
            var oldChapterDirs: MutableSet<String>? = null
            // Save the old name's cached chapter dirs
            if (sourceDir.mangaDirs.containsKey(oldMangaDirName)) {
                oldChapterDirs = sourceDir.mangaDirs[oldMangaDirName]?.chapterDirs
                sourceDir.mangaDirs -= oldMangaDirName
            }

            // Retrieve/create the cached manga directory for new name
            val newMangaDirName = provider.getMangaDirName(newTitle)
            var mangaDir = sourceDir.mangaDirs[newMangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += newMangaDirName to mangaDir
            }

            // Add the old chapters to new name's cache
            if (!oldChapterDirs.isNullOrEmpty()) {
                mangaDir.chapterDirs += oldChapterDirs
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: Source) {
        logcat(LogPriority.DEBUG) {
            "DownloadCache: removing source from in-memory index sourceId=${source.id} name=${source.name}"
        }
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    suspend fun refreshSourceDirectory(sourceId: Long, directory: UniFile) {
        val refreshedDirectory = scanSourceDirectory(SourceDirectory(directory))
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs += sourceId to refreshedDirectory
        }

        logcat(LogPriority.DEBUG) {
            "DownloadCache: refreshed source directory sourceId=$sourceId directory=${directory.name}"
        }
        notifyChanges()
    }

    /**
     * Prevents the profile listener from scheduling a second full scan after a removal flow
     * that has already updated the download cache explicitly.
     */
    fun suppressNextProfileRefresh(expectedProfileIds: Set<Long>) {
        synchronized(profileRefreshLock) {
            suppressedProfileIds = expectedProfileIds
        }
        logcat(LogPriority.DEBUG) {
            "DownloadCache: suppressing next profile refresh ids=${expectedProfileIds.joinToString(",")}"
        }
    }

    fun invalidateCache(reason: String = "unspecified") {
        val generation = renewalGeneration.incrementAndGet()
        val renewalActive = synchronized(renewalStateLock) {
            lastRenew = 0L
            renewalJob?.cancel()
            renewalJob?.isActive == true
        }
        logcat(LogPriority.DEBUG) {
            "DownloadCache: invalidate requested reason=$reason " +
                "generation=$generation lastRenew=$lastRenew " +
                "renewalActive=$renewalActive"
        }
        diskCacheFile.delete()
        if (!renewalActive) {
            renewCache("invalidate:$reason", generation)
        }
    }

    fun clearDiskCache(): Int {
        val deleted = LocalTempCacheDirectoryProvider.countDownloadIndexFiles(context)
        invalidateCache("clear_disk_cache")
        LocalTempCacheDirectoryProvider.clearLegacyDownloadIndexCache(context)
        return deleted
    }

    /**
     * Renews the downloads cache.
     */
    private fun renewCache(
        trigger: String = "periodic_or_lookup",
        generation: Long = renewalGeneration.get(),
    ) {
        val job = synchronized(renewalStateLock) {
            // Avoid renewing cache if in the process nor too often.
            if (lastRenew + renewInterval >= System.currentTimeMillis() || renewalJob?.isActive == true) {
                return
            }

            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val startedAt = System.currentTimeMillis()
                logcat(LogPriority.DEBUG) {
                    "DownloadCache: full scan started trigger=$trigger"
                }
                if (lastRenew == 0L) {
                    _isInitializing.emit(true)
                }

                // Try to wait until extensions and sources have loaded
                var sources = emptyList<Source>()
                withTimeoutOrNull(30.seconds) {
                    sourceManager.isInitialized.first { it }

                    sources = getSources()
                }
                logcat(LogPriority.DEBUG) {
                    "DownloadCache: full scan enumerating sources=${sources.size} " +
                        "sourceIds=${sources.joinToString(",") { it.id.toString() }} trigger=$trigger"
                }

                rootDownloadsDirMutex.withLock {
                    val updatedRootDir = RootDirectory(storageManager.getDownloadsDirectory())

                    val directoriesByKey = linkedMapOf<String, List<UniFile>>()
                    val sourceDirectoryKeys = sources.mapNotNull { source ->
                        val directories = provider.findSourceDirs(source)
                        if (directories.isEmpty()) return@mapNotNull null
                        val key = directories.joinToString(separator = "|") { it.uri.toString() }
                        directoriesByKey.putIfAbsent(key, directories)
                        source.id to key
                    }
                    val scansByKey = directoriesByKey.mapValues { (_, directories) ->
                        async { scanSourceDirectories(directories) }
                    }
                    val scannedDirectories = scansByKey.mapValues { (_, scan) -> scan.await() }
                    updatedRootDir.sourceDirs = sourceDirectoryKeys.associate { (sourceId, key) ->
                        sourceId to scannedDirectories.getValue(key)
                    }

                    rootDownloadsDir = updatedRootDir
                }

                _isInitializing.emit(false)
                logcat(LogPriority.DEBUG) {
                    "DownloadCache: full scan body completed trigger=$trigger " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt}"
                }
            }.also { renewalJob = it }
        }

        // Run after cancellation has fully completed so a newer invalidation can safely
        // start the replacement scan without being blocked by the old active Job.
        job.invokeOnCompletion { exception ->
            synchronized(renewalStateLock) {
                if (renewalJob === job) renewalJob = null
            }
            val isLatestGeneration = generation == renewalGeneration.get()
            if (exception != null && exception !is CancellationException) {
                logcat(LogPriority.ERROR, exception) { "DownloadCache: failed to create cache" }
            } else if (exception is CancellationException) {
                logcat(LogPriority.DEBUG) {
                    "DownloadCache: full scan cancelled trigger=$trigger " +
                        "generation=$generation latestGeneration=${renewalGeneration.get()}"
                }
            }

            if (!isLatestGeneration) {
                // An invalidation arrived while this scan was running. The cancellation
                // callback is the first reliable point at which the old Job is no longer
                // active, so restart the latest requested generation here.
                logcat(LogPriority.DEBUG) {
                    "DownloadCache: restarting superseded full scan " +
                        "generation=${renewalGeneration.get()}"
                }
                renewCache(
                    trigger = "superseded:$trigger",
                    generation = renewalGeneration.get(),
                )
                return@invokeOnCompletion
            }

            synchronized(renewalStateLock) {
                lastRenew = System.currentTimeMillis()
            }
            _isInitializing.value = false
            notifyChanges()
        }
        job.start()

        // Mainly to notify the indexing notifier UI
        notifyChanges()
    }

    private fun getSources(): List<Source> {
        return sourceManager.getOnlineSources() + sourceManager.getStubSources()
    }

    private fun scanSourceDirectory(sourceDir: SourceDirectory): SourceDirectory {
        sourceDir.mangaDirs = sourceDir.dir?.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.isNullOrBlank() }
            .associate { it.name!! to MangaDirectory(it) }

        sourceDir.mangaDirs.values.forEach { mangaDir ->
            val chapterDirs = mangaDir.dir?.listFiles().orEmpty()
                .mapNotNull {
                    when {
                        // Ignore incomplete downloads
                        it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                        // Folder of images
                        it.isDirectory -> it.name
                        // Supported downloaded files
                        it.isFile && DownloadProvider.isSupportedChapterFileExtension(it.extension) ->
                            it.nameWithoutExtension
                        // Anything else is irrelevant
                        else -> null
                    }
                }
                .toMutableSet()

            mangaDir.chapterDirs = chapterDirs
        }
        return sourceDir
    }

    private fun scanSourceDirectories(directories: List<UniFile>): SourceDirectory {
        val mergedMangaDirs = linkedMapOf<String, MangaDirectory>()
        directories.forEach { directory ->
            val scannedDirectory = scanSourceDirectory(SourceDirectory(directory))
            scannedDirectory.mangaDirs.forEach { (name, mangaDir) ->
                val existing = mergedMangaDirs[name]
                if (existing == null) {
                    mergedMangaDirs[name] = mangaDir
                } else {
                    existing.chapterDirs += mangaDir.chapterDirs
                }
            }
        }
        return SourceDirectory(
            dir = directories.firstOrNull(),
            mangaDirs = mergedMangaDirs,
        )
    }

    private fun notifyChanges() {
        scope.launchNonCancellable {
            _changes.send(Unit)
        }
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launchIO {
            delay(1000)
            ensureActive()
            val bytes = ProtoBuf.encodeToByteArray(rootDownloadsDir)
            ensureActive()
            try {
                diskCacheFile.writeBytes(bytes)
            } catch (e: Throwable) {
                logcat(
                    priority = LogPriority.ERROR,
                    throwable = e,
                    message = { "Failed to write disk cache file" },
                )
            }
        }
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var chapterDirs: MutableSet<String> = mutableSetOf(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(Injekt.get<Application>(), decoder.decodeString().toUri())
        } else {
            decoder.decodeNull()
        }
    }
}
