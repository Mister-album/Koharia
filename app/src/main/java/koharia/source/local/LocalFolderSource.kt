package koharia.source.local

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import androidx.preference.PreferenceScreen
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import koharia.connection.ConnectionBrowseAdapter
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionChapterThumbnailAdapter
import koharia.connection.ConnectionFileTransfer
import koharia.connection.ConnectionLibraryMembershipAdapter
import koharia.connection.ConnectionLibraryRefreshAdapter
import koharia.connection.ConnectionLibraryRefreshResult
import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionLibraryShelfAdapter
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.connection.ConnectionMediaGrouping
import koharia.connection.ConnectionMediaImportAdapter
import koharia.connection.ConnectionMediaImportDestination
import koharia.connection.ConnectionMediaImportRequest
import koharia.connection.ConnectionMediaImportResult
import koharia.connection.ConnectionMediaImportSeries
import koharia.connection.ConnectionMediaType
import koharia.connection.ConnectionMetadataAdapter
import koharia.connection.ConnectionMetadataConflictAdapter
import koharia.connection.ConnectionMetadataGenerationAdapter
import koharia.connection.ConnectionSeriesCoverAdapter
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.connection.LibraryMetadata
import koharia.connection.LibraryMetadataField
import koharia.connection.LibraryMetadataSuggestion
import koharia.connection.MetadataFilenameTemplate
import koharia.connection.MetadataSuggestionSource
import koharia.connection.SharedAppPreferences
import koharia.core.archive.archiveReader
import koharia.core.archive.epubReader
import koharia.document.DocumentEngines
import koharia.document.MobiDocumentEngine
import koharia.document.toDocumentRenderSettings
import koharia.domain.manga.model.toDomainManga
import koharia.importing.IncomingMediaSessionLocator
import koharia.media.LocalMediaFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.imageEntries
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.common.util.system.readCoverImage
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class LocalReadProgressIndex(
    val indexedChapterCount: Int,
    val isIndividualFile: Boolean,
    val itemKey: String = "",
    val isFolderContainer: Boolean = false,
    val descendantItemKeys: Set<String> = emptySet(),
)

internal class LocalLibraryPartialScanException(
    val failedRootIds: Set<String>,
    message: String,
) : IOException(message)

class LocalFolderSource(
    private val context: Context,
    override val id: Long,
    private val customName: String,
    override val connectionProfile: LibraryConnectionProfile,
) :
    CatalogueSource,
    ConfigurableSource,
    UnmeteredSource,
    ConnectionSource,
    ConnectionBrowseAdapter,
    koharia.connection.ConnectionEntryOpeningAdapter,
    ConnectionLibraryRefreshAdapter,
    ConnectionLibraryMembershipAdapter,
    ConnectionLibraryShelfAdapter,
    ConnectionMangaBehaviorAdapter,
    ConnectionMediaImportAdapter,
    ConnectionChapterThumbnailAdapter,
    ConnectionLocalFileAdapter,
    koharia.connection.ConnectionPreparedFileAdapter,
    koharia.connection.ConnectionReaderRoutingAdapter,
    ConnectionSeriesCoverAdapter,
    ConnectionMetadataGenerationAdapter,
    ConnectionMetadataConflictAdapter,
    ConnectionMetadataAdapter,
    koharia.connection.ConnectionFileTransferAdapter,
    koharia.connection.ConnectionDownloadStorageAdapter,
    koharia.connection.ConnectionPageProgressAdapter,
    koharia.connection.ConnectionLocalPageProgressAdapter,
    koharia.connection.ConnectionEpubProgressAdapter,
    koharia.connection.ConnectionLocalEpubProgressAdapter,
    koharia.connection.ConnectionReadStatusAdapter,
    koharia.connection.ConnectionManagedLifecycle,
    AutoCloseable {

    private val json = Injekt.get<kotlinx.serialization.json.Json>()
    private val xml: XML by injectLazy()
    private val preferences by lazy { LocalLibraryPreferences(id, json) }
    private val storageProgress by lazy { koharia.storage.StorageReaderProgressAdapter(::prepareChapterFile) }
    private val storageScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private var storageObserver: kotlinx.coroutines.Job? = null
    override fun onRegistered() {
        if (storageObserver != null) return
        storageObserver = storageScope.launch {
            Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.collect { available ->
                if (available && supportsFileTransfers) {
                    runCatching { koharia.storage.NetworkStorageRuntime.get(context, id).progress.flush() }
                        .onFailure { if (it is CancellationException) throw it }
                }
            }
        }
    }
    override fun close() {
        storageScope.cancel()
    }
    override suspend fun pullPageProgress(
        chapterUrl: String,
        chapterMemo: JsonObject,
    ) = storageProgress.pullPageProgress(chapterUrl, chapterMemo)
    override suspend fun pushPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) = storageProgress.pushPageProgress(chapterUrl, pageIndex, totalPages)
    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) =
        storageProgress.recordLocalPageProgress(chapterUrl, pageIndex, totalPages, readAt, initialPage)
    override suspend fun setChapterReadStatus(
        chapterUrl: String,
        read: Boolean,
    ) = storageProgress.setChapterReadStatus(chapterUrl, read)
    override suspend fun getCachedEpubProgress(chapterId: Long) = storageProgress.getCachedEpubProgress(chapterId)
    override suspend fun refreshEpubProgress(
        mangaId: Long,
        chapter: Chapter,
    ) = storageProgress.refreshEpubProgress(mangaId, chapter)
    override suspend fun pullEpubProgress(resourceId: String) = storageProgress.pullEpubProgress(resourceId)
    override suspend fun pushEpubProgress(
        resourceId: String,
        locator: org.readium.r2.shared.publication.Locator,
        positions: List<org.readium.r2.shared.publication.Locator>,
        modifiedAt: java.util.Date,
    ) =
        storageProgress.pushEpubProgress(resourceId, locator, positions, modifiedAt)
    override suspend fun recordLocalEpubProgress(
        resourceId: String,
        locator: org.readium.r2.shared.publication.Locator,
        modifiedAt: java.util.Date,
    ) =
        storageProgress.recordLocalEpubProgress(resourceId, locator, modifiedAt)
    override suspend fun acceptRemoteEpubProgress(
        resourceId: String,
        locator: org.readium.r2.shared.publication.Locator,
        modifiedAt: java.util.Date,
    ) =
        storageProgress.acceptRemoteEpubProgress(resourceId, locator, modifiedAt)
    override suspend fun confirmLocalEpubProgress(
        resourceId: String,
        locator: org.readium.r2.shared.publication.Locator,
        modifiedAt: java.util.Date,
    ) =
        storageProgress.confirmLocalEpubProgress(resourceId, locator, modifiedAt)

    internal fun rememberCustomCover(url: String) = preferences.rememberCustomCover(url)

    internal suspend fun customCoverCandidates(): Set<String> =
        preferences.customCoverUrls() + mangaRepository.getMangaBySourceId(id).map(Manga::url)

    internal suspend fun removeCustomCoverIfMissing(url: String, delete: () -> Unit): Boolean =
        refreshMutex.withLock {
            val config = preferences.coverMaintenanceConfig() ?: return@withLock false
            val location = LocalLibraryLocator.location(url, id)
            if (location?.relativePath?.startsWith(".koharia/nodes/") == true) return@withLock false
            val removed = isRemovedLocalCover(id, url, config) { root, path ->
                preferences.resolveRoot(context, root)?.let { localCoverEntryExists(context, it, path) }
            }
            if (!removed) return@withLock false
            preferences.withUnchangedConfig(config, delete)
        }

    override fun seriesSettingsAvailable() = preferences.configChanges().map { config ->
        config.roots.any { config.organizationMode(it) == LocalLibraryOrganizationMode.SERIES }
    }

    override fun entryOpeningSettings(): List<koharia.connection.ConnectionEntryOpeningSetting> {
        val opening = Injekt.get<koharia.connection.EntryOpenPreferences>()
        return listOf(
            koharia.connection.ConnectionEntryOpeningSetting(
                tachiyomi.i18n.MR.strings.entry_open_local_single,
                opening.localSingleComic,
                koharia.connection.EntryOpenMode.entries,
            ),
            koharia.connection.ConnectionEntryOpeningSetting(
                tachiyomi.i18n.MR.strings.entry_open_local_single_book,
                opening.localSingleBook,
                listOf(koharia.connection.EntryOpenMode.READER, koharia.connection.EntryOpenMode.DETAILS),
            ),
        )
    }
    private val metadataStore by lazy { LocalMetadataStore(context, id, json, xml) }
    private val mangaRepository: MangaRepository by injectLazy()
    private val getChaptersByMangaId: GetChaptersByMangaId by injectLazy()
    private val coverCache: CoverCache by injectLazy()
    private val documentLayoutPreferences by lazy {
        Injekt.get<SharedAppPreferences>().epubLayoutPreferences()
    }
    private val refreshMutex = Mutex()
    private val mutableLibraryRefreshes = MutableSharedFlow<ConnectionLibraryRefreshResult>(
        replay = 1,
        extraBufferCapacity = 1,
    )

    override val libraryRefreshes = mutableLibraryRefreshes.asSharedFlow()
    override val libraryShelves: Flow<List<ConnectionLibraryShelf>> = preferences.libraryStateChanges()
        .map { preferences.getConfig().toConnectionLibraryShelves(context) }

    override val name: String = customName
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override val mangaBehavior: ConnectionMangaBehavior get() = MANGA_BEHAVIOR.copy(
        allowsChapterDownloads = supportsFileTransfers,
    )
    override val supportsFileTransfers: Boolean get() =
        koharia.storage.NetworkStoragePreferences(id).configuration.mode != koharia.storage.LibraryStorageMode.LOCAL
    override val usesSharedDownloadStorage = false
    override fun downloadDirectoryName() = "Storage_${id}_${koharia.storage.NetworkStoragePreferences(id).account}"
    override fun downloadDirectoryNames() = listOf(downloadDirectoryName())
    override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())
    override fun legacyDownloadDirectoryNames() = emptyList<String>()

    override suspend fun describeFileTransfer(chapterUrl: String): ConnectionFileTransfer = withIOContext {
        val file = prepareChapterFile(chapterUrl) as? com.hippo.unifile.RemoteStorageFile ?: error("Not a network file")
        val entry = file.runtime.backend.stat(file.storagePath)
        if (entry.directory) {
            val images = file.runtime.backend.list(file.storagePath).filter {
                !it.directory &&
                    LocalMediaFormats.isImage(it.name.substringAfterLast('.'))
            }
                .sortedBy { it.path }
            require(images.isNotEmpty())
            koharia.connection.ConnectionFileTransfer(
                "cbz",
                -1,
                koharia.storage.storageDigest(
                    images.joinToString {
                        it.path +
                            it.version
                    },
                ),
            )
        } else {
            koharia.connection.ConnectionFileTransfer(checkNotNull(file.extension), entry.size, entry.version)
        }
    }
    override suspend fun transferFile(
        chapterUrl: String,
        expected: koharia.connection.ConnectionFileTransfer,
        output: java.io.OutputStream,
        offset: Long,
    ) = withIOContext {
        val file = prepareChapterFile(chapterUrl) as? com.hippo.unifile.RemoteStorageFile ?: error("Not a network file")
        check(describeFileTransfer(chapterUrl) == expected) { "File changed before download" }
        val entry = file.runtime.backend.stat(file.storagePath)
        if (entry.directory) {
            require(offset == 0L)
            val images = file.runtime.backend.list(file.storagePath).filter {
                !it.directory &&
                    LocalMediaFormats.isImage(it.name.substringAfterLast('.'))
            }
                .sortedBy { it.path }
            val zip = java.util.zip.ZipOutputStream(output)
            for (image in images) {
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(java.util.zip.ZipEntry(image.name))
                file.runtime.backend.copyTo(image, zip)
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        } else {
            require(offset in 0..entry.size)
            var position = offset
            while (position < entry.size) {
                currentCoroutineContext().ensureActive()
                val bytes = try {
                    file.runtime.backend.read(entry, position, minOf(256 * 1024L, entry.size - position).toInt())
                } catch (failure: koharia.storage.StorageFailure) {
                    if (failure.reason == koharia.storage.StorageFailure.Reason.UNSUPPORTED && offset > 0) {
                        throw koharia.connection.ConnectionFileTransferRestartRequired()
                    }
                    if (failure.reason != koharia.storage.StorageFailure.Reason.UNSUPPORTED ||
                        position != 0L
                    ) {
                        throw failure
                    }
                    file.runtime.backend.copyTo(entry, output)
                    break
                }
                output.write(bytes)
                position += bytes.size
            }
        }
        check(describeFileTransfer(chapterUrl) == expected) { "File changed during download" }
    }

    override suspend fun fileTransferCheckpoint(chapterUrl: String) =
        koharia.storage.NetworkStorageRuntime.get(context, id).records
            .get<koharia.connection.ConnectionFileTransferCheckpoint>("transfers", chapterUrl)
    override suspend fun saveFileTransferCheckpoint(
        chapterUrl: String,
        checkpoint: koharia.connection.ConnectionFileTransferCheckpoint,
    ) = koharia.storage.NetworkStorageRuntime.get(context, id).records.put("transfers", chapterUrl, checkpoint)
    override suspend fun clearFileTransferCheckpoint(chapterUrl: String) {
        val records = koharia.storage.NetworkStorageRuntime.get(context, id).records
        records.list("transfers").firstOrNull { it.key == chapterUrl }?.let {
            records.remove("transfers", chapterUrl, it.revision)
        }
    }

    override fun toString(): String = name

    override fun chapterThumbnailUrl(chapterUrl: String): String = chapterUrl

    override suspend fun loadChapterThumbnail(chapterUrl: String): ByteArray? = withIOContext {
        val file = prepareChapterFile(chapterUrl) ?: return@withIOContext null
        indexedEntry(chapterUrl)?.takeIf { it.kind == LocalLibraryItem.Kind.FOLDER }?.let {
            return@withIOContext folderCover(it, mutableSetOf())
        }
        runCatching { firstImageBytes(file) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                logcat(LogPriority.WARN, error) { "Unable to read local chapter thumbnail" }
            }
            .getOrNull()
    }

    override fun availableContentScopes(): Set<LibraryContentScope> {
        return localLibraryContentScopes(preferences.getConfig())
    }

    override fun contentScopesChanges(): Flow<Set<LibraryContentScope>> {
        return preferences.configChanges().map(::localLibraryContentScopes)
    }

    override fun createBrowseScreen(
        scope: LibraryContentScope,
        listingQuery: String?,
        showNavigationUp: Boolean,
    ): ConnectionBrowseScreen = LocalLibraryScreen(
        sourceId = id,
        scope = scope,
        initialQuery = listingQuery,
        showNavigationUp = showNavigationUp,
    )

    override suspend fun refreshLibrary(): Result<ConnectionLibraryRefreshResult> {
        return Injekt.get<LocalLibraryRefreshTasks>().refresh(id) {
            refreshMutex.withLock {
                scanLibrary().also { mutableLibraryRefreshes.emit(it) }
            }
        }
    }

    internal fun startLibraryRefresh() {
        Injekt.get<LocalLibraryRefreshTasks>().start(id) {
            refreshMutex.withLock { scanLibrary().also { mutableLibraryRefreshes.emit(it) } }
        }
    }

    internal suspend fun resumePendingNetworkScan() {
        if (supportsFileTransfers && needsInitialScan()) startLibraryRefresh()
    }

    suspend fun needsInitialScan(): Boolean = withIOContext {
        for (root in preferences.getConfig().roots) {
            val remote = koharia.storage.NetworkStorageRuntime.fromUri(context, Uri.parse(root.treeUri))
            if (remote?.runtime?.snapshot?.hasPendingScan() == true) return@withIOContext true
        }
        val index = preferences.getIndex()
        if (index.scannedAt <= 0L || index.schemaVersion < 5) return@withIOContext true
        val indexedUrls = index.items.asSequence()
            .filter {
                it.kind in setOf(
                    LocalLibraryItem.Kind.SERIES,
                    LocalLibraryItem.Kind.FILE_ENTRY,
                    LocalLibraryItem.Kind.FOLDER,
                ) &&
                    it.rootId.isNotBlank()
            }
            .map { item -> LocalLibraryLocator.entryUrl(id, item.rootId, item.locatorPath) }
            .toSet()
        if (indexedUrls.isEmpty()) return@withIOContext false
        val storedUrls = mangaRepository.getMangaBySourceId(id).mapTo(mutableSetOf(), Manga::url)
        !storedUrls.containsAll(indexedUrls)
    }

    internal suspend fun prepareFileDeletion(mangas: List<Manga>): LocalLibraryDeletionPlan = withIOContext {
        refreshMutex.withLock {
            val roots = preferences.getConfig().roots
            val directories = roots.associateWith { preferences.resolveRoot(context, it) }
            val protectedDirectories = directories.values.filterNotNull().map(::localDeletionIdentity).toSet()
            val index = preferences.getIndex()
            val entries = mangas.distinctBy(Manga::id).map { manga ->
                require(manga.source == id)
                val item = index.items.firstOrNull {
                    it.kind != LocalLibraryItem.Kind.CHAPTER &&
                        LocalLibraryLocator.entryUrl(id, it.rootId, it.locatorPath) == manga.url
                } ?: error("Local library entry is no longer available")
                val root = roots.first { it.id == item.rootId }
                LocalLibraryDeletionEntry(
                    manga = manga,
                    root = root,
                    item = item,
                    deletion = LocalFileDeletion.prepare(
                        root = checkNotNull(directories[root]),
                        path = localImageSeriesPhysicalPath(item.relativePath) ?: item.relativePath,
                        series = item.kind != LocalLibraryItem.Kind.FILE_ENTRY && !item.imageComic,
                        protectedDirectories = protectedDirectories,
                    ),
                )
            }
            require(entries.isNotEmpty())
            LocalLibraryDeletionPlan(roots, entries)
        }
    }

    internal suspend fun deleteLocalFiles(plan: LocalLibraryDeletionPlan): LocalLibraryDeletionResult = withIOContext {
        // Once file deletion begins, finish reconciling the index even if the screen is closed.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            refreshMutex.withLock {
                check(preferences.getConfig().roots == plan.roots)
                val deleted = mutableListOf<Manga>()
                val failed = mutableListOf<Manga>()
                val existingMangas = mangaRepository.getMangaBySourceId(id)
                for (entry in plan.entries) {
                    try {
                        entry.deletion.delete()
                        val removedItems = preferences.getIndex().items.filter { item ->
                            item.rootId == entry.root.id && (
                                item.itemKey == entry.item.itemKey ||
                                    (
                                        entry.item.kind != LocalLibraryItem.Kind.FILE_ENTRY &&
                                            item.relativePath.startsWith("${entry.item.relativePath}/")
                                        )
                                )
                        }
                        val urls = removedItems.mapTo(mutableSetOf()) {
                            LocalLibraryLocator.entryUrl(id, it.rootId, it.locatorPath)
                        }
                        existingMangas.filter { it.url in urls }.forEach { manga ->
                            coverCache.deleteFromCache(manga)
                            preferences.rememberCustomCover(manga.url)
                            mangaRepository.deleteMangaById(manga.id)
                        }
                        preferences.removeDeletedItems(removedItems.mapTo(mutableSetOf()) { it.itemKey })
                        deleted += entry.manga
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Unable to delete local library entry" }
                        failed += entry.manga
                    }
                }
                if (failed.isNotEmpty()) {
                    runCatching { scanLibrary() }.onFailure { error ->
                        logcat(LogPriority.WARN, error) { "Unable to refresh partially deleted local entries" }
                    }
                }
                mutableLibraryRefreshes.emit(
                    ConnectionLibraryRefreshResult(
                        itemCount = preferences.getIndex().items.count { it.kind != LocalLibraryItem.Kind.CHAPTER },
                        refreshedAt = System.currentTimeMillis(),
                    ),
                )
                LocalLibraryDeletionResult(deleted, failed)
            }
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = indexedMangaPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage = indexedMangaPage(latestFirst = true)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        indexedMangaPage(
            query = query,
            scope = filters.filterIsInstance<LocalLibraryScopeFilter>().firstOrNull()?.scope,
            filters = filters.localLibraryFilters(),
            bookshelfId = filters.localBookshelfId(),
        )

    override suspend fun filterLibraryEntries(mangas: List<Manga>): List<Manga> {
        return browseIndexedLibrary(mangas = mangas, query = "", hierarchical = false)
    }

    internal suspend fun browseIndexedLibrary(
        query: String,
        scope: LibraryContentScope? = null,
        filters: LocalLibraryFilters = LocalLibraryFilters(),
        bookshelfId: String? = null,
    ): List<Manga> = browseIndexedLibrary(
        mangas = mangaRepository.getMangaBySourceId(id),
        query = query,
        scope = scope,
        filters = filters,
        bookshelfId = bookshelfId,
    )

    internal suspend fun browseIndexedLibrary(
        mangas: List<Manga>,
        query: String,
        scope: LibraryContentScope? = null,
        filters: LocalLibraryFilters = LocalLibraryFilters(),
        bookshelfId: String? = null,
        parentUrl: String? = null,
        hierarchical: Boolean = true,
    ): List<Manga> = withIOContext {
        val index = preferences.getIndex()
        val assignments = preferences.getBookshelfAssignments()
        val config = preferences.getConfig()
        val rootsById = config.roots.associateBy { it.id }
        val libraryItems = index.libraryItemsByKey
        val chapterNames = index.chapterNamesBySeriesKey
        val chapterFormats = index.chapterFormatsBySeriesKey
        val shelfDescendantKeys = if (bookshelfId == null) {
            emptySet()
        } else {
            libraryItems.values
                .asSequence()
                .filter { item ->
                    val root = rootsById[item.rootId] ?: return@filter false
                    item.metadataRole(config.organizationMode(root)).isClassifiable() &&
                        config.effectiveBookshelfId(
                            root = root,
                            itemKey = item.itemKey,
                            assignments = assignments,
                            contentType = item.contentType,
                        ) == bookshelfId
                }
                .mapTo(mutableSetOf(), LocalLibraryItem::itemKey)
        }
        val parent = parentUrl?.let(::indexedEntry)
        if (parentUrl != null && parent == null) return@withIOContext emptyList()

        val filtered = mangas
            .mapNotNull { manga ->
                val location = LocalLibraryLocator.location(manga.url, id) ?: return@mapNotNull null
                val rootId = location.rootId ?: return@mapNotNull null
                val itemKey = LocalLibraryLocator.itemKey(rootId, location.relativePath)
                val indexedItem = libraryItems[itemKey] ?: return@mapNotNull null
                val root = rootsById[rootId] ?: return@mapNotNull null
                if (hierarchical && config.organizationMode(root) == LocalLibraryOrganizationMode.FOLDER) {
                    if (!indexedItem.isInFolder(
                            parent?.rootId,
                            parent?.relativePath,
                            query.isNotBlank(),
                        )
                    ) {
                        return@mapNotNull null
                    }
                } else if (parentUrl != null) {
                    return@mapNotNull null
                }
                if (!indexedItem.contentType.matches(scope)) return@mapNotNull null
                if (bookshelfId != null) {
                    val role = indexedItem.metadataRole(config.organizationMode(root))
                    if (role.isClassifiable()) {
                        if (config.effectiveBookshelfId(
                                root = root,
                                itemKey = itemKey,
                                assignments = assignments,
                                contentType = indexedItem.contentType,
                            ) != bookshelfId
                        ) {
                            return@mapNotNull null
                        }
                    } else if (shelfDescendantKeys.none { descendantKey ->
                            val descendant = libraryItems[descendantKey] ?: return@none false
                            descendant.rootId == indexedItem.rootId &&
                                descendant.relativePath.startsWith("${indexedItem.relativePath}/")
                        }
                    ) {
                        return@mapNotNull null
                    }
                }
                manga.takeIf {
                    it.matchesIndexedLibrary(
                        query = query,
                        filters = filters,
                        folderName = indexedItem.relativePath.substringAfterLast('/'),
                        format = if (indexedItem.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                            indexedItem.format
                        } else {
                            chapterFormats[itemKey].orEmpty()
                        },
                        chapterNames = if (indexedItem.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                            listOf(indexedItem.relativePath)
                        } else {
                            chapterNames[itemKey].orEmpty()
                        },
                    )
                }
            }
        val sorted = when (filters.sort) {
            // IDs preserve first insertion order, including entries indexed before sorting existed.
            1 -> filtered.sortedBy(Manga::id)
            2 -> filtered.sortedBy { manga ->
                val location = LocalLibraryLocator.location(manga.url, id)
                location?.rootId?.let { rootId ->
                    libraryItems[LocalLibraryLocator.itemKey(rootId, location.relativePath)]?.modifiedAt
                } ?: 0L
            }
            else -> filtered.sortedWith { first, second ->
                first.title.compareToCaseInsensitiveNaturalOrder(second.title)
            }
        }
        val ordered = if (filters.descending) sorted.reversed() else sorted
        if (filters.foldersFirst) {
            ordered.sortedBy {
                indexedEntry(it.url)?.kind != LocalLibraryItem.Kind.FOLDER
            }
        } else {
            ordered
        }
    }

    private suspend fun indexedMangaPage(
        query: String = "",
        scope: LibraryContentScope? = null,
        filters: LocalLibraryFilters = LocalLibraryFilters(),
        bookshelfId: String? = null,
        latestFirst: Boolean = false,
    ): MangasPage {
        val mangas = browseIndexedLibrary(query, scope, filters, bookshelfId)
        val sorted = if (latestFirst) {
            val modifiedByItemKey = preferences.getIndex().items
                .filter {
                    it.kind in setOf(
                        LocalLibraryItem.Kind.SERIES,
                        LocalLibraryItem.Kind.FILE_ENTRY,
                        LocalLibraryItem.Kind.FOLDER,
                    )
                }
                .associate { it.itemKey to it.modifiedAt }
            mangas.sortedByDescending { manga ->
                val location = LocalLibraryLocator.location(manga.url, id)
                location?.rootId?.let { LocalLibraryLocator.itemKey(it, location.relativePath) }
                    ?.let(modifiedByItemKey::get)
                    ?: 0L
            }
        } else {
            mangas
        }
        return MangasPage(sorted.map(Manga::toSManga), false)
    }

    override suspend fun currentLibraryShelfId(mangaUrl: String): String? {
        val resource = resolveResource(mangaUrl) ?: return null
        val indexedItem = indexedLibraryItem(resource) ?: return null
        if (!indexedItem.metadataRole(preferences.getConfig().organizationMode(resource.root)).isClassifiable()) {
            return null
        }
        val itemKey = indexedLibraryItem(resource)?.itemKey
            ?: LocalLibraryLocator.itemKey(
                resource.root.id,
                resource.relativePath,
            )
        return preferences.getConfig().effectiveBookshelfId(
            root = resource.root,
            itemKey = itemKey,
            assignments = preferences.getBookshelfAssignments(),
            contentType = indexedItem.contentType,
        )
    }

    override fun isLibraryShelfAssignable(mangaUrl: String): Boolean {
        val item = indexedEntry(mangaUrl) ?: return false
        val config = preferences.getConfig()
        val root = config.roots.firstOrNull { it.id == item.rootId } ?: return false
        return item.metadataRole(config.organizationMode(root)).isClassifiable()
    }

    override suspend fun readerContentScope(
        manga: Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
    ): LibraryContentScope {
        val shelfId = currentLibraryShelfId(manga.url)
        libraryShelves.first().firstOrNull { it.id == shelfId }?.let { return it.contentScope }
        return when (resolveResource(chapter.url)?.root?.contentType) {
            LocalLibraryContentType.COMICS -> LibraryContentScope.COMIC
            LocalLibraryContentType.BOOKS -> LibraryContentScope.BOOK
            else -> LibraryContentScope.ALL
        }
    }

    override suspend fun compatibleLibraryShelves(mangaUrl: String): List<ConnectionLibraryShelf> {
        val resource = resolveResource(mangaUrl) ?: return emptyList()
        val item = indexedLibraryItem(resource) ?: return emptyList()
        if (!item.metadataRole(preferences.getConfig().organizationMode(resource.root)).isClassifiable()) {
            return emptyList()
        }
        val mode = item.organizationMode()
        return preferences.getConfig().bookshelvesFor(item.contentType)
            .filter { it.organizationMode == mode }
            .map { it.toConnectionLibraryShelf(context) }
    }

    override suspend fun moveMangaToLibraryShelf(mangaUrl: String, shelfId: String): Result<Unit> = runCatching {
        val resource = resolveResource(mangaUrl) ?: error("Invalid local manga URL")
        val item = indexedLibraryItem(resource) ?: error("Local library entry is not indexed")
        require(item.metadataRole(preferences.getConfig().organizationMode(resource.root)).isClassifiable()) {
            "Local folder entries cannot be assigned to a bookshelf"
        }
        val validShelfIds = preferences.getConfig()
            .bookshelvesFor(item.contentType)
            .filter { it.organizationMode == item.organizationMode() }
            .mapTo(mutableSetOf()) { it.id }
        require(shelfId in validShelfIds) { "Bookshelf does not match local content type or organization mode" }
        preferences.setBookshelfAssignment(
            itemKey = indexedLibraryItem(resource)?.itemKey
                ?: LocalLibraryLocator.itemKey(
                    resource.root.id,
                    resource.relativePath,
                ),
            bookshelfId = shelfId,
        )
    }

    override suspend fun mediaImportDestinations(): List<ConnectionMediaImportDestination> = withIOContext {
        val config = preferences.getConfig()
        config.roots.mapNotNull { root ->
            val directory = preferences.resolveRoot(context, root) ?: return@mapNotNull null
            // Let the provider resolve tree grants and document URIs instead of comparing URI strings.
            if (!directory.canWrite()) return@mapNotNull null
            val organizationMode = config.organizationMode(root)
            val directoryName = root.displayPath.ifBlank { root.treeUri }
            val bookshelfName = config.bookshelf(
                root.bookshelfId.ifBlank { config.defaultBookshelfId(root.contentType) },
            )
                ?.name
                ?.takeIf(String::isNotBlank)
            ConnectionMediaImportDestination(
                id = root.id,
                name = listOfNotNull(
                    directoryName,
                    bookshelfName,
                    context.stringResource(
                        when (organizationMode) {
                            LocalLibraryOrganizationMode.FOLDER -> MR.strings.local_library_mode_folder
                            LocalLibraryOrganizationMode.SERIES -> MR.strings.local_library_mode_series
                            LocalLibraryOrganizationMode.INDIVIDUAL_FILES ->
                                MR.strings.local_library_mode_individual
                        },
                    ),
                ).joinToString(" · "),
                mediaType = root.contentType.toConnectionMediaType(),
                supportedExtensions = if (organizationMode == LocalLibraryOrganizationMode.FOLDER) {
                    supportedExtensions(root.contentType)
                } else {
                    when (root.contentType) {
                        LocalLibraryContentType.COMICS -> LocalMediaFormats.comicImportExtensions
                        LocalLibraryContentType.BOOKS -> BOOK_LIBRARY_EXTENSIONS
                        LocalLibraryContentType.MIXED -> LocalMediaFormats.documentImportExtensions
                    }
                },
                defaultShelfId = root.bookshelfId.ifBlank { config.defaultBookshelfId(root.contentType) }
                    .ifBlank { null },
                grouping = when (organizationMode) {
                    LocalLibraryOrganizationMode.SERIES -> ConnectionMediaGrouping.SERIES
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                    LocalLibraryOrganizationMode.FOLDER,
                    ->
                        ConnectionMediaGrouping.INDIVIDUAL
                },
                compatibleShelfIds = config.bookshelvesFor(root.contentType)
                    .filter { it.organizationMode == organizationMode }
                    .mapTo(mutableSetOf(), LocalBookshelf::id),
            )
        }
    }

    override suspend fun mediaImportSeries(destinationId: String): List<ConnectionMediaImportSeries> = withIOContext {
        val config = preferences.getConfig()
        val root = config.roots.firstOrNull { it.id == destinationId } ?: return@withIOContext emptyList()
        if (config.organizationMode(root) != LocalLibraryOrganizationMode.SERIES) {
            return@withIOContext emptyList()
        }
        val assignments = preferences.getBookshelfAssignments()
        val mangasByUrl = mangaRepository.getMangaBySourceId(id).associateBy(Manga::url)
        preferences.getIndex().items
            .asSequence()
            .filter { it.kind == LocalLibraryItem.Kind.SERIES && it.rootId == root.id && !it.missing }
            .map { item ->
                val resourceUrl = entryUrl(item)
                ConnectionMediaImportSeries(
                    id = resourceUrl,
                    name = mangasByUrl[resourceUrl]?.title
                        ?.takeIf(String::isNotBlank)
                        ?: item.relativePath.substringAfterLast('/'),
                    destinationId = root.id,
                    shelfId = config.effectiveBookshelfId(
                        root = root,
                        itemKey = item.itemKey,
                        assignments = assignments,
                        contentType = item.contentType,
                    ),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ConnectionMediaImportSeries::name))
            .toList()
    }

    internal suspend fun folderImportDestination(url: String): ConnectionMediaImportDestination? = withIOContext {
        val entry = indexedEntry(url)?.takeIf { !it.missing && it.kind == LocalLibraryItem.Kind.FOLDER }
            ?: return@withIOContext null
        if (resolveResource(url)?.file?.canWrite() != true) return@withIOContext null
        mediaImportDestinations().firstOrNull {
            it.id == entry.rootId &&
                it.grouping == ConnectionMediaGrouping.INDIVIDUAL
        }
            ?.let { destination ->
                destination.copy(
                    name = entry.relativePath,
                    compatibleShelfIds = setOfNotNull(destination.defaultShelfId),
                )
            }
    }

    override suspend fun importMedia(request: ConnectionMediaImportRequest): Result<ConnectionMediaImportResult> {
        return try {
            Result.success(
                withIOContext {
                    val config = preferences.getConfig()
                    val root = config.roots.firstOrNull { it.id == request.destinationId }
                        ?: error("Local import destination no longer exists")
                    val organizationMode = config.organizationMode(root)
                    val target = request.targetFolderUrl?.let { url ->
                        require(organizationMode == LocalLibraryOrganizationMode.FOLDER)
                        val entry = checkNotNull(indexedEntry(url))
                        require(entry.rootId == root.id && !entry.missing && entry.kind == LocalLibraryItem.Kind.FOLDER)
                        checkNotNull(resolveResource(url)).also { require(it.file.isDirectory) }
                    }
                    val destination = target?.file ?: preferences.resolveRoot(context, root)
                        ?: error("Local import destination is unavailable")
                    val supportedExtensions = if (organizationMode == LocalLibraryOrganizationMode.FOLDER) {
                        supportedExtensions(root.contentType)
                    } else {
                        when (root.contentType) {
                            LocalLibraryContentType.COMICS -> LocalMediaFormats.comicImportExtensions
                            LocalLibraryContentType.BOOKS -> BOOK_LIBRARY_EXTENSIONS
                            LocalLibraryContentType.MIXED -> LocalMediaFormats.documentImportExtensions
                        }
                    }
                    require(request.items.isNotEmpty()) { "No media selected for import" }
                    require(request.items.all { it.extension.orEmpty().lowercase() in supportedExtensions }) {
                        "Imported media does not match the destination type"
                    }
                    val importContentType = when (root.contentType) {
                        LocalLibraryContentType.MIXED -> if (
                            request.items.all { it.extension.orEmpty().lowercase() in BOOK_LIBRARY_EXTENSIONS }
                        ) {
                            LocalLibraryContentType.BOOKS
                        } else {
                            LocalLibraryContentType.COMICS
                        }
                        else -> root.contentType
                    }
                    request.shelfId?.let { shelfId ->
                        require(
                            config.bookshelvesFor(importContentType).any {
                                it.id == shelfId && it.organizationMode == organizationMode
                            },
                        ) {
                            "Bookshelf does not match imported media type or organization mode"
                        }
                    }
                    if (organizationMode != LocalLibraryOrganizationMode.SERIES) {
                        return@withIOContext importIndividualMedia(
                            root = root,
                            destination = destination,
                            request = request,
                            parentPath = target?.relativePath.orEmpty(),
                        )
                    }
                    val existingResource = request.existingSeriesId?.let { existingSeriesId ->
                        resolveResource(existingSeriesId)
                            ?.takeIf { it.root.id == root.id && it.file.isDirectory }
                            ?: error("Existing import series is unavailable")
                    }
                    val seriesName = existingResource?.relativePath
                        ?: sanitizeImportName(request.seriesName).also { name ->
                            require(name.isNotBlank()) { "Series name cannot be empty" }
                        }
                    if (existingResource == null) {
                        require(destination.findFile(seriesName) == null) {
                            "Series already exists; choose the existing series instead"
                        }
                    }
                    val existingSeriesDirectory = existingResource?.file
                    val seriesDirectory = existingSeriesDirectory
                        ?: destination.createDirectory(seriesName)
                        ?: error("Unable to create series directory")
                    val importedFiles = mutableListOf<UniFile>()
                    val importedNames = mutableListOf<String>()
                    var itemKey = existingResource?.let(::indexedLibraryItem)?.itemKey
                        ?: LocalLibraryLocator.itemKey(root.id, seriesName)
                    val previousShelfId = preferences.getBookshelfAssignments()[itemKey]

                    fun rollBackImport() {
                        importedFiles.forEach(UniFile::delete)
                        if (existingSeriesDirectory == null) {
                            seriesDirectory.delete()
                        }
                        when (previousShelfId) {
                            null -> preferences.clearBookshelfAssignment(itemKey)
                            else -> preferences.setBookshelfAssignment(itemKey, previousShelfId)
                        }
                    }

                    try {
                        request.items.forEach { item ->
                            val fileName = uniqueImportName(
                                seriesDirectory,
                                sanitizeImportFileName(item.displayName, item.extension),
                            )
                            val temporaryName = ".$fileName.importing-${System.currentTimeMillis()}"
                            val temporary = seriesDirectory.createFile(temporaryName)
                                ?: error("Unable to create temporary import file")
                            try {
                                val copied = context.contentResolver.openInputStream(android.net.Uri.parse(item.uri))
                                    ?.use { input ->
                                        temporary.openOutputStream().use { output -> input.copyTo(output) }
                                    }
                                    ?: error("Unable to open imported media")
                                require(item.sizeBytes == null || item.sizeBytes < 0L || copied == item.sizeBytes) {
                                    "Imported media size verification failed"
                                }
                                check(temporary.renameTo(fileName)) { "Unable to finalize imported media" }
                                val imported = seriesDirectory.findFile(fileName)
                                    ?: error("Imported media is unavailable after copy")
                                importedFiles += imported
                                importedNames += fileName
                            } catch (error: Throwable) {
                                temporary.delete()
                                throw error
                            }
                        }
                        request.shelfId?.takeIf(String::isNotBlank)?.let { shelfId ->
                            preferences.setBookshelfAssignment(itemKey, shelfId)
                        }
                        refreshAfterImport(root.id)
                        val scannedKey = preferences.getIndex().itemsByLocation[root.id to seriesName]?.itemKey
                        if (scannedKey != null && scannedKey != itemKey) {
                            val assigned = preferences.getBookshelfAssignments()[itemKey]
                            preferences.clearBookshelfAssignment(itemKey)
                            itemKey = scannedKey
                            assigned?.let { preferences.setBookshelfAssignment(itemKey, it) }
                        }
                    } catch (error: Throwable) {
                        rollBackImport()
                        throw error
                    }
                    ConnectionMediaImportResult(
                        resourceUrls = listOf(
                            preferences.getIndex().itemsByLocation[root.id to seriesName]?.let(::entryUrl)
                                ?: existingResource?.let { request.existingSeriesId }
                                ?: LocalLibraryLocator.seriesUrl(id, root.id, seriesName),
                        ),
                        importedFileNames = importedNames,
                    )
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun importIndividualMedia(
        root: LocalLibraryRootConfig,
        destination: UniFile,
        request: ConnectionMediaImportRequest,
        parentPath: String = "",
    ): ConnectionMediaImportResult {
        require(request.existingSeriesId == null) { "Individual-file libraries do not accept a series target" }
        val importedFiles = mutableListOf<UniFile>()
        val importedNames = mutableListOf<String>()
        val previousAssignments = mutableMapOf<String, String?>()

        fun rollBackImport() {
            importedFiles.forEach(UniFile::delete)
            previousAssignments.forEach { (itemKey, previousShelfId) ->
                when (previousShelfId) {
                    null -> preferences.clearBookshelfAssignment(itemKey)
                    else -> preferences.setBookshelfAssignment(itemKey, previousShelfId)
                }
            }
        }

        try {
            request.items.forEach { item ->
                val fileName = uniqueImportName(
                    destination,
                    sanitizeImportFileName(item.displayName, item.extension),
                )
                val temporaryName = ".$fileName.importing-${System.currentTimeMillis()}"
                val temporary = destination.createFile(temporaryName)
                    ?: error("Unable to create temporary import file")
                try {
                    val copied = context.contentResolver.openInputStream(Uri.parse(item.uri))
                        ?.use { input -> temporary.openOutputStream().use { output -> input.copyTo(output) } }
                        ?: error("Unable to open imported media")
                    require(item.sizeBytes == null || item.sizeBytes < 0L || copied == item.sizeBytes) {
                        "Imported media size verification failed"
                    }
                    check(temporary.renameTo(fileName)) { "Unable to finalize imported media" }
                    val imported = destination.findFile(fileName)
                        ?: error("Imported media is unavailable after copy")
                    importedFiles += imported
                    importedNames += fileName
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
            }
            refreshAfterImport(root.id)
            importedNames.forEach { fileName ->
                val path = listOf(parentPath, fileName).filter(String::isNotBlank).joinToString("/")
                val itemKey = preferences.getIndex().itemsByLocation[root.id to path]?.itemKey
                    ?: LocalLibraryLocator.itemKey(root.id, path)
                previousAssignments[itemKey] = preferences.getBookshelfAssignments()[itemKey]
                request.shelfId?.takeIf(String::isNotBlank)?.let { preferences.setBookshelfAssignment(itemKey, it) }
            }
        } catch (error: Throwable) {
            rollBackImport()
            throw error
        }

        return ConnectionMediaImportResult(
            resourceUrls = importedNames.map { fileName ->
                val path = listOf(parentPath, fileName).filter(String::isNotBlank).joinToString("/")
                preferences.getIndex().itemsByLocation[root.id to path]?.let(::entryUrl)
                    ?: LocalLibraryLocator.entryUrl(id, root.id, path)
            },
            importedFileNames = importedNames,
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        hydrateNetworkDirectory(manga.url)
        val indexedItem = indexedEntry(manga.url)
        indexedEntry(manga.url)?.takeIf { it.kind == LocalLibraryItem.Kind.FOLDER }?.let {
            applyFolderDisplay(manga, it)
            // Keep a cover discovered during indexing. Replacing it with the folder URL here
            // invalidates the shared cover cache whenever the details screen is opened.
            if (manga.thumbnail_url.isNullOrBlank()) manga.thumbnail_url = manga.url
            manga.initialized = true
            return@withIOContext manga
        }
        resolveResource(manga.url)?.let { resource ->
            if (indexedItem?.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                applyIndividualMetadata(
                    manga,
                    resource,
                    preferences.getMetadataOverrides(),
                    role = metadataRole(resource),
                )
            } else {
                val files = resource.file.listFiles().orEmpty()
                    .filterNot { it.name.orEmpty().startsWith('.') }
                applySeriesMetadata(
                    manga = manga,
                    root = resource.root,
                    directory = resource.file,
                    relativePath = resource.relativePath,
                    files = files,
                    metadataOverrides = preferences.getMetadataOverrides(),
                    itemKey = indexedItem?.itemKey,
                )
            }
            applyStoredMetadata(manga, resource)
        }
        manga.initialized = true
        manga
    }

    private fun applyStoredMetadata(manga: SManga, resource: ResolvedLocalResource) {
        val item = indexedLibraryItem(resource) ?: return
        val role = metadataRole(resource)
        if (!role.isMetadataReadable()) return
        metadataStore.externalMetadata(
            item.itemKey,
            metadataDirectory(resource),
            item.contentType,
            resource.file.takeUnless(UniFile::isDirectory)?.name,
            role = role,
        )?.applyTo(manga)
        preferences.getMetadataOverrides()[item.itemKey]?.applyTo(manga)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        hydrateNetworkDirectory(manga.url)
        IncomingMediaSessionLocator.location(manga.url, id)
            ?.takeIf { it.fileName == null }
            ?.let { location ->
                return@withIOContext IncomingMediaSessionLocator.sessionDirectory(context, location.sessionId)
                    ?.listFiles()
                    .orEmpty()
                    .filter(File::isFile)
                    .map { file ->
                        SChapter.create().apply {
                            url = IncomingMediaSessionLocator.chapterUrl(id, location.sessionId, file.name)
                            name = file.nameWithoutExtension
                            date_upload = file.lastModified()
                            chapter_number = 1F
                        }
                    }
            }
        val resource = resolveResource(manga.url) ?: return@withIOContext emptyList()
        val indexedItem = indexedLibraryItem(resource)
        if (indexedItem?.kind == LocalLibraryItem.Kind.FOLDER && !indexedItem.imageComic) {
            return@withIOContext emptyList()
        }
        val existingChaptersByUrl = mangaRepository.getMangaByUrlAndSourceId(manga.url, id)
            ?.let { storedManga -> getChaptersByMangaId.await(storedManga.id) }
            .orEmpty()
            .associateBy(Chapter::url)
        if (indexedItem?.kind == LocalLibraryItem.Kind.FILE_ENTRY ||
            (indexedItem?.kind == LocalLibraryItem.Kind.FOLDER && indexedItem.imageComic)
        ) {
            val chapterUrl = LocalLibraryLocator.chapterUrl(
                id,
                resource.root.id,
                indexedLibraryItem(resource)?.locatorPath
                    ?: resource.relativePath,
            )
            val modifiedAt = resource.file.lastModified()
            return@withIOContext listOf(
                SChapter.create().apply {
                    url = chapterUrl
                    name = if (resource.file.isDirectory) {
                        resource.file.name.orEmpty()
                    } else {
                        resource.file.nameWithoutExtension.orEmpty()
                    }
                    date_upload = modifiedAt
                    chapter_number = 1F
                    memo =
                        documentPageMemo(
                            resource.file,
                            modifiedAt,
                            existingChaptersByUrl[chapterUrl],
                            resource.root.id,
                            resource.relativePath,
                        )
                },
            )
        }
        val chapters = resource.file.listFiles().orEmpty()
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter(::isSupportedChapter)
            .map { file ->
                val relative = "${resource.relativePath}/${file.name.orEmpty()}"
                val chapterLocator = preferences.getIndex().itemsByLocation[resource.root.id to relative]?.locatorPath
                    ?: relative
                val chapterUrl = LocalLibraryLocator.chapterUrl(id, resource.root.id, chapterLocator)
                val modifiedAt = file.lastModified()
                SChapter.create().apply {
                    url = chapterUrl
                    name = if (file.isDirectory) file.name.orEmpty() else file.nameWithoutExtension.orEmpty()
                    date_upload = modifiedAt
                    chapter_number = ChapterRecognition.parseChapterNumber(
                        manga.title,
                        name,
                        chapter_number.toDouble(),
                    ).toFloat()
                    memo =
                        documentPageMemo(
                            file,
                            modifiedAt,
                            existingChaptersByUrl[chapterUrl],
                            resource.root.id,
                            relative,
                        )
                }
            }
            .sortedWith { first, second ->
                second.name.compareToCaseInsensitiveNaturalOrder(first.name)
            }
        preferences.clearPendingChapterRefresh(
            indexedItem?.itemKey ?: LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath),
        )
        chapters
    }

    override fun shouldRefreshChapters(manga: Manga, nowMillis: Long): Boolean {
        val location = LocalLibraryLocator.location(manga.url, id) ?: return false
        val rootId = location.rootId ?: return false
        val itemKey = LocalLibraryLocator.itemKey(rootId, location.relativePath)
        return itemKey in preferences.getIndex().pendingChapterRefreshItemKeys
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        throw UnsupportedOperationException("Local pages are loaded directly by the reader")
    }

    override fun getFilterList(): FilterList = LocalLibraryFilters().toFilterList(LibraryContentScope.ALL)

    override suspend fun readMetadata(resourceUrl: String): LibraryMetadata? {
        val resource = resolveResource(resourceUrl) ?: return null
        val entry = indexedLibraryItem(resource)
        val role = metadataRole(resource)
        if (role == LocalMetadataRole.FOLDER_CONTAINER) {
            val settings = preferences.folderDisplaySettings(
                entry?.itemKey ?: LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath),
            )
            return LibraryMetadata(
                title = settings.displayName,
                author = settings.author,
                description = settings.description,
                genres = settings.tags,
                lockedFields = settings.effectiveLockedFields(),
                source = "user",
            )
        }
        if (!role.isMetadataReadable()) return null
        val external = metadataStore.externalMetadata(
            entry?.itemKey
                ?: LocalLibraryLocator.itemKey(
                    resource.root.id,
                    resource.relativePath,
                ),
            metadataDirectory(resource),
            entry?.contentType
                ?: resource.root.contentType,
            resource.file.takeUnless(UniFile::isDirectory)?.name,
            role = role,
            observe = true,
        )
        val overrides = preferences.getMetadataOverrides()
        return (
            overrides[
                indexedLibraryItem(resource)?.itemKey
                    ?: LocalLibraryLocator.itemKey(
                        resource.root.id,
                        resource.relativePath,
                    ),
            ]
                ?: overrides[LocalLibraryLocator.legacyItemKey(legacyRelativePath(resource))]
                ?: external
            )?.toLibraryMetadata()
    }

    override fun isMetadataEditable(resourceUrl: String): Boolean {
        return editableMetadataFields(resourceUrl).isNotEmpty()
    }

    override fun editableMetadataFields(resourceUrl: String): Set<LibraryMetadataField> {
        val item = indexedEntry(resourceUrl) ?: return emptySet()
        val config = preferences.getConfig()
        val root = config.roots.firstOrNull { it.id == item.rootId } ?: return emptySet()
        return when (item.metadataRole(config.organizationMode(root))) {
            LocalMetadataRole.FOLDER_CONTAINER -> setOf(
                LibraryMetadataField.TITLE,
                LibraryMetadataField.AUTHOR,
                LibraryMetadataField.DESCRIPTION,
                LibraryMetadataField.GENRES,
            )
            LocalMetadataRole.CHAPTER -> emptySet()
            else -> LibraryMetadataField.entries.toSet()
        }
    }

    override suspend fun updateMetadata(resourceUrl: String, metadata: LibraryMetadata): Result<Unit> =
        saveMetadata(resourceUrl, metadata)

    override suspend fun overwriteMetadata(resourceUrl: String, metadata: LibraryMetadata): Result<Unit> =
        saveMetadata(resourceUrl, metadata, overwriteExternal = true)

    internal suspend fun saveMetadata(
        resourceUrl: String,
        metadata: LibraryMetadata,
        overwriteExternal: Boolean = false,
    ): Result<Unit> {
        val resource = resolveResource(resourceUrl)
            ?: return Result.failure(IllegalArgumentException("Invalid local resource URL"))
        val indexedItem = indexedLibraryItem(resource)
        val itemKey = indexedItem?.itemKey
            ?: LocalLibraryLocator.itemKey(
                resource.root.id,
                resource.relativePath,
            )
        val role = metadataRole(resource)
        if (role == LocalMetadataRole.FOLDER_CONTAINER) {
            return runCatching {
                withIOContext {
                    refreshMutex.withLock {
                        val current = preferences.folderDisplaySettings(itemKey)
                        val editedFields = metadata.editedFields.ifEmpty {
                            buildSet {
                                if (metadata.title != null) add(LibraryMetadataField.TITLE)
                                if (metadata.author != null) add(LibraryMetadataField.AUTHOR)
                                if (metadata.description != null) add(LibraryMetadataField.DESCRIPTION)
                                if (metadata.genres.isNotEmpty()) add(LibraryMetadataField.GENRES)
                            }
                        }
                        val lockedFields = current.effectiveLockedFields() +
                            metadata.lockedFields +
                            editedFields.mapTo(mutableSetOf()) { it.name.lowercase() }
                        preferences.setFolderDisplaySettings(
                            itemKey,
                            current.copy(
                                displayName = if (LibraryMetadataField.TITLE in editedFields) {
                                    metadata.title?.trim()?.takeIf(String::isNotEmpty)
                                } else {
                                    current.displayName
                                },
                                author = if (LibraryMetadataField.AUTHOR in editedFields) {
                                    metadata.author?.trim()?.takeIf(String::isNotEmpty)
                                } else {
                                    current.author
                                },
                                description = if (LibraryMetadataField.DESCRIPTION in editedFields) {
                                    metadata.description?.trim()?.takeIf(String::isNotEmpty)
                                } else {
                                    current.description
                                },
                                tags = if (LibraryMetadataField.GENRES in editedFields) {
                                    metadata.genres.map(String::trim).filter(String::isNotEmpty).distinct()
                                } else {
                                    current.tags
                                },
                                lockedFields = lockedFields,
                            ),
                        )
                        val item = checkNotNull(indexedItem) { "Local folder is no longer indexed" }
                        val mangaUrl = entryUrl(item)
                        mangaRepository.getMangaByUrlAndSourceId(mangaUrl, id)?.let { manga ->
                            val updated = manga.toSManga().also { applyFolderDisplay(it, item) }
                            check(
                                mangaRepository.update(
                                    MangaUpdate(
                                        id = manga.id,
                                        title = updated.title,
                                        author = updated.author.orEmpty(),
                                        artist = updated.artist.orEmpty(),
                                        description = updated.description.orEmpty(),
                                        genre = updated.genre.orEmpty()
                                            .split(',')
                                            .map(String::trim)
                                            .filter(String::isNotEmpty),
                                        status = updated.status.toLong(),
                                        initialized = true,
                                    ),
                                ),
                            ) { "Unable to refresh local folder metadata" }
                        }
                        emitLibraryRefresh()
                    }
                }
            }
        }
        if (!role.isMetadataEditable()) {
            return Result.failure(IllegalArgumentException("This local entry does not support metadata editing"))
        }
        val editedFields = metadata.editedFields.ifEmpty {
            buildSet {
                if (metadata.title != null) add(LibraryMetadataField.TITLE)
                if (metadata.author != null) add(LibraryMetadataField.AUTHOR)
                if (metadata.artist != null) add(LibraryMetadataField.ARTIST)
                if (metadata.description != null) add(LibraryMetadataField.DESCRIPTION)
                if (metadata.genres.isNotEmpty()) add(LibraryMetadataField.GENRES)
                if (metadata.status != null) add(LibraryMetadataField.STATUS)
            }
        }
        val current = readMetadata(resourceUrl)
        val merged = metadata.copy(
            title = if (LibraryMetadataField.TITLE in editedFields) metadata.title else current?.title,
            author = if (LibraryMetadataField.AUTHOR in editedFields) metadata.author else current?.author,
            artist = if (LibraryMetadataField.ARTIST in editedFields) metadata.artist else current?.artist,
            description = if (LibraryMetadataField.DESCRIPTION in editedFields) {
                metadata.description
            } else {
                current?.description
            },
            genres = if (LibraryMetadataField.GENRES in editedFields) metadata.genres else current?.genres.orEmpty(),
            status = if (LibraryMetadataField.STATUS in editedFields) metadata.status else current?.status,
            editedFields = editedFields,
            lockedFields = current?.lockedFields.orEmpty() +
                metadata.lockedFields +
                editedFields.mapTo(mutableSetOf()) { it.name.lowercase() },
        )
        val value = merged.toLocalMetadataOverride()
        return runCatching {
            val metadataDirectory = metadataDirectory(resource)
            val adjacentFileStem = resource.file.takeUnless(UniFile::isDirectory)?.nameWithoutExtension
            check(
                metadataStore.save(
                    itemKey = itemKey,
                    metadata = value,
                    itemDirectory = metadataDirectory,
                    contentType = indexedLibraryItem(resource)?.contentType ?: resource.root.contentType,
                    adjacentFileStem = adjacentFileStem,
                    entryName = resource.file.takeUnless(UniFile::isDirectory)?.name,
                    role = role,
                    overwriteExternal = overwriteExternal,
                ),
            ) {
                "Unable to save local metadata"
            }
        }
    }

    override suspend fun useExternalMetadata(resourceUrl: String): Result<Unit> = runCatching {
        withIOContext {
            refreshMutex.withLock {
                val resource = checkNotNull(resolveResource(resourceUrl))
                val entry = checkNotNull(indexedLibraryItem(resource))
                val role = metadataRole(resource)
                check(role.isMetadataReadable()) { "This local entry has no external metadata" }
                val external = checkNotNull(
                    metadataStore.externalMetadata(
                        entry.itemKey,
                        metadataDirectory(resource),
                        entry.contentType,
                        resource.file.takeUnless(UniFile::isDirectory)?.name,
                        role = role,
                        acceptExternalChanges = true,
                    ),
                )
                preferences.setMetadataOverride(entry.itemKey, external)
                scanLibrary().also { mutableLibraryRefreshes.emit(it) }
            }
        }
    }

    override suspend fun generateMetadataSuggestion(
        resourceUrl: String,
        filenameTemplate: MetadataFilenameTemplate,
    ): Result<LibraryMetadataSuggestion> = runCatching {
        withIOContext {
            val resource = resolveResource(resourceUrl)
                ?: error("Invalid local resource URL")
            val role = metadataRole(resource)
            if (!role.isMetadataSuggestionSupported()) {
                error("Metadata suggestions are not supported for this local entry")
            }
            val items = when (role) {
                LocalMetadataRole.INDIVIDUAL_FILE -> listOf(resource.file)
                LocalMetadataRole.FOLDER_IMAGE_SERIES -> resource.file.listFiles().orEmpty()
                    .filter {
                        !isLocalAuxiliaryFile(it.name.orEmpty()) && !it.isDirectory &&
                            ImageUtil.isImage(it.name) { it.openInputStream() }
                    }
                else -> resource.file.listFiles().orEmpty()
                    .filterNot { it.name.orEmpty().startsWith('.') }.filter(::isSupportedChapter)
            }
            val item = checkNotNull(indexedLibraryItem(resource))
            val override = preferences.getMetadataOverrides()[item.itemKey]
            val localOverride = override.takeUnless { role == LocalMetadataRole.FOLDER_CONTAINER }
            val candidates = buildList {
                addAll(objectMetadataCandidates(resource, role))
                metadataStore.externalMetadata(
                    item.itemKey,
                    metadataDirectory(resource),
                    item.contentType,
                    resource.file.takeUnless(UniFile::isDirectory)?.name,
                    role,
                )?.let { add(LocalMetadataCandidate(it.toLibraryMetadata(), MetadataSuggestionSource.SIDECAR)) }
                localOverride?.let {
                    add(LocalMetadataCandidate(it.toLibraryMetadata(), MetadataSuggestionSource.LOCAL_OVERRIDE))
                }
            }
            val displayName = if (resource.file.isDirectory) {
                resource.file.name.orEmpty()
            } else {
                resource.file.nameWithoutExtension.orEmpty()
            }
            generateLocalMetadataSuggestion(
                folderName = displayName,
                itemNames = items.map { file -> file.name.orEmpty() },
                embeddedMetadata = if (role == LocalMetadataRole.SERIES) {
                    items.mapNotNull(::readFileEmbeddedMetadata)
                        .map { it.copy(source = MetadataSuggestionSource.CHAPTER_EMBEDDED) }
                } else {
                    emptyList()
                },
                filenameTemplate = filenameTemplate,
            ).withLocalMetadata(candidates, localOverride?.lockedFields.orEmpty())
        }
    }

    override suspend fun legacyMetadataSuggestion(resourceUrl: String): LibraryMetadataSuggestion? = withIOContext {
        val resource = resolveResource(resourceUrl) ?: return@withIOContext null
        if (metadataRole(resource) != LocalMetadataRole.FOLDER_IMAGE_SERIES) return@withIOContext null
        val item = indexedLibraryItem(resource) ?: return@withIOContext null
        if (preferences.getMetadataOverrides()[item.itemKey] != null || metadataStore.externalMetadata(
                item.itemKey,
                resource.file,
                item.contentType,
                null,
                LocalMetadataRole.FOLDER_IMAGE_SERIES,
            ) != null
        ) {
            return@withIOContext null
        }
        val metadata = metadataStore.legacyImageMetadata(resource.file) ?: return@withIOContext null
        LibraryMetadataSuggestion(LibraryMetadata(), emptyMap(), 0, 0).withLocalMetadata(
            listOf(LocalMetadataCandidate(metadata.toLibraryMetadata(), MetadataSuggestionSource.LEGACY_SIDECAR)),
            preferences.getMetadataOverrides()[indexedLibraryItem(resource)?.itemKey]?.lockedFields.orEmpty(),
        )
    }

    override fun localChapterFile(chapterUrl: String): UniFile? {
        val incoming = IncomingMediaSessionLocator.chapterFile(context, chapterUrl, id)
            ?.let { file -> UniFile.fromUri(context, Uri.fromFile(file)) }
        return incoming ?: resolveResource(chapterUrl)?.file
    }

    override suspend fun prepareChapterFile(chapterUrl: String): UniFile? = withIOContext {
        if (!supportsFileTransfers) return@withIOContext localChapterFile(chapterUrl)
        val location = LocalLibraryLocator.location(chapterUrl, id) ?: return@withIOContext null
        val root = preferences.getConfig().roots.firstOrNull { it.id == location.rootId } ?: return@withIOContext null
        val remote =
            koharia.storage.NetworkStorageRuntime.fromUri(context, Uri.parse(root.treeUri)) ?: return@withIOContext null
        val index = preferences.getIndex()
        val indexed = index.itemsByLocator[root.id to location.relativePath]
            ?: index.itemsByKey[LocalLibraryLocator.itemKey(root.id, location.relativePath)]
        if (indexed?.missing == true ||
            (location.relativePath.startsWith(".koharia/nodes/") && indexed == null)
        ) {
            return@withIOContext null
        }
        val relative = (indexed?.physicalPath() ?: location.relativePath)
            .takeUnless { it == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY }.orEmpty()
        val path = koharia.storage.StoragePath.normalize(
            listOf(remote.storagePath, root.relativePath, relative)
                .filter(String::isNotEmpty).joinToString("/"),
        )
        remote.runtime.snapshot.directory("")
        var parent = ""
        for (segment in koharia.storage.StoragePath.parent(path).split('/').filter(String::isNotEmpty)) {
            parent = koharia.storage.StoragePath.child(parent, segment)
            remote.runtime.snapshot.directory(parent)
        }
        val file = localChapterFile(chapterUrl)
        if (file is com.hippo.unifile.RemoteStorageFile &&
            file.isDirectory
        ) {
            file.runtime.snapshot.directory(file.storagePath)
        }
        file
    }

    private suspend fun hydrateNetworkDirectory(url: String) {
        if (!supportsFileTransfers) return
        val file = prepareChapterFile(url) as? com.hippo.unifile.RemoteStorageFile ?: return
        if (file.isDirectory) file.runtime.snapshot.scan(file.storagePath)
    }

    override suspend fun loadSuggestedSeriesCover(mangaUrl: String): ByteArray? = withIOContext {
        prepareChapterFile(mangaUrl)
        indexedEntry(mangaUrl)?.takeIf { it.kind == LocalLibraryItem.Kind.FOLDER }?.let {
            return@withIOContext folderCover(it, mutableSetOf())
        }
        val incomingDirectory = IncomingMediaSessionLocator.location(mangaUrl, id)
            ?.takeIf { it.fileName == null }
            ?.let { IncomingMediaSessionLocator.sessionDirectory(context, it.sessionId) }
            ?.let { UniFile.fromUri(context, Uri.fromFile(it)) }
        resolveResource(mangaUrl)?.let { resource ->
            if (indexedLibraryItem(resource)?.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                return@withIOContext runCatching { firstImageBytes(resource.file) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        logcat(LogPriority.WARN, error) { "Unable to read suggested local item cover" }
                    }
                    .getOrNull()
            }
        }
        val directory = incomingDirectory ?: resolveResource(mangaUrl)?.file?.takeIf(UniFile::isDirectory)
            ?: return@withIOContext null
        try {
            firstImageBytes(directory)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Unable to read suggested local series cover" }
            null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    internal fun isIndividualFileEntry(mangaUrl: String): Boolean {
        val item = indexedEntry(mangaUrl) ?: return false
        return item.kind == LocalLibraryItem.Kind.FILE_ENTRY ||
            (item.kind == LocalLibraryItem.Kind.FOLDER && item.imageComic)
    }

    internal fun isFolderContainer(mangaUrl: String): Boolean {
        return indexedEntry(mangaUrl)?.let { it.kind == LocalLibraryItem.Kind.FOLDER && !it.imageComic } == true
    }

    internal suspend fun readableDescendantMangas(mangaUrl: String): List<Manga> = withIOContext {
        val folder = indexedEntry(mangaUrl)?.takeIf {
            it.kind == LocalLibraryItem.Kind.FOLDER && !it.imageComic
        } ?: return@withIOContext emptyList()
        val descendantKeys = preferences.getIndex().folderReadProgressDescendants()[folder.itemKey].orEmpty()
        if (descendantKeys.isEmpty()) return@withIOContext emptyList()
        val mangas = mangaRepository.getMangaBySourceId(id)
        val progressIndexes = readProgressIndexes(mangas.map(Manga::url))
        mangas.filter { manga ->
            progressIndexes[manga.url.trimEnd('/')]
                ?.itemKey
                ?.let(descendantKeys::contains) == true
        }
    }

    internal fun indexedEntry(url: String): LocalLibraryItem? {
        val location = LocalLibraryLocator.location(url, id) ?: return null
        return location.rootId?.let { rootId ->
            val index = preferences.getIndex()
            index.itemsByLocator[rootId to location.relativePath]
                ?: index.libraryItemsByKey[LocalLibraryLocator.itemKey(rootId, location.relativePath)]
        }
    }

    internal fun folderRoots(): List<LocalLibraryRootConfig> = preferences.getConfig().let { config ->
        config.roots.filter { config.organizationMode(it) == LocalLibraryOrganizationMode.FOLDER }
    }

    internal fun hasPendingFolderOperation(): Boolean = preferences.pendingFolderOperation() != null

    internal suspend fun canReadAsImageComic(url: String): Boolean = withIOContext {
        runCatching {
            val item = indexedEntry(url) ?: return@runCatching false
            if (item.kind == LocalLibraryItem.Kind.FILE_ENTRY) return@runCatching item.imageComic
            val resource = resolveResource(url) ?: return@runCatching false
            val scan = scanFolderImages(resource.file, LocalScanDirectoryReader(context))
            item.imageComic || (scan.pureImages && scan.entries.none(LocalScanFile::directory))
        }.getOrDefault(false)
    }

    internal suspend fun canMergeImageSeries(url: String): Boolean = withIOContext {
        runCatching {
            val item = indexedEntry(url) ?: return@runCatching false
            if (item.kind != LocalLibraryItem.Kind.FOLDER) return@runCatching false
            resolveResource(url)?.let {
                scanFolderImages(it.file, LocalScanDirectoryReader(context)).pureImages
            } == true
        }.getOrDefault(false)
    }

    internal suspend fun canMoveEntry(url: String): Boolean = withIOContext {
        runCatching {
            val item = indexedEntry(url)
            item?.imageComic != true && resolveResource(url)?.file?.let { canMoveLocalFile(context, it) } == true
        }.getOrDefault(false)
    }

    internal suspend fun invalidateFolderAncestorCovers(url: String) {
        val item = indexedEntry(url) ?: return
        val ancestors = preferences.getIndex().libraryItemsByKey.values.filter {
            it.rootId == item.rootId && it.kind == LocalLibraryItem.Kind.FOLDER &&
                item.relativePath.startsWith("${it.relativePath}/")
        }.mapTo(mutableSetOf(), ::entryUrl)
        val mangas = mangaRepository.getMangaBySourceId(id).filter { it.url in ancestors }
        mangas.forEach(coverCache::deleteFromCache)
        mangaRepository.updateAll(
            mangas.map {
                MangaUpdate(id = it.id, coverLastModified = System.currentTimeMillis())
            },
        )
    }

    internal fun folderEntries(rootId: String): List<LocalLibraryItem> = preferences.getIndex().libraryItemsByKey.values
        .filter { it.rootId == rootId && it.kind == LocalLibraryItem.Kind.FOLDER }

    internal fun entryUrl(item: LocalLibraryItem): String = LocalLibraryLocator.entryUrl(
        id,
        item.rootId,
        item.locatorPath,
    )

    internal suspend fun createFolder(rootId: String, parentPath: String, name: String): Result<Unit> = runCatching {
        withIOContext {
            refreshMutex.withLock {
                validateLocalName(name)
                val root = folderRoots().first { it.id == rootId }
                val directory = resolveLocalChild(checkNotNull(preferences.resolveRoot(context, root)), parentPath)
                check(directory.findFile(name) == null)
                checkNotNull(directory.createDirectory(name))
                scanLibrary().also { mutableLibraryRefreshes.emit(it) }
            }
        }
    }

    internal suspend fun setImageComic(url: String, enabled: Boolean): Result<Unit> = runCatching {
        withIOContext {
            refreshMutex.withLock {
                val entry = checkNotNull(indexedEntry(url))
                check(
                    entry.kind == LocalLibraryItem.Kind.FOLDER &&
                        entry.format == "directory" &&
                        entry.rootId in folderRoots().map { it.id },
                )
                val resource = checkNotNull(resolveResource(url))
                if (enabled) {
                    val scan = scanFolderImages(resource.file, LocalScanDirectoryReader(context))
                    check(scan.pureImages)
                }
                if (preferences.getConfig().metadataStorage == LocalMetadataStorage.FOLDER_DIRECTORY) {
                    val marker = metadataStore.folderMarker(
                        resource.file,
                        true,
                    )
                        ?: error("Unable to save directory mode")
                    check(
                        metadataStore.saveFolderMarker(
                            resource.file,
                            marker.copy(imageComic = enabled, imageComicOverride = enabled),
                        ),
                    )
                }
                preferences.setIndex(
                    preferences.getIndex().let { index ->
                        index.copy(
                            items = index.items.map {
                                if (it.itemKey == entry.itemKey) {
                                    it.copy(imageComic = enabled, imageComicOverride = enabled)
                                } else {
                                    it
                                }
                            },
                        )
                    },
                )
                scanLibrary().also { mutableLibraryRefreshes.emit(it) }
            }
        }
    }

    internal suspend fun relocateEntry(
        url: String,
        newName: String? = null,
        destination: String? = null,
    ): Result<Unit> = runCatching {
        withIOContext {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                refreshMutex.withLock {
                    check(preferences.pendingFolderOperation() == null) { "Complete the pending operation first" }
                    val item = checkNotNull(indexedEntry(url))
                    check(!item.imageComic)
                    val root = folderRoots().first { it.id == item.rootId }
                    val base = checkNotNull(preferences.resolveRoot(context, root))
                    val file = resolveLocalChild(base, item.relativePath)
                    val parentPath = item.relativePath.substringBeforeLast('/', "")
                    val from = resolveLocalChild(base, parentPath)
                    val targetPath = destination ?: parentPath
                    check(targetPath != item.relativePath && !targetPath.startsWith("${item.relativePath}/"))
                    val to = resolveLocalChild(base, targetPath)
                    val name = newName ?: checkNotNull(file.name)
                    validateLocalName(name)
                    if (!file.isDirectory) {
                        val targetStem = name.substringBeforeLast('.')
                        val targetMetadata = to.findFile(".koharia")?.findFile("metadata")
                        check(
                            listOf("metadata.opf", "ComicInfo.xml").none { suffix ->
                                targetMetadata?.findFile("$name.$suffix") != null
                            },
                        )
                        check(
                            listOf("metadata.opf", "ComicInfo.xml").none { suffix ->
                                to.findFile("$targetStem.$suffix") != null
                            },
                        )
                    }
                    fun joined(
                        parent: String,
                        child: String,
                    ) = listOf(
                        parent,
                        child,
                    ).filter(String::isNotBlank).joinToString("/")
                    val newPath = joined(targetPath, name)
                    val steps = mutableListOf(localMutationStep(base, item.relativePath, newPath))
                    if (!file.isDirectory) {
                        val oldName = item.relativePath.substringAfterLast('/')
                        val oldStem = oldName.substringBeforeLast('.')
                        val newStem = name.substringBeforeLast('.')
                        val unambiguous = from.listFiles().orEmpty().none {
                            it.name != oldName && it.name.orEmpty().substringBeforeLast('.') == oldStem &&
                                it.extension.orEmpty().lowercase() in SUPPORTED_FILE_EXTENSIONS
                        }
                        if (unambiguous) {
                            for (suffix in listOf("metadata.opf", "ComicInfo.xml")) {
                                if (from.findFile("$oldStem.$suffix") != null) {
                                    steps += localMutationStep(
                                        base,
                                        joined(
                                            parentPath,
                                            "$oldStem.$suffix",
                                        ),
                                        joined(
                                            targetPath,
                                            "$newStem.$suffix",
                                        ),
                                    )
                                }
                            }
                        }
                        val oldMetadata = from.findFile(".koharia")?.findFile("metadata")
                        for (suffix in listOf("metadata.opf", "ComicInfo.xml")) {
                            if (oldMetadata?.findFile("$oldName.$suffix") != null) {
                                val hidden = to.findFile(".koharia") ?: checkNotNull(to.createDirectory(".koharia"))
                                checkNotNull(hidden.findFile("metadata") ?: hidden.createDirectory("metadata"))
                                steps += localMutationStep(
                                    base,
                                    joined(
                                        parentPath,
                                        ".koharia/metadata/$oldName.$suffix",
                                    ),
                                    joined(
                                        targetPath,
                                        ".koharia/metadata/$name.$suffix",
                                    ),
                                )
                            }
                        }
                    }
                    if (destination != null) {
                        steps.forEach {
                            check(
                                canMoveLocalFile(
                                    context,
                                    resolveLocalChild(
                                        base,
                                        it.from,
                                    ),
                                ),
                            )
                        }
                    }
                    val operation = LocalFolderMutation(
                        root.id,
                        root.directoryKey(),
                        item.itemKey,
                        file.isDirectory,
                        steps,
                    )
                    preferences.recordFolderOperation(json.encodeToString(operation))
                    completeFolderMutation(operation)
                }
            }
        }
    }

    internal suspend fun resumeFolderOperation(): Result<Unit> = runCatching {
        withIOContext {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                refreshMutex.withLock {
                    val pending = checkNotNull(preferences.pendingFolderOperation())
                    completeFolderMutation(json.decodeFromString<LocalFolderMutation>(pending))
                }
            }
        }
    }

    internal suspend fun acceptCurrentFolderState(): Result<Unit> = runCatching {
        withIOContext {
            refreshMutex.withLock {
                scanLibrary().also { mutableLibraryRefreshes.emit(it) }
                preferences.recordFolderOperation(null)
            }
        }
    }

    private suspend fun completeFolderMutation(initial: LocalFolderMutation) {
        val root = folderRoots().first { it.id == initial.rootId && it.directoryKey() == initial.rootKey }
        val base = checkNotNull(preferences.resolveRoot(context, root))
        var operation = initial
        operation.steps.indices.forEach { position ->
            val step = operation.steps[position]
            if (step.completed) return@forEach
            val changed = executeLocalMutationStep(context, base, step)
            val index = preferences.getIndex()
            val updatedIndex = if (position == 0) {
                index.copy(
                    items = index.items.map { entry ->
                        if (entry.rootId == root.id && (
                                entry.itemKey == operation.itemKey ||
                                    (operation.directory && entry.relativePath.startsWith("${step.from}/"))
                                )
                        ) {
                            entry.copy(
                                relativePath = step.to + entry.relativePath.removePrefix(step.from),
                                documentIdentity = if (entry.itemKey == operation.itemKey &&
                                    changed.uri.scheme == "content"
                                ) {
                                    "${changed.uri.authority}:${DocumentsContract.getDocumentId(changed.uri)}"
                                } else {
                                    null
                                },
                            )
                        } else {
                            entry
                        }
                    },
                )
            } else {
                index
            }
            operation = operation.copy(
                steps = operation.steps.mapIndexed {
                        i,
                        value,
                    ->
                    if (i == position) value.copy(completed = true) else value
                },
            )
            preferences.commitFolderOperation(updatedIndex, json.encodeToString(operation))
        }
        scanLibrary().also { mutableLibraryRefreshes.emit(it) }
        preferences.recordFolderOperation(null)
    }

    internal fun containingFolder(url: String): LocalLibraryItem? {
        val entry = indexedEntry(url) ?: return null
        val parentPath = entry.relativePath.substringBeforeLast('/', "")
        return preferences.getIndex().libraryItemsByKey.values.firstOrNull {
            it.rootId == entry.rootId &&
                it.relativePath == parentPath
        }
    }

    private suspend fun folderCover(item: LocalLibraryItem, visited: MutableSet<String>): ByteArray? {
        currentCoroutineContext().ensureActive()
        if (!visited.add(item.itemKey)) return null
        val customCovers = Injekt.get<koharia.cover.CustomCoverStore>()
        mangaRepository.getMangaByUrlAndSourceId(entryUrl(item), id)?.let { manga ->
            customCovers.open(manga)?.use { return it.readBytes() }
        }
        prepareChapterFile(entryUrl(item))
        val resource = resolveResource(entryUrl(item)) ?: return null
        val custom = resource.file.listFiles().orEmpty().firstOrNull {
            !it.isDirectory &&
                it.name.orEmpty().substringBeforeLast('.').lowercase() in setOf(
                    "cover",
                    "folder",
                    "poster",
                    "!cover",
                ) &&
                LocalMediaFormats.isImage(it.extension.orEmpty().lowercase())
        }
        if (custom != null) return custom.openInputStream().use { it.readBytes() }
        val children =
            preferences.getIndex().childrenByLocation[item.rootId to item.relativePath].orEmpty().sortedWith {
                    a,
                    b,
                ->
                a.relativePath.substringAfterLast(
                    '/',
                ).compareToCaseInsensitiveNaturalOrder(b.relativePath.substringAfterLast('/'))
            }
        for (child in children) {
            currentCoroutineContext().ensureActive()
            val bytes = try {
                val manga = mangaRepository.getMangaByUrlAndSourceId(entryUrl(child), id)
                val customBytes = manga?.let { customCovers.open(it)?.use { input -> input.readBytes() } }
                if (customBytes != null) return customBytes
                if (child.kind == LocalLibraryItem.Kind.FOLDER) {
                    folderCover(
                        child,
                        visited,
                    )
                } else {
                    prepareChapterFile(entryUrl(child))?.let {
                        firstImageBytes(it)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            if (bytes != null) return bytes
        }
        return resource.file.listFiles().orEmpty().filter {
            !it.isDirectory &&
                !isLocalAuxiliaryFile(it.name.orEmpty()) &&
                LocalMediaFormats.isImage(it.extension.orEmpty().lowercase())
        }
            .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
            .firstOrNull()?.openInputStream()?.use { it.readBytes() }
    }

    internal fun isIndividualBookEntry(mangaUrl: String): Boolean {
        val item = individualFileEntry(mangaUrl) ?: return false
        return item.contentType == LocalLibraryContentType.BOOKS ||
            (item.contentType == LocalLibraryContentType.MIXED && item.format in BOOK_FILE_EXTENSIONS)
    }

    private fun individualFileEntry(mangaUrl: String): LocalLibraryItem? {
        val location = LocalLibraryLocator.location(mangaUrl, id) ?: return null
        val index = preferences.getIndex()
        val item = if (location.rootId != null) {
            index.libraryItemsByKey[LocalLibraryLocator.itemKey(location.rootId, location.relativePath)]
        } else {
            val roots = preferences.getConfig().roots.associateBy { it.id }
            index.libraryItemsByKey.values.singleOrNull { item ->
                val root = roots[item.rootId] ?: return@singleOrNull false
                location.relativePath == listOf(root.relativePath, item.relativePath)
                    .filter(String::isNotBlank).joinToString("/")
            }
        }
        return item?.takeIf { it.kind == LocalLibraryItem.Kind.FILE_ENTRY }
    }

    internal suspend fun documentPageCount(chapterUrl: String): Int? = withIOContext {
        val file = localChapterFile(chapterUrl) ?: return@withIOContext null
        runCatching {
            when {
                DocumentEngines.forExtension(file.extension) != null -> {
                    DocumentEngines.open(context, file, documentLayoutPreferences.toDocumentRenderSettings())
                        .use { session -> session.pageCount }
                }
                LocalMediaFormats.isImage(file.extension) -> 1
                LocalMediaFormats.isArchive(file.extension) -> {
                    val coroutineContext = currentCoroutineContext()
                    file.archiveReader(context) { coroutineContext.ensureActive() }.use { reader ->
                        reader.imageEntries { coroutineContext.ensureActive() }.size
                    }
                }
                file.extension.equals("pdf", ignoreCase = true) -> {
                    context.contentResolver.openFileDescriptor(file.uri, "r")?.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
                    } ?: 0
                }
                else -> 0
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()?.takeIf { it > 0 }
    }

    private fun documentPageMemo(
        file: UniFile,
        modifiedAt: Long,
        existingChapter: Chapter?,
        rootId: String,
        relativePath: String,
    ): JsonObject = localChapterMemo(
        existing = existingChapter,
        modifiedAt = modifiedAt,
        sizeBytes = if (file.isDirectory) 0L else file.length(),
        fingerprint = preferences.getIndex().itemsByLocation[rootId to relativePath]?.fingerprint,
    )

    private suspend fun refreshAfterImport(destinationRootId: String) {
        val error = runCatching {
            refreshMutex.withLock { scanLibrary().also { mutableLibraryRefreshes.emit(it) } }
        }.exceptionOrNull() ?: return
        if (error is LocalLibraryPartialScanException && destinationRootId !in error.failedRootIds) return
        throw error
    }

    internal fun readProgressIndex(mangaUrl: String): LocalReadProgressIndex? {
        return readProgressIndexes(listOf(mangaUrl))[mangaUrl.trimEnd('/')]
    }

    internal fun readProgressIndexes(mangaUrls: Collection<String>): Map<String, LocalReadProgressIndex> {
        if (mangaUrls.isEmpty()) return emptyMap()

        val index = preferences.getIndex()
        val rootsById = preferences.getConfig().roots.associateBy { it.id }
        val indexedItems = index.libraryItemsByKey
        val chapterCounts = index.chaptersBySeriesKey
        val folderDescendants = index.folderReadProgressDescendants()

        return mangaUrls.mapNotNull { mangaUrl ->
            val location = LocalLibraryLocator.location(mangaUrl, id) ?: return@mapNotNull null
            val itemKey = location.rootId?.let { rootId ->
                LocalLibraryLocator.itemKey(rootId, location.relativePath)
            } ?: indexedItems.values.singleOrNull { item ->
                val root = rootsById[item.rootId] ?: return@singleOrNull false
                location.relativePath == listOf(root.relativePath, item.relativePath)
                    .filter(String::isNotBlank).joinToString("/")
            }?.itemKey ?: return@mapNotNull null
            val item = indexedItems[itemKey] ?: return@mapNotNull null
            val progress = if (item.kind == LocalLibraryItem.Kind.FILE_ENTRY ||
                (item.kind == LocalLibraryItem.Kind.FOLDER && item.imageComic)
            ) {
                LocalReadProgressIndex(
                    indexedChapterCount = 1,
                    isIndividualFile = true,
                    itemKey = item.itemKey,
                )
            } else if (item.kind == LocalLibraryItem.Kind.FOLDER) {
                val descendantItemKeys = folderDescendants[item.itemKey].orEmpty()
                LocalReadProgressIndex(
                    indexedChapterCount = descendantItemKeys.size,
                    isIndividualFile = false,
                    itemKey = item.itemKey,
                    isFolderContainer = true,
                    descendantItemKeys = descendantItemKeys,
                )
            } else {
                LocalReadProgressIndex(
                    indexedChapterCount = chapterCounts[itemKey]?.size ?: 0,
                    isIndividualFile = false,
                    itemKey = item.itemKey,
                )
            }
            mangaUrl.trimEnd('/') to progress
        }.toMap()
    }

    private fun LocalLibraryItem.isReadProgressLeaf(): Boolean {
        return kind == LocalLibraryItem.Kind.FILE_ENTRY ||
            (kind == LocalLibraryItem.Kind.FOLDER && imageComic)
    }

    private fun LocalLibraryIndex.folderReadProgressDescendants(): Map<String, Set<String>> {
        val cache = mutableMapOf<String, Set<String>>()
        fun descendants(folder: LocalLibraryItem): Set<String> {
            return cache.getOrPut(folder.itemKey) {
                childrenByLocation[folder.rootId to folder.relativePath]
                    .orEmpty()
                    .flatMapTo(mutableSetOf()) { child ->
                        when {
                            child.isReadProgressLeaf() -> setOf(child.itemKey)
                            child.kind == LocalLibraryItem.Kind.FOLDER -> descendants(child)
                            else -> emptySet()
                        }
                    }
            }
        }
        return libraryItemsByKey.values
            .asSequence()
            .filter { it.kind == LocalLibraryItem.Kind.FOLDER && !it.imageComic }
            .associate { it.itemKey to descendants(it) }
    }

    private fun indexedLibraryItem(resource: ResolvedLocalResource): LocalLibraryItem? {
        return preferences.getIndex().itemsByLocation[resource.root.id to resource.indexedPath]?.takeIf {
            it.kind in setOf(
                LocalLibraryItem.Kind.SERIES,
                LocalLibraryItem.Kind.FILE_ENTRY,
                LocalLibraryItem.Kind.FOLDER,
            )
        }
    }

    private fun LocalLibraryItem.organizationMode(): LocalLibraryOrganizationMode {
        val config = preferences.getConfig()
        return config.roots.firstOrNull { it.id == rootId }?.let(config::organizationMode)
            ?: LocalLibraryOrganizationMode.SERIES
    }

    private fun metadataRole(resource: ResolvedLocalResource): LocalMetadataRole {
        val item = indexedLibraryItem(resource) ?: return LocalMetadataRole.INDIVIDUAL_FILE
        // A physical file entry is always an individual file. In folder mode the
        // virtual image-series entry also uses FILE_ENTRY, but resolves to its
        // backing directory; use that physical distinction when resolving URLs.
        if (item.kind == LocalLibraryItem.Kind.FILE_ENTRY && !resource.file.isDirectory) {
            return LocalMetadataRole.INDIVIDUAL_FILE
        }
        return item.metadataRole(preferences.getConfig().organizationMode(resource.root))
    }

    private fun applyIndividualMetadata(
        manga: SManga,
        resource: ResolvedLocalResource,
        metadataOverrides: Map<String, LocalMetadataOverride>,
        scannedFiles: List<UniFile>? = null,
        role: LocalMetadataRole = LocalMetadataRole.INDIVIDUAL_FILE,
    ) {
        val file = resource.file
        clearComicMetadata(manga)
        manga.title = if (file.isDirectory) file.name.orEmpty() else file.nameWithoutExtension.orEmpty()
        manga.thumbnail_url = if (file.isDirectory) {
            findCover(scannedFiles ?: file.listFiles().orEmpty().toList())?.uri?.toString()
                ?: LocalLibraryLocator.chapterUrl(
                    id,
                    resource.root.id,
                    indexedLibraryItem(resource)?.locatorPath
                        ?: resource.relativePath,
                )
        } else {
            LocalLibraryLocator.chapterUrl(
                id,
                resource.root.id,
                indexedLibraryItem(resource)?.locatorPath
                    ?: resource.relativePath,
            )
        }
        objectMetadataCandidates(resource, role, scannedFiles).forEach {
            it.metadata.toLocalMetadataOverride().applyTo(manga)
        }

        val legacyRelativePath = listOf(resource.root.relativePath, resource.relativePath)
            .filter(String::isNotBlank)
            .joinToString("/")
        val override = metadataOverrides[
            indexedLibraryItem(resource)?.itemKey
                ?: LocalLibraryLocator.itemKey(
                    resource.root.id,
                    resource.relativePath,
                ),
        ]
            ?: metadataOverrides[LocalLibraryLocator.legacyItemKey(legacyRelativePath)]
        override?.let {
            it.title?.let { value -> manga.title = value }
            it.author?.let { value -> manga.author = value }
            it.artist?.let { value -> manga.artist = value }
            it.description?.let { value -> manga.description = value }
            it.genres.takeIf { genres -> genres.isNotEmpty() || "genres" in it.lockedFields }
                ?.let { genres -> manga.genre = genres.joinToString(", ") }
            it.status?.let { value -> manga.status = value }
        }
    }

    private fun applyComicInfoMetadata(manga: SManga, file: UniFile) {
        if (file.extension.orEmpty().lowercase() !in COMIC_FILE_EXTENSIONS) return
        runCatching {
            file.archiveReader(context).use { archive ->
                val comicInfoEntry = archive.useEntries { entries ->
                    entries.firstOrNull {
                        it.isFile && it.name.substringAfterLast('/').equals(COMIC_INFO_FILE, ignoreCase = true)
                    }
                } ?: return@use
                archive.getInputStream(comicInfoEntry.name)?.use { input ->
                    AndroidXmlReader(input, StandardCharsets.UTF_8.name()).use { reader ->
                        manga.copyFromComicInfo(xml.decodeFromReader<ComicInfo>(reader))
                    }
                }
            }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Unable to read embedded ComicInfo.xml" }
        }
    }

    private fun applySeriesMetadata(
        manga: SManga,
        root: LocalLibraryRootConfig,
        directory: UniFile,
        relativePath: String,
        files: List<UniFile>,
        metadataOverrides: Map<String, LocalMetadataOverride>,
        itemKey: String? = null,
    ) {
        clearComicMetadata(manga)
        manga.title = directory.name.orEmpty()
        manga.thumbnail_url = findCover(files)?.uri?.toString()
            ?: findFirstChapter(files)?.let { chapter ->
                val chapterPath = listOf(relativePath, chapter.name.orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString("/")
                LocalLibraryLocator.chapterUrl(
                    id,
                    root.id,
                    preferences.getIndex().itemsByLocation[root.id to chapterPath]?.locatorPath ?: chapterPath,
                )
            }
        objectMetadataCandidates(ResolvedLocalResource(root, directory, relativePath), LocalMetadataRole.SERIES, files)
            .forEach { it.metadata.toLocalMetadataOverride().applyTo(manga) }

        val legacyRelativePath = listOf(root.relativePath, relativePath)
            .filter(String::isNotBlank)
            .joinToString("/")
        val override = metadataOverrides[itemKey ?: LocalLibraryLocator.itemKey(root.id, relativePath)]
            ?: metadataOverrides[LocalLibraryLocator.legacyItemKey(legacyRelativePath)]
        override?.let {
            override.title?.let { manga.title = it }
            override.author?.let { manga.author = it }
            override.artist?.let { manga.artist = it }
            override.description?.let { manga.description = it }
            override.genres
                .takeIf { it.isNotEmpty() || "genres" in override.lockedFields }
                ?.let { manga.genre = it.joinToString(", ") }
            override.status?.let { manga.status = it }
        }
    }

    private fun readFileEmbeddedMetadata(file: UniFile): LocalEmbeddedMetadata? {
        if (file.isDirectory) return null
        if (file.extension.equals("epub", true)) return readEpubMetadata(file)
        if (isMobiFile(file)) return readMobiMetadata(file)?.copy(source = MetadataSuggestionSource.MOBI_EMBEDDED)
        if (file.extension.orEmpty().lowercase() !in COMIC_FILE_EXTENSIONS) return null
        val manga = SManga.create().apply { title = "" }
        applyComicInfoMetadata(manga, file)
        return LocalEmbeddedMetadata(
            title = manga.title.takeIf(String::isNotBlank),
            authors = listOfNotNull(manga.author),
            contributors = listOfNotNull(manga.artist),
            description = manga.description,
            subjects = manga.genre?.split(',')?.map(String::trim).orEmpty(),
            status = manga.status.takeUnless {
                it == SManga.UNKNOWN
            },
            source = MetadataSuggestionSource.COMICINFO_EMBEDDED,
        )
    }

    private fun objectMetadataCandidates(
        resource: ResolvedLocalResource,
        role: LocalMetadataRole,
        scannedFiles: List<UniFile>? = null,
    ): List<LocalMetadataCandidate> = buildList {
        if (!role.isMetadataReadable()) return@buildList
        if (role == LocalMetadataRole.INDIVIDUAL_FILE) {
            readFileEmbeddedMetadata(resource.file)?.let {
                add(LocalMetadataCandidate(it.toLibraryMetadata(), it.source))
            }
        }
        val directory = if (resource.file.isDirectory) resource.file else metadataDirectory(resource)
        fun sidecar(suffix: String): UniFile? {
            val name = when (role) {
                LocalMetadataRole.SERIES -> suffix
                LocalMetadataRole.FOLDER_IMAGE_SERIES -> ".koharia-image-series.$suffix"
                else -> if (resource.file.isDirectory) return null else "${resource.file.nameWithoutExtension}.$suffix"
            }
            return scannedFiles?.firstOrNull { it.name.equals(name, true) } ?: directory?.findFile(name)
        }
        sidecar(COMIC_INFO_FILE)?.let { metadataStore.readStandard(it, LocalLibraryContentType.COMICS) }
            ?.let { add(LocalMetadataCandidate(it.toLibraryMetadata(), MetadataSuggestionSource.SIDECAR)) }
        sidecar("metadata.opf")?.let(::readOpfFile)?.let {
            val value = if (role == LocalMetadataRole.SERIES) it.forSeriesDisplay(true) else it
            add(LocalMetadataCandidate(value.toLibraryMetadata(), MetadataSuggestionSource.SIDECAR))
        }
    }

    private fun clearComicMetadata(manga: SManga) {
        manga.author = null
        manga.artist = null
        manga.description = null
        manga.genre = null
        manga.status = SManga.UNKNOWN
    }

    private fun applyFolderDisplay(manga: SManga, item: LocalLibraryItem) {
        clearComicMetadata(manga)
        val settings = preferences.folderDisplaySettings(item.itemKey)
        manga.title = settings.displayName?.takeIf(String::isNotBlank)
            ?: item.relativePath.substringAfterLast('/')
        manga.author = settings.author?.takeIf(String::isNotBlank)
        manga.description = settings.description
        manga.genre = settings.tags.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
    }

    private fun readOpfFile(file: UniFile): LocalEmbeddedMetadata? = runCatching {
        file.openInputStream().use { input ->
            parseLocalOpfMetadata(Jsoup.parse(input, StandardCharsets.UTF_8.name(), "", Parser.xmlParser()))
        }
    }.getOrNull()

    private fun readEpubMetadata(file: UniFile): LocalEmbeddedMetadata? = runCatching {
        file.epubReader(context).use { epub ->
            parseLocalOpfMetadata(epub.getPackageDocument(epub.getPackageHref()))
        }
    }.getOrNull()

    private fun readMobiMetadata(file: UniFile): LocalEmbeddedMetadata? = runCatching {
        val metadata = MobiDocumentEngine.readMetadata(file)
        LocalEmbeddedMetadata(
            title = metadata.title,
            authors = listOfNotNull(metadata.author),
        )
    }.getOrNull()

    private fun isMobiFile(file: UniFile): Boolean =
        file.extension.orEmpty().lowercase() in LocalMediaFormats.mobi.extensions

    private fun Manga.matchesIndexedLibrary(
        query: String,
        filters: LocalLibraryFilters,
        folderName: String,
        format: String,
        chapterNames: List<String>,
    ): Boolean {
        val searchableValues = listOfNotNull(
            folderName,
            title,
            author,
            artist,
            genre?.joinToString(", "),
            description,
            format,
        ) + chapterNames

        return (query.isBlank() || searchableValues.any { it.contains(query, ignoreCase = true) }) &&
            (
                filters.series.isBlank() ||
                    title.contains(filters.series, ignoreCase = true) ||
                    folderName.contains(filters.series, ignoreCase = true)
                ) &&
            (filters.chapter.isBlank() || chapterNames.any { it.contains(filters.chapter, ignoreCase = true) }) &&
            (filters.author.isBlank() || author.orEmpty().contains(filters.author, ignoreCase = true)) &&
            (filters.artist.isBlank() || artist.orEmpty().contains(filters.artist, ignoreCase = true)) &&
            (filters.genre.isBlank() || genre.orEmpty().any { it.contains(filters.genre, ignoreCase = true) }) &&
            (filters.format.isBlank() || format.contains(filters.format, ignoreCase = true))
    }

    private fun findCover(files: List<UniFile>): UniFile? {
        return files
            .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            .sortedWith { first, second ->
                val firstCover = first.name.orEmpty().lowercase().startsWith("cover")
                val secondCover = second.name.orEmpty().lowercase().startsWith("cover")
                when {
                    firstCover != secondCover -> if (firstCover) -1 else 1
                    else -> first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
                }
            }
            .firstOrNull()
    }

    private fun findFirstChapter(files: List<UniFile>): UniFile? {
        return files
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter(::isSupportedChapter)
            .sortedWith { first, second ->
                first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
            }
            .firstOrNull()
    }

    private fun isSupportedChapter(file: UniFile): Boolean {
        if (file.isDirectory) {
            return file.listFiles().orEmpty().any {
                !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
            }
        }
        return file.extension.orEmpty().lowercase() in SUPPORTED_FILE_EXTENSIONS
    }

    private suspend fun firstImageBytes(file: UniFile): ByteArray? {
        val coroutineContext = currentCoroutineContext()
        if (file.isDirectory) {
            if (file is com.hippo.unifile.RemoteStorageFile) {
                file.runtime.snapshot.directory(file.storagePath)
            }
            val files = file.listFiles().orEmpty().filterNot { it.name.orEmpty().startsWith('.') }
            findCover(files)?.let { return it.openInputStream().use { input -> input.readBytes() } }
            for (chapter in files.sortedWith { first, second ->
                first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
            }) {
                coroutineContext.ensureActive()
                if (chapter is com.hippo.unifile.RemoteStorageFile && chapter.isDirectory) {
                    chapter.runtime.snapshot.directory(chapter.storagePath)
                }
                if (isSupportedChapter(chapter)) return firstImageBytes(chapter)
            }
            return null
        }

        val extension = file.extension.orEmpty().lowercase()
        return when {
            LocalMediaFormats.isImage(extension) -> file.openInputStream().use { it.readBytes() }
            extension == "epub" -> file.epubReader(context).use { epub ->
                epub.getCoverOrFirstImage()
                    ?.let(epub::getInputStream)
                    ?.use { it.readBytes() }
            }
            extension == "pdf" -> renderFirstPdfPage(file)
            // Ask the engine registry rather than hand-maintaining a parallel extension list
            // (txt/mobi/djvu/md today). Image, epub and pdf are matched above, so the registry
            // answer is equivalent here and stays correct when a new engine is registered.
            DocumentEngines.forExtension(extension) != null -> {
                renderFirstDocumentPage(file)
            }
            else -> file.archiveReader(context) { coroutineContext.ensureActive() }.use { reader ->
                reader.readCoverImage { coroutineContext.ensureActive() }
            }
        }
    }

    private fun renderFirstDocumentPage(file: UniFile): ByteArray? {
        return runCatching {
            DocumentEngines.open(context, file).use { session ->
                val bitmap = session.page(0).render()
                try {
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }.getOrNull()
    }

    private fun renderFirstPdfPage(file: UniFile): ByteArray? {
        val descriptor = context.contentResolver.openFileDescriptor(file.uri, "r") ?: return null
        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(
                        page.width.coerceAtLeast(1),
                        page.height.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            output.toByteArray()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun candidateResources(root: ResolvedLocalLibraryRoot, partial: Boolean = false): List<ScanCandidate> {
        val reader = LocalScanDirectoryReader(context, allowPendingRemote = partial)
        return when (preferences.getConfig().organizationMode(root.config)) {
            LocalLibraryOrganizationMode.FOLDER -> candidateFolderEntries(root, reader)
            LocalLibraryOrganizationMode.SERIES -> candidateSeriesDirectories(root, reader)
            LocalLibraryOrganizationMode.INDIVIDUAL_FILES -> candidateIndividualEntries(root, reader)
        }
    }

    private fun scanImage(entry: LocalScanFile): Boolean = !entry.directory &&
        (
            LocalMediaFormats.isImage(entry.extension) ||
                (
                    entry.extension !in SUPPORTED_FILE_EXTENSIONS &&
                        entry.file !is com.hippo.unifile.RemoteStorageFile &&
                        try {
                            ImageUtil.isImage(entry.name) { entry.file.openInputStream() }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                    )
            )

    private fun scanFolderImages(
        directory: UniFile,
        reader: LocalScanDirectoryReader,
    ): FolderImageScan {
        val entries = reader.list(directory)
            .filterNot { isLocalAuxiliaryFile(it.name) }
        val files = entries.filterNot { it.directory }
        val images = files.filter(::scanImage)
        return FolderImageScan(
            entries = entries,
            images = images,
            pureImages = images.size >= 2 && images.size == files.size,
        )
    }

    private fun candidateFolderEntries(
        root: ResolvedLocalLibraryRoot,
        reader: LocalScanDirectoryReader,
    ): List<ScanCandidate> {
        val result = mutableListOf<ScanCandidate>()
        val visited = mutableSetOf<String>()
        val createMarkers = preferences.getConfig().metadataStorage == LocalMetadataStorage.FOLDER_DIRECTORY
        fun visit(directory: UniFile, path: String) {
            check(visited.add(localDeletionIdentity(directory))) { "Cyclic local directory" }
            if (root.directory.uri.scheme == "file") {
                val base = File(checkNotNull(root.directory.uri.path)).canonicalFile.toPath()
                check(File(checkNotNull(directory.uri.path)).canonicalFile.toPath().startsWith(base))
            }
            val children = reader.list(directory).filterNot { it.name.startsWith('.') || ".importing-" in it.name }
            val content = children.filterNot { isLocalAuxiliaryFile(it.name) }
            val folderImages = scanFolderImages(directory, reader)
            val marker = path.takeIf(String::isNotEmpty)?.let {
                runCatching { metadataStore.folderMarker(directory, createMarkers) }.getOrNull()
            }
            val storedOverride = marker?.imageComicOverride
                ?: preferences.getIndex().itemsByLocation[root.config.id to path]?.imageComicOverride
            val imageSeriesEnabled = path.isNotEmpty() && storedOverride != false
            if (path.isNotEmpty()) {
                val hasDirectories = folderImages.entries.any(LocalScanFile::directory)
                val imageSeriesMarker = marker?.copy(id = "${marker.id}:image-series")
                if (!folderImages.pureImages || hasDirectories || !imageSeriesEnabled) {
                    result += ScanCandidate(
                        root.config,
                        directory,
                        path,
                        root.config.contentType,
                        LocalLibraryItem.Kind.FOLDER,
                        folderImages.entries.map { it.file }, folderImages.images.map { it.file }, reader.attributes,
                        marker = marker,
                        pureImages = folderImages.pureImages && !hasDirectories,
                    )
                }
                if (folderImages.pureImages && imageSeriesEnabled) {
                    val parentPath = path.substringBeforeLast('/', "")
                    val name = path.substringAfterLast('/')
                    val imagePath = if (hasDirectories) {
                        "$path/.koharia-image-series"
                    } else {
                        listOf(parentPath, ".koharia-image-series-$name")
                            .filter(String::isNotBlank)
                            .joinToString("/")
                    }
                    result += ScanCandidate(
                        root.config,
                        directory,
                        imagePath,
                        root.config.contentType,
                        LocalLibraryItem.Kind.FILE_ENTRY,
                        folderImages.entries.map { it.file },
                        folderImages.images.map { it.file },
                        reader.attributes,
                        marker = imageSeriesMarker,
                        pureImages = true,
                    )
                }
            }
            content.filter {
                !it.directory &&
                    it.extension in supportedExtensions(root.config.contentType) &&
                    !(folderImages.pureImages && imageSeriesEnabled && scanImage(it))
            }
                .forEach { file ->
                    val sidecars = children.filter {
                        it.name.equals(
                            "${file.name.substringBeforeLast('.')}.metadata.opf",
                            true,
                        ) ||
                            it.name.equals(
                                "${file.name.substringBeforeLast('.')}.ComicInfo.xml",
                                true,
                            )
                    }
                    result += ScanCandidate(
                        root.config,
                        file.file,
                        listOf(
                            path,
                            file.name,
                        ).filter(String::isNotBlank).joinToString("/"),
                        root.config.contentType,
                        LocalLibraryItem.Kind.FILE_ENTRY,
                        listOf(file.file) + sidecars.map {
                            it.file
                        },
                        listOf(file.file),
                        reader.attributes,
                    )
                }
            children.filter {
                it.directory
            }
                .forEach {
                    visit(
                        it.file,
                        listOf(
                            path,
                            it.name,
                        ).filter(String::isNotBlank).joinToString("/"),
                    )
                }
        }
        visit(root.directory, "")
        return result
    }

    private fun candidateSeriesDirectories(
        root: ResolvedLocalLibraryRoot,
        reader: LocalScanDirectoryReader,
    ): List<ScanCandidate> = reader.list(root.directory)
        .filter { it.directory && !it.name.startsWith('.') }
        .map { directory ->
            val files = reader.list(directory.file).filterNot { it.name.startsWith('.') }
            val chapters = files.filter { entry ->
                if (entry.directory) {
                    reader.list(entry.file).any(::scanImage)
                } else {
                    entry.extension in SUPPORTED_FILE_EXTENSIONS
                }
            }
            val contentType = if (root.config.contentType != LocalLibraryContentType.MIXED) {
                root.config.contentType
            } else {
                val hasBooks = chapters.any { !it.directory && it.extension in BOOK_FILE_EXTENSIONS }
                val hasComics = chapters.any { it.directory || it.extension in COMIC_FILE_EXTENSIONS }
                if (hasBooks && !hasComics) LocalLibraryContentType.BOOKS else LocalLibraryContentType.COMICS
            }
            ScanCandidate(
                root = root.config,
                resource = directory.file,
                relativePath = directory.name,
                contentType = contentType,
                kind = LocalLibraryItem.Kind.SERIES,
                files = files.map { it.file },
                chapterFiles = chapters.map { it.file },
                attributes = reader.attributes,
                marker = runCatching {
                    metadataStore.folderMarker(
                        directory.file,
                        preferences.getConfig().metadataStorage == LocalMetadataStorage.FOLDER_DIRECTORY,
                    )
                }.getOrNull(),
            )
        }

    private fun candidateIndividualEntries(
        root: ResolvedLocalLibraryRoot,
        reader: LocalScanDirectoryReader,
    ): List<ScanCandidate> {
        val entries = mutableListOf<ScanCandidate>()
        val visitedUris = mutableSetOf<String>()
        fun visit(directory: UniFile, parentPath: String) {
            if (!visitedUris.add(directory.uri.normalizeScheme().toString())) return
            val children = reader.list(directory).filterNot {
                it.name.startsWith('.') || ".importing-" in it.name
            }
            val directImages = children.filter(::scanImage)
            if (directImages.isNotEmpty()) {
                entries += ScanCandidate(
                    root = root.config,
                    resource = directory,
                    relativePath = parentPath.ifBlank { LocalLibraryLocator.ROOT_DIRECTORY_ENTRY },
                    contentType = root.config.contentType,
                    kind = LocalLibraryItem.Kind.FILE_ENTRY,
                    files = children.map { it.file },
                    chapterFiles = directImages.map { it.file },
                    attributes = reader.attributes,
                )
            }
            val supportedMedia = children.filter {
                !it.directory &&
                    it.extension in supportedExtensions(root.config.contentType)
            }
            val filesByName = children.filterNot { it.directory }.associateBy { it.name.lowercase() }
            supportedMedia.forEach { file ->
                val stem = file.name.substringBeforeLast('.')
                val sidecarNames = buildList {
                    add("$stem.metadata.opf")
                    add("$stem.ComicInfo.xml")
                }
                val sidecars = sidecarNames.mapNotNull { filesByName[it.lowercase()] }.distinctBy { it.file.uri }
                entries += ScanCandidate(
                    root = root.config,
                    resource = file.file,
                    relativePath = listOf(parentPath, file.name).filter(String::isNotBlank).joinToString("/"),
                    contentType = root.config.contentType,
                    kind = LocalLibraryItem.Kind.FILE_ENTRY,
                    files = listOf(file.file) + sidecars.map { it.file },
                    chapterFiles = listOf(file.file),
                    attributes = reader.attributes,
                )
            }
            children.filter { it.directory }.forEach { child ->
                visit(child.file, listOf(parentPath, child.name).filter(String::isNotBlank).joinToString("/"))
            }
        }
        visit(root.directory, "")
        return entries.distinctBy { it.relativePath }
    }

    private fun supportedExtensions(contentType: LocalLibraryContentType): Set<String> = when (contentType) {
        LocalLibraryContentType.COMICS -> COMIC_LIBRARY_EXTENSIONS
        LocalLibraryContentType.BOOKS -> BOOK_LIBRARY_EXTENSIONS
        LocalLibraryContentType.MIXED -> SUPPORTED_FILE_EXTENSIONS
    }

    private fun detectSeriesContentType(
        root: LocalLibraryRootConfig,
        directory: UniFile,
    ): LocalLibraryContentType {
        val items = directory.listFiles().orEmpty().filter(::isSupportedChapter)
        return detectSeriesContentType(root, items)
    }

    private fun detectSeriesContentType(
        root: LocalLibraryRootConfig,
        items: List<UniFile>,
    ): LocalLibraryContentType {
        if (root.contentType != LocalLibraryContentType.MIXED) return root.contentType
        val hasBooks = items.any { !it.isDirectory && it.extension.orEmpty().lowercase() in BOOK_FILE_EXTENSIONS }
        val hasComics = items.any {
            it.isDirectory || it.extension.orEmpty().lowercase() in COMIC_FILE_EXTENSIONS
        }
        return if (hasBooks && !hasComics) LocalLibraryContentType.BOOKS else LocalLibraryContentType.COMICS
    }

    private fun resolveResource(url: String): ResolvedLocalResource? {
        val location = LocalLibraryLocator.location(url, id) ?: return null
        val config = preferences.getConfig()
        val roots = if (location.rootId != null) {
            config.roots.filter { it.id == location.rootId }
        } else {
            config.roots
        }
        roots.forEach { root ->
            val relative = if (location.rootId == null && root.relativePath.isNotBlank()) {
                location.relativePath.removePrefix("${root.relativePath}/")
            } else {
                location.relativePath
            }
            val index = preferences.getIndex()
            val indexed = index.itemsByLocator[root.id to relative]
                ?: index.itemsByKey[LocalLibraryLocator.itemKey(root.id, relative)]
            if (indexed?.missing == true) return@forEach
            val resolvedPath = indexed?.physicalPath() ?: relative
            if (relative.startsWith(".koharia/nodes/") && indexed == null) return@forEach
            val base = preferences.resolveRoot(context, root) ?: return@forEach
            if (resolvedPath == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY) {
                return ResolvedLocalResource(root, base, relative, indexed?.relativePath ?: relative)
            }
            val file = LocalLibraryLocator.normalize(resolvedPath)
                .split('/')
                .filter(String::isNotBlank)
                .fold(base) { parent, segment -> parent.findFile(segment) ?: return@forEach }
            return ResolvedLocalResource(
                root = root,
                file = file,
                relativePath = LocalLibraryLocator.normalize(resolvedPath),
                indexedPath = indexed?.relativePath ?: LocalLibraryLocator.normalize(resolvedPath),
            )
        }
        return null
    }

    private fun metadataDirectory(resource: ResolvedLocalResource): UniFile? {
        if (resource.file.isDirectory) return resource.file
        val base = preferences.resolveRoot(context, resource.root) ?: return null
        val parentPath = resource.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        return LocalLibraryLocator.normalize(parentPath)
            .split('/')
            .filter(String::isNotBlank)
            .fold(base) { parent, segment -> parent.findFile(segment) ?: return null }
    }

    private fun legacyRelativePath(resource: ResolvedLocalResource): String {
        return listOf(resource.root.relativePath, resource.relativePath)
            .filter(String::isNotBlank)
            .joinToString("/")
    }

    private suspend fun emitLibraryRefresh() {
        mutableLibraryRefreshes.emit(
            ConnectionLibraryRefreshResult(
                itemCount = preferences.getIndex().items.count {
                    it.kind in setOf(
                        LocalLibraryItem.Kind.SERIES,
                        LocalLibraryItem.Kind.FILE_ENTRY,
                        LocalLibraryItem.Kind.FOLDER,
                    )
                },
                refreshedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun scanLibrary(): ConnectionLibraryRefreshResult = withIOContext {
        val defaultChapterFlags = Injekt.get<SharedAppPreferences>().libraryPreferences().defaultChapterFlags()
        val previousIndex = preferences.getIndex()
        val existingByUrl = mangaRepository.getMangaBySourceId(id).associateBy(Manga::url)
        val metadataOverrides = preferences.getMetadataOverrides()
        val config = preferences.getConfig()
        val configuredRootIds = config.roots.mapTo(mutableSetOf(), LocalLibraryRootConfig::id)
        val successfulRootIds = mutableSetOf<String>()
        val failedRoots = mutableListOf<LocalLibraryRootConfig>()
        val previewUrls = existingByUrl.keys.toMutableSet()
        var lastPreview = 0L
        val candidates = buildList {
            config.roots.forEach { root ->
                try {
                    koharia.storage.NetworkStorageRuntime.fromUri(context, Uri.parse(root.treeUri))?.let { remote ->
                        if (remote.runtime.config.persistentIdentity) remote.runtime.identities.refresh()
                        remote.runtime.snapshot.scan(
                            koharia.storage.StoragePath.normalize(
                                listOf(
                                    remote.storagePath,
                                    root.relativePath,
                                ).filter(String::isNotEmpty).joinToString("/"),
                            ),
                            refresh = true,
                            onDirectory = {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastPreview >= 500) {
                                    val directory = preferences.resolveRoot(context, root)
                                    if (directory != null) {
                                        val preview =
                                            candidateResources(
                                                ResolvedLocalLibraryRoot(root, directory),
                                                partial = true,
                                            )
                                        val staged =
                                            buildIndex(
                                                preview,
                                                preferences.getIndex(),
                                                previousIndex.scannedAt,
                                                configuredRootIds,
                                                emptySet(),
                                            )
                                        preferences.setIndex(staged)
                                        val additions = preview.mapNotNull { candidate ->
                                            val item =
                                                staged.itemsByLocation[candidate.root.id to candidate.relativePath]
                                                    ?: return@mapNotNull null
                                            val url = LocalLibraryLocator.entryUrl(id, item.rootId, item.locatorPath)
                                            if (!previewUrls.add(url)) return@mapNotNull null
                                            SManga.create().apply {
                                                this.url = url
                                                title = candidate.resource.nameWithoutExtension.orEmpty()
                                                thumbnail_url = url
                                                initialized = false
                                            }.toDomainManga(id).copy(chapterFlags = defaultChapterFlags)
                                        }
                                        if (additions.isNotEmpty()) {
                                            mangaRepository.insertNetworkManga(additions)
                                            mutableLibraryRefreshes.emit(
                                                ConnectionLibraryRefreshResult(
                                                    staged.items.size,
                                                    System.currentTimeMillis(),
                                                ),
                                            )
                                        }
                                    }
                                    lastPreview = now
                                }
                            },
                        )
                    }
                    val directory = preferences.resolveRoot(context, root)
                        ?: throw IOException("Unable to resolve local library directory: ${root.displayPath}")
                    addAll(candidateResources(ResolvedLocalLibraryRoot(root, directory)))
                    successfulRootIds += root.id
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failedRoots += root
                    logcat(LogPriority.WARN, error) {
                        "Unable to scan local library directory ${root.displayPath}"
                    }
                }
            }
        }
        val stagedIndex = preferences.getIndex()
        val previousItems = stagedIndex.items.ifEmpty {
            recoverLocalLibraryItems(
                sourceId = id,
                config = config,
                mangaUrls = existingByUrl.keys,
            )
        }
        val effectivePreviousIndex = stagedIndex.copy(items = previousItems)
        val refreshedAt = System.currentTimeMillis()
        val refreshedIndex = buildIndex(
            candidates = candidates,
            previousIndex = effectivePreviousIndex,
            scannedAt = refreshedAt.takeIf { successfulRootIds.isNotEmpty() } ?: previousIndex.scannedAt,
            configuredRootIds = configuredRootIds,
            successfulRootIds = successfulRootIds,
        )
        val previousEntriesByKey = previousIndex.items
            .filter {
                it.kind in setOf(
                    LocalLibraryItem.Kind.SERIES,
                    LocalLibraryItem.Kind.FILE_ENTRY,
                    LocalLibraryItem.Kind.FOLDER,
                )
            }
            .associateBy { it.itemKey }

        val changedManga = candidates.chunked(SCAN_METADATA_BATCH_SIZE).flatMap { batch ->
            coroutineScope {
                batch.map { candidate ->
                    async {
                        try {
                            val scannedItem =
                                refreshedIndex.itemsByLocation.getValue(candidate.root.id to candidate.relativePath)
                            val url = LocalLibraryLocator.entryUrl(id, candidate.root.id, scannedItem.locatorPath)
                            val existing = existingByUrl[url]
                            val itemKey = scannedItem.itemKey
                            val unchanged = previousIndex.schemaVersion >= 7 && existing?.initialized == true &&
                                previousEntriesByKey[itemKey]?.fingerprint == scannedItem.fingerprint
                            if (unchanged &&
                                config.metadataStorage == LocalMetadataStorage.DATABASE &&
                                config.organizationMode(candidate.root) != LocalLibraryOrganizationMode.FOLDER
                            ) {
                                return@async null
                            }

                            (existing?.toSManga()?.takeIf { unchanged } ?: SManga.create()).apply {
                                if (!unchanged) {
                                    title = if (candidate.resource.isDirectory) {
                                        candidate.resource.name.orEmpty()
                                    } else {
                                        candidate.resource.nameWithoutExtension.orEmpty()
                                    }
                                }
                                this.url = url
                                val metadataRole = scannedItem.metadataRole(config.organizationMode(candidate.root))
                                val deferEmbeddedMetadata = candidate.resource is com.hippo.unifile.RemoteStorageFile
                                if (scannedItem.kind == LocalLibraryItem.Kind.FILE_ENTRY &&
                                    (!unchanged || scannedItem.isVirtualImageSeries()) && !deferEmbeddedMetadata
                                ) {
                                    applyIndividualMetadata(
                                        manga = this,
                                        resource = ResolvedLocalResource(
                                            root = candidate.root,
                                            file = candidate.resource,
                                            relativePath = candidate.relativePath,
                                        ),
                                        metadataOverrides = metadataOverrides,
                                        scannedFiles = candidate.files,
                                        role = metadataRole,
                                    )
                                } else if (scannedItem.kind == LocalLibraryItem.Kind.FOLDER) {
                                    applyFolderDisplay(this, scannedItem)
                                    thumbnail_url = url
                                } else if (!unchanged && scannedItem.kind == LocalLibraryItem.Kind.SERIES &&
                                    !deferEmbeddedMetadata
                                ) {
                                    applySeriesMetadata(
                                        manga = this,
                                        root = candidate.root,
                                        directory = candidate.resource,
                                        relativePath = candidate.relativePath,
                                        files = candidate.files,
                                        metadataOverrides = metadataOverrides,
                                        itemKey = scannedItem.itemKey,
                                    )
                                }
                                if (config.organizationMode(candidate.root) == LocalLibraryOrganizationMode.FOLDER &&
                                    metadataRole != LocalMetadataRole.FOLDER_CONTAINER
                                ) {
                                    thumbnail_url =
                                        url
                                }
                                val metadataDirectory =
                                    if (candidate.resource.isDirectory) {
                                        candidate.resource
                                    } else {
                                        metadataDirectory(
                                            ResolvedLocalResource(
                                                candidate.root,
                                                candidate.resource,
                                                candidate.relativePath,
                                            ),
                                        )
                                    }
                                metadataStore.readAndMigrate(
                                    scannedItem.itemKey,
                                    metadataDirectory,
                                    scannedItem.contentType,
                                    candidate.resource.takeUnless(UniFile::isDirectory)?.name,
                                    role = metadataRole,
                                    legacyImageOwnership = metadataRole == LocalMetadataRole.FOLDER_IMAGE_SERIES &&
                                        candidate.files.none(UniFile::isDirectory) && candidate.pureImages,
                                )?.applyTo(this)
                                if (metadataRole.isMetadataReadable()) {
                                    metadataOverrides[scannedItem.itemKey]?.applyTo(this)
                                }
                                if (deferEmbeddedMetadata) thumbnail_url = url
                                initialized = !deferEmbeddedMetadata || existing?.initialized == true
                            }.toDomainManga(id).let { manga ->
                                if (unchanged &&
                                    existing.title == manga.title && existing.author == manga.author &&
                                    existing.artist == manga.artist && existing.description == manga.description &&
                                    existing.genre == manga.genre && existing.status == manga.status &&
                                    existing.thumbnailUrl == manga.thumbnailUrl
                                ) {
                                    return@async null
                                }
                                if (existing == null) manga.copy(chapterFlags = defaultChapterFlags) else manga
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            logcat(LogPriority.WARN, error) {
                                "Unable to read local metadata for ${candidate.relativePath}"
                            }
                            null
                        }
                    }
                }.awaitAll()
            }
        }.filterNotNull()
        if (changedManga.isNotEmpty()) {
            val changedExistingManga = changedManga.mapNotNull { existingByUrl[it.url] }
            changedExistingManga.forEach { coverCache.deleteFromCache(it) }
            if (changedExistingManga.isNotEmpty()) {
                mangaRepository.updateAll(
                    changedExistingManga.map { manga ->
                        MangaUpdate(id = manga.id, coverLastModified = refreshedAt)
                    },
                )
            }
            mangaRepository.insertNetworkManga(changedManga)
        }
        preferences.setIndex(refreshedIndex)
        if (failedRoots.isNotEmpty()) {
            throw LocalLibraryPartialScanException(
                failedRootIds = failedRoots.mapTo(mutableSetOf(), LocalLibraryRootConfig::id),
                message = context.stringResource(MR.strings.local_library_scan_incomplete),
            )
        }
        ConnectionLibraryRefreshResult(
            itemCount = refreshedIndex.items.count {
                it.kind in setOf(
                    LocalLibraryItem.Kind.SERIES,
                    LocalLibraryItem.Kind.FILE_ENTRY,
                    LocalLibraryItem.Kind.FOLDER,
                )
            },
            refreshedAt = refreshedAt,
        )
    }

    private fun buildIndex(
        candidates: List<ScanCandidate>,
        previousIndex: LocalLibraryIndex,
        scannedAt: Long,
        configuredRootIds: Set<String>,
        successfulRootIds: Set<String>,
    ): LocalLibraryIndex {
        val scannedItems = candidates.flatMap { candidate ->
            val resource = candidate.attributes.getValue(candidate.resource.uri)
            if (candidate.kind != LocalLibraryItem.Kind.SERIES) {
                return@flatMap listOf(
                    LocalLibraryItem(
                        itemKey = LocalLibraryLocator.itemKey(candidate.root.id, candidate.relativePath),
                        rootId = candidate.root.id,
                        relativePath = candidate.relativePath,
                        contentType = candidate.contentType,
                        kind = candidate.kind,
                        documentIdentity = localDocumentIdentity(candidate.resource)?.let {
                            if (localImageSeriesPhysicalPath(candidate.relativePath) != null) "$it:image-series" else it
                        },
                        folderIdentity = candidate.marker?.id,
                        virtualType = LocalLibraryItem.VirtualType.IMAGE_SERIES.takeIf {
                            localImageSeriesPhysicalPath(candidate.relativePath) !=
                                null
                        },
                        backingPath = localImageSeriesPhysicalPath(candidate.relativePath),
                        imageComic = candidate.marker?.imageComic == true,
                        imageComicOverride = candidate.marker?.imageComicOverride,
                        format = if (resource.directory) {
                            "directory"
                        } else {
                            resource.extension
                        },
                        sizeBytes = if (resource.directory) {
                            candidate.chapterFiles.sumOf { candidate.attributes.getValue(it.uri).sizeBytes }
                        } else {
                            resource.sizeBytes
                        },
                        modifiedAt = resource.modifiedAt,
                        fingerprint = candidate.fingerprint,
                    ),
                )
            }
            val seriesPath = candidate.relativePath
            val seriesItem = LocalLibraryItem(
                itemKey = LocalLibraryLocator.itemKey(candidate.root.id, seriesPath),
                rootId = candidate.root.id,
                relativePath = seriesPath,
                contentType = candidate.contentType,
                kind = LocalLibraryItem.Kind.SERIES,
                format = "directory",
                sizeBytes = candidate.chapterFiles.sumOf { candidate.attributes.getValue(it.uri).sizeBytes },
                modifiedAt = resource.modifiedAt,
                fingerprint = candidate.fingerprint,
                documentIdentity = localDocumentIdentity(candidate.resource),
                folderIdentity = candidate.marker?.id,
            )
            val chapterItems = candidate.chapterFiles
                .map { file ->
                    val attributes = candidate.attributes.getValue(file.uri)
                    val path = "$seriesPath/${attributes.name}"
                    LocalLibraryItem(
                        itemKey = LocalLibraryLocator.itemKey(candidate.root.id, path),
                        rootId = candidate.root.id,
                        relativePath = path,
                        contentType = candidate.contentType,
                        kind = LocalLibraryItem.Kind.CHAPTER,
                        format = if (attributes.directory) {
                            "directory"
                        } else {
                            attributes.extension
                        },
                        sizeBytes = attributes.sizeBytes,
                        modifiedAt = attributes.modifiedAt,
                        fingerprint = fingerprint(listOf(attributes)),
                        documentIdentity = localDocumentIdentity(file),
                    )
                }
            listOf(seriesItem) + chapterItems
        }
        val candidatesByLocation = candidates.associateBy { it.root.id to it.relativePath }
        val stableRoots = configuredRootIds
        val folderItems = withLocalFolderFingerprints(
            reconcileLocalFolders(
                scannedItems.filter {
                    it.rootId in stableRoots
                },
                previousIndex.items.filter {
                    it.rootId in stableRoots
                },
            ),
        )
            .map { item ->
                val candidate = candidatesByLocation[item.rootId to item.relativePath] ?: return@map item
                val imageComic = when (item.imageComicOverride) {
                    true -> candidate.pureImages
                    false -> false
                    null -> candidate.pureImages
                }
                item.copy(imageComic = imageComic)
            }
        val folderKeys = folderItems.mapTo(mutableSetOf()) { it.itemKey }
        val missing = previousIndex.items.filter {
            it.rootId in stableRoots &&
                it.rootId in successfulRootIds &&
                it.itemKey !in folderKeys
        }
            .map {
                it.copy(missing = true)
            }
        val items = mergeLocalLibraryScanItems(
            scannedItems = folderItems + missing,
            previousItems = previousIndex.items,
            configuredRootIds = configuredRootIds,
            successfulRootIds = successfulRootIds,
        )

        val previousChapterSignatures = previousIndex.chapterSignatures()
        val currentIndex = LocalLibraryIndex(
            schemaVersion = 7,
            scannedAt = scannedAt,
            items = items,
        )
        val currentChapterSignatures = currentIndex.chapterSignatures()
        val currentSeriesKeys = items
            .filter { it.kind == LocalLibraryItem.Kind.SERIES && !it.missing }
            .mapTo(mutableSetOf(), LocalLibraryItem::itemKey)
        val changedSeriesKeys = currentSeriesKeys.filterTo(mutableSetOf()) { itemKey ->
            previousChapterSignatures[itemKey] != currentChapterSignatures[itemKey]
        }
        return currentIndex.copy(
            pendingChapterRefreshItemKeys = (
                previousIndex.pendingChapterRefreshItemKeys + changedSeriesKeys
                ).intersect(currentSeriesKeys),
        )
    }

    private fun LocalLibraryIndex.chapterSignatures(): Map<String, List<String>> {
        return chaptersBySeriesKey.mapValues { (_, chapters) ->
            chapters.map { item ->
                listOf(
                    item.relativePath,
                    item.format,
                    item.sizeBytes.toString(),
                    item.modifiedAt.toString(),
                    item.fingerprint.orEmpty(),
                ).joinToString("\u0000")
            }.sorted()
        }
    }

    private fun localDocumentIdentity(file: UniFile): String? = runCatching {
        if (file is com.hippo.unifile.RemoteStorageFile) {
            return@runCatching kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                file.runtime.cached(file.storagePath)?.let { file.runtime.identities.identity(it) }
            }
        }
        when (file.uri.scheme) {
            "content" -> "${file.uri.authority}:${DocumentsContract.getDocumentId(file.uri)}"
            "file" -> Os.stat(checkNotNull(file.uri.path)).let { "file:${it.st_dev}:${it.st_ino}" }
            else -> null
        }
    }.getOrNull()

    private fun sanitizeImportName(value: String): String {
        return value
            .replace(IMPORT_INVALID_CHARACTERS, "_")
            .replace(Regex("[\\u0000-\\u001f]"), "")
            .trim()
            .trim('.')
            .take(160)
    }

    private fun sanitizeImportFileName(value: String, extension: String): String {
        val safeExtension = extension.lowercase().takeIf { it in SUPPORTED_FILE_EXTENSIONS }
            ?: error("Unsupported imported media extension")
        val sanitized = value
            .replace(IMPORT_INVALID_CHARACTERS, "_")
            .replace(Regex("[\\u0000-\\u001f]"), "")
            .trim()
            .trim('.')
        val baseName = sanitized
            .takeIf { it.substringAfterLast('.', "").equals(safeExtension, ignoreCase = true) }
            ?.substringBeforeLast('.')
            ?: sanitized
        val maxBaseLength = 160 - safeExtension.length - 1
        return "${baseName.take(maxBaseLength).ifBlank { "Imported media" }}.$safeExtension"
    }

    private fun uniqueImportName(directory: UniFile, requestedName: String): String {
        val fallback = requestedName.ifBlank { "Imported media" }
        if (directory.findFile(fallback) == null) return fallback
        val extension = fallback.substringAfterLast('.', missingDelimiterValue = "")
        val base = if (extension.isBlank()) fallback else fallback.removeSuffix(".$extension")
        return generateSequence(2) { it + 1 }
            .map { index -> if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension" }
            .first { directory.findFile(it) == null }
    }

    companion object {
        internal val MANGA_BEHAVIOR = ConnectionMangaBehavior(
            supportsChapterCoverGrid = true,
            allowsChapterDownloads = false,
            providerManagedLibrary = true,
            allowsLocalLibraryManagement = false,
            allowsCategoryManagement = false,
            allowsFetchIntervalManagement = false,
            showSourceName = true,
            detailsRefreshIntervalMillis = null,
        )
        private val COMIC_LIBRARY_EXTENSIONS = LocalMediaFormats.comicExtensions + LocalMediaFormats.epub.extensions
        private val BOOK_LIBRARY_EXTENSIONS = LocalMediaFormats.bookExtensions
        private val BOOK_FILE_EXTENSIONS = LocalMediaFormats.bookExtensions
        private val SUPPORTED_FILE_EXTENSIONS = LocalMediaFormats.allExtensions
        private val COMIC_FILE_EXTENSIONS = SUPPORTED_FILE_EXTENSIONS - BOOK_FILE_EXTENSIONS
        private val IMPORT_INVALID_CHARACTERS = Regex("[\\\\/:*?\"<>|]")
        private const val SCAN_METADATA_BATCH_SIZE = 8
    }

    private data class ScanCandidate(
        val root: LocalLibraryRootConfig,
        val resource: UniFile,
        val relativePath: String,
        val contentType: LocalLibraryContentType,
        val kind: LocalLibraryItem.Kind,
        val files: List<UniFile>,
        val chapterFiles: List<UniFile>,
        val attributes: Map<Uri, LocalScanFile>,
        val marker: LocalFolderMarker? = null,
        val pureImages: Boolean = false,
    ) {
        val fingerprint = relativePath + ":" + fingerprint(
            (files + resource).distinctBy {
                it.uri
            }
                .map {
                    attributes.getValue(it.uri)
                },
        )
    }

    private data class FolderImageScan(
        val entries: List<LocalScanFile>,
        val images: List<LocalScanFile>,
        val pureImages: Boolean,
    )

    private data class ResolvedLocalResource(
        val root: LocalLibraryRootConfig,
        val file: UniFile,
        val relativePath: String,
        val indexedPath: String = relativePath,
    )
}

private fun LocalLibraryContentType.toConnectionMediaType(): ConnectionMediaType = when (this) {
    LocalLibraryContentType.COMICS -> ConnectionMediaType.COMIC
    LocalLibraryContentType.BOOKS -> ConnectionMediaType.BOOK
    LocalLibraryContentType.MIXED -> ConnectionMediaType.MIXED
}

internal class LocalLibraryScopeFilter(
    scope: LibraryContentScope,
) : eu.kanade.tachiyomi.source.model.Filter.Select<LibraryContentScope>(
    name = "local-library-content-scope",
    values = LibraryContentScope.entries.toTypedArray(),
    state = LibraryContentScope.entries.indexOf(scope),
) {
    val scope: LibraryContentScope
        get() = values[state]
}

internal fun localLibraryContentScopes(config: LocalLibraryConfig): Set<LibraryContentScope> {
    val contentTypes = config.roots.mapTo(mutableSetOf()) { it.contentType }
    val hasSeparatedComicAndBookRoots = LocalLibraryContentType.COMICS in contentTypes &&
        LocalLibraryContentType.BOOKS in contentTypes &&
        LocalLibraryContentType.MIXED !in contentTypes
    return if (hasSeparatedComicAndBookRoots) {
        setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK)
    } else {
        setOf(LibraryContentScope.ALL)
    }
}

private fun LocalLibraryConfig.toConnectionLibraryShelves(context: Context): List<ConnectionLibraryShelf> {
    return buildList {
        addAll(
            bookshelvesFor(LocalLibraryContentType.COMICS)
                .mapIndexed { index, shelf -> shelf.toConnectionLibraryShelf(context, isDefault = index == 0) },
        )
        addAll(
            bookshelvesFor(LocalLibraryContentType.BOOKS)
                .mapIndexed { index, shelf -> shelf.toConnectionLibraryShelf(context, isDefault = index == 0) },
        )
    }
}

private fun LocalBookshelf.toConnectionLibraryShelf(
    context: Context,
    isDefault: Boolean = false,
): ConnectionLibraryShelf {
    val defaultName = when (contentType) {
        LocalLibraryContentType.COMICS -> MR.strings.local_library_default_comics_bookshelf
        LocalLibraryContentType.BOOKS -> MR.strings.local_library_default_books_bookshelf
        LocalLibraryContentType.MIXED -> MR.strings.local_library_bookshelves
    }
    return ConnectionLibraryShelf(
        id = id,
        name = name.ifBlank { context.stringResource(defaultName) },
        contentScope = when (contentType) {
            LocalLibraryContentType.COMICS -> LibraryContentScope.COMIC
            LocalLibraryContentType.BOOKS -> LibraryContentScope.BOOK
            LocalLibraryContentType.MIXED -> LibraryContentScope.ALL
        },
        isDefault = isDefault,
    )
}

private fun LocalLibraryContentType.matches(scope: LibraryContentScope?): Boolean {
    return when (scope) {
        null, LibraryContentScope.ALL -> true
        LibraryContentScope.COMIC -> this == LocalLibraryContentType.COMICS || this == LocalLibraryContentType.MIXED
        LibraryContentScope.BOOK -> this == LocalLibraryContentType.BOOKS || this == LocalLibraryContentType.MIXED
    }
}

private fun LocalMetadataOverride.toLibraryMetadata() = LibraryMetadata(
    title = title,
    author = author,
    artist = artist,
    description = description,
    genres = genres,
    status = status,
    editedFields = editedFields.mapNotNull { value ->
        runCatching { LibraryMetadataField.valueOf(value.uppercase()) }.getOrNull()
    }.toSet(),
    lockedFields = lockedFields,
    source = source,
)

private fun LibraryMetadata.toLocalMetadataOverride() = LocalMetadataOverride(
    title = title,
    author = author,
    artist = artist,
    description = description,
    genres = genres,
    status = status,
    editedFields = editedFields.mapTo(mutableSetOf()) { it.name.lowercase() },
    lockedFields = lockedFields,
    source = source,
)

private fun fingerprint(files: List<LocalScanFile>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.name.lowercase() }.forEach { file ->
        val entry = listOf(file.name, file.directory.toString(), file.sizeBytes.toString(), file.modifiedAt.toString())
            .joinToString("\u0000", postfix = "\u0001")
        digest.update(entry.toByteArray(StandardCharsets.UTF_8))
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
