package koharia.source.local

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
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
import koharia.connection.ConnectionMetadataGenerationAdapter
import koharia.connection.ConnectionSeriesCoverAdapter
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.connection.LibraryMetadata
import koharia.connection.LibraryMetadataSuggestion
import koharia.connection.MetadataFilenameTemplate
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import tachiyomi.core.common.util.system.logcat
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
    ConnectionLibraryRefreshAdapter,
    ConnectionLibraryMembershipAdapter,
    ConnectionLibraryShelfAdapter,
    ConnectionMangaBehaviorAdapter,
    ConnectionMediaImportAdapter,
    ConnectionChapterThumbnailAdapter,
    ConnectionLocalFileAdapter,
    koharia.connection.ConnectionReaderRoutingAdapter,
    ConnectionSeriesCoverAdapter,
    ConnectionMetadataGenerationAdapter,
    ConnectionMetadataAdapter {

    private val json = Injekt.get<kotlinx.serialization.json.Json>()
    private val xml: XML by injectLazy()
    private val preferences by lazy { LocalLibraryPreferences(id, json) }

    override fun seriesSettingsAvailable() = preferences.configChanges().map { config ->
        config.roots.any { config.organizationMode(it) == LocalLibraryOrganizationMode.SERIES }
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
    override val mangaBehavior: ConnectionMangaBehavior = MANGA_BEHAVIOR

    override fun toString(): String = name

    override fun chapterThumbnailUrl(chapterUrl: String): String = chapterUrl

    override suspend fun loadChapterThumbnail(chapterUrl: String): ByteArray? = withIOContext {
        val file = localChapterFile(chapterUrl) ?: return@withIOContext null
        runCatching { firstImageBytes(file) }
            .onFailure { error ->
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
        val result = runCatching {
            refreshMutex.withLock {
                scanLibrary()
            }
        }
        result.getOrNull()?.let { mutableLibraryRefreshes.emit(it) }
        return result
    }

    suspend fun needsInitialScan(): Boolean = withIOContext {
        val index = preferences.getIndex()
        if (index.scannedAt <= 0L || index.schemaVersion < 5) return@withIOContext true
        val indexedUrls = index.items.asSequence()
            .filter {
                it.kind in setOf(LocalLibraryItem.Kind.SERIES, LocalLibraryItem.Kind.FILE_ENTRY) &&
                    it.rootId.isNotBlank()
            }
            .map { item -> LocalLibraryLocator.entryUrl(id, item.rootId, item.relativePath) }
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
                        LocalLibraryLocator.entryUrl(id, it.rootId, it.relativePath) == manga.url
                } ?: error("Local library entry is no longer available")
                val root = roots.first { it.id == item.rootId }
                LocalLibraryDeletionEntry(
                    manga = manga,
                    root = root,
                    item = item,
                    deletion = LocalFileDeletion.prepare(
                        root = checkNotNull(directories[root]),
                        path = item.relativePath,
                        series = item.kind == LocalLibraryItem.Kind.SERIES,
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
                                        entry.item.kind == LocalLibraryItem.Kind.SERIES &&
                                            item.relativePath.startsWith("${entry.item.relativePath}/")
                                        )
                                )
                        }
                        val urls = removedItems.mapTo(mutableSetOf()) {
                            LocalLibraryLocator.entryUrl(id, it.rootId, it.relativePath)
                        }
                        existingMangas.filter { it.url in urls }.forEach { manga ->
                            coverCache.deleteFromCache(manga)
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
        return browseIndexedLibrary(mangas = mangas, query = "")
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
    ): List<Manga> = withIOContext {
        val index = preferences.getIndex()
        val assignments = preferences.getBookshelfAssignments()
        val config = preferences.getConfig()
        val rootsById = config.roots.associateBy { it.id }
        val libraryItems = index.libraryItemsByKey
        val chapterNames = index.chapterNamesBySeriesKey
        val chapterFormats = index.chapterFormatsBySeriesKey

        val filtered = mangas
            .mapNotNull { manga ->
                val location = LocalLibraryLocator.location(manga.url, id) ?: return@mapNotNull null
                val rootId = location.rootId ?: return@mapNotNull null
                val itemKey = LocalLibraryLocator.itemKey(rootId, location.relativePath)
                val indexedItem = libraryItems[itemKey] ?: return@mapNotNull null
                val root = rootsById[rootId] ?: return@mapNotNull null
                if (!indexedItem.contentType.matches(scope)) return@mapNotNull null
                if (
                    bookshelfId != null && config.effectiveBookshelfId(
                        root = root,
                        itemKey = itemKey,
                        assignments = assignments,
                        contentType = indexedItem.contentType,
                    ) != bookshelfId
                ) {
                    return@mapNotNull null
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
        if (filters.descending) sorted.reversed() else sorted
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
                .filter { it.kind in setOf(LocalLibraryItem.Kind.SERIES, LocalLibraryItem.Kind.FILE_ENTRY) }
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
        val itemKey = LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath)
        return preferences.getConfig().effectiveBookshelfId(
            root = resource.root,
            itemKey = itemKey,
            assignments = preferences.getBookshelfAssignments(),
            contentType = indexedItem.contentType,
        )
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
        val mode = item.organizationMode()
        return preferences.getConfig().bookshelvesFor(item.contentType)
            .filter { it.organizationMode == mode }
            .map { it.toConnectionLibraryShelf(context) }
    }

    override suspend fun moveMangaToLibraryShelf(mangaUrl: String, shelfId: String): Result<Unit> = runCatching {
        val resource = resolveResource(mangaUrl) ?: error("Invalid local manga URL")
        val item = indexedLibraryItem(resource) ?: error("Local library entry is not indexed")
        val validShelfIds = preferences.getConfig()
            .bookshelvesFor(item.contentType)
            .filter { it.organizationMode == item.organizationMode() }
            .mapTo(mutableSetOf()) { it.id }
        require(shelfId in validShelfIds) { "Bookshelf does not match local content type or organization mode" }
        preferences.setBookshelfAssignment(
            itemKey = LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath),
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
                            LocalLibraryOrganizationMode.SERIES -> MR.strings.local_library_mode_series
                            LocalLibraryOrganizationMode.INDIVIDUAL_FILES ->
                                MR.strings.local_library_mode_individual
                        },
                    ),
                ).joinToString(" · "),
                mediaType = root.contentType.toConnectionMediaType(),
                supportedExtensions = when (root.contentType) {
                    LocalLibraryContentType.COMICS -> LocalMediaFormats.comicImportExtensions
                    LocalLibraryContentType.BOOKS -> BOOK_LIBRARY_EXTENSIONS
                    LocalLibraryContentType.MIXED -> LocalMediaFormats.documentImportExtensions
                },
                defaultShelfId = root.bookshelfId.ifBlank { config.defaultBookshelfId(root.contentType) }
                    .ifBlank { null },
                grouping = when (organizationMode) {
                    LocalLibraryOrganizationMode.SERIES -> ConnectionMediaGrouping.SERIES
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES -> ConnectionMediaGrouping.INDIVIDUAL
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
            .filter { it.kind == LocalLibraryItem.Kind.SERIES && it.rootId == root.id }
            .map { item ->
                val resourceUrl = LocalLibraryLocator.seriesUrl(id, root.id, item.relativePath)
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

    override suspend fun importMedia(request: ConnectionMediaImportRequest): Result<ConnectionMediaImportResult> {
        return try {
            Result.success(
                withIOContext {
                    val config = preferences.getConfig()
                    val root = config.roots.firstOrNull { it.id == request.destinationId }
                        ?: error("Local import destination no longer exists")
                    val destination = preferences.resolveRoot(context, root)
                        ?: error("Local import destination is unavailable")
                    val supportedExtensions = when (root.contentType) {
                        LocalLibraryContentType.COMICS -> LocalMediaFormats.comicImportExtensions
                        LocalLibraryContentType.BOOKS -> BOOK_LIBRARY_EXTENSIONS
                        LocalLibraryContentType.MIXED -> LocalMediaFormats.documentImportExtensions
                    }
                    require(request.items.isNotEmpty()) { "No media selected for import" }
                    require(request.items.all { it.extension.lowercase() in supportedExtensions }) {
                        "Imported media does not match the destination type"
                    }
                    val organizationMode = config.organizationMode(root)
                    val importContentType = when (root.contentType) {
                        LocalLibraryContentType.MIXED -> if (
                            request.items.all { it.extension.lowercase() in BOOK_LIBRARY_EXTENSIONS }
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
                    if (organizationMode == LocalLibraryOrganizationMode.INDIVIDUAL_FILES) {
                        return@withIOContext importIndividualMedia(
                            root = root,
                            destination = destination,
                            request = request,
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
                    val itemKey = LocalLibraryLocator.itemKey(root.id, seriesName)
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
                    } catch (error: Throwable) {
                        rollBackImport()
                        throw error
                    }
                    ConnectionMediaImportResult(
                        resourceUrls = listOf(
                            existingResource?.let { request.existingSeriesId }
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
                    val itemKey = LocalLibraryLocator.itemKey(root.id, fileName)
                    previousAssignments[itemKey] = preferences.getBookshelfAssignments()[itemKey]
                    request.shelfId?.takeIf(String::isNotBlank)?.let { shelfId ->
                        preferences.setBookshelfAssignment(itemKey, shelfId)
                    }
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
            }
            refreshAfterImport(root.id)
        } catch (error: Throwable) {
            rollBackImport()
            throw error
        }

        return ConnectionMediaImportResult(
            resourceUrls = importedNames.map { fileName ->
                LocalLibraryLocator.entryUrl(id, root.id, fileName)
            },
            importedFileNames = importedNames,
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        resolveResource(manga.url)?.let { resource ->
            if (indexedLibraryItem(resource)?.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                applyIndividualMetadata(manga, resource, preferences.getMetadataOverrides())
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
                )
            }
        }
        manga.initialized = true
        manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
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
        val existingChaptersByUrl = mangaRepository.getMangaByUrlAndSourceId(manga.url, id)
            ?.let { storedManga -> getChaptersByMangaId.await(storedManga.id) }
            .orEmpty()
            .associateBy(Chapter::url)
        if (indexedLibraryItem(resource)?.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
            val chapterUrl = LocalLibraryLocator.chapterUrl(id, resource.root.id, resource.relativePath)
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
                val chapterUrl = LocalLibraryLocator.chapterUrl(id, resource.root.id, relative)
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
            LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath),
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
        val overrides = preferences.getMetadataOverrides()
        return (
            overrides[LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath)]
                ?: overrides[LocalLibraryLocator.legacyItemKey(legacyRelativePath(resource))]
            )?.toLibraryMetadata()
    }

    override suspend fun updateMetadata(resourceUrl: String, metadata: LibraryMetadata): Result<Unit> {
        val resource = resolveResource(resourceUrl)
            ?: return Result.failure(IllegalArgumentException("Invalid local resource URL"))
        val itemKey = LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath)
        val value = metadata.toLocalMetadataOverride()
        return runCatching {
            val metadataDirectory = metadataDirectory(resource)
            val adjacentFileStem = resource.file.takeUnless(UniFile::isDirectory)?.nameWithoutExtension
            check(
                metadataStore.save(
                    itemKey = itemKey,
                    metadata = value,
                    itemDirectory = metadataDirectory,
                    contentType = resource.root.contentType,
                    adjacentFileStem = adjacentFileStem,
                ),
            ) {
                "Unable to save local metadata"
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
            if (indexedLibraryItem(resource)?.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                val embeddedMetadata = if (
                    !resource.file.isDirectory && resource.file.extension.equals("epub", ignoreCase = true)
                ) {
                    listOfNotNull(readEpubMetadata(resource.file))
                } else {
                    emptyList()
                }
                return@withIOContext generateLocalMetadataSuggestion(
                    folderName = if (resource.file.isDirectory) {
                        resource.file.name.orEmpty()
                    } else {
                        resource.file.nameWithoutExtension.orEmpty()
                    },
                    itemNames = listOf(resource.file.name.orEmpty()),
                    embeddedMetadata = embeddedMetadata,
                    filenameTemplate = filenameTemplate,
                )
            }
            val items = resource.file.listFiles().orEmpty()
                .filterNot { it.name.orEmpty().startsWith('.') }
                .filter(::isSupportedChapter)
            generateLocalMetadataSuggestion(
                folderName = resource.file.name.orEmpty(),
                itemNames = items.map { file -> file.name.orEmpty() },
                embeddedMetadata = readEmbeddedMetadata(resource.file, items),
                filenameTemplate = filenameTemplate,
            )
        }
    }

    override fun localChapterFile(chapterUrl: String): UniFile? {
        val incoming = IncomingMediaSessionLocator.chapterFile(context, chapterUrl, id)
            ?.let { file -> UniFile.fromUri(context, Uri.fromFile(file)) }
        return incoming ?: resolveResource(chapterUrl)?.file
    }

    override suspend fun loadSuggestedSeriesCover(mangaUrl: String): ByteArray? = withIOContext {
        val incomingDirectory = IncomingMediaSessionLocator.location(mangaUrl, id)
            ?.takeIf { it.fileName == null }
            ?.let { IncomingMediaSessionLocator.sessionDirectory(context, it.sessionId) }
            ?.let { UniFile.fromUri(context, Uri.fromFile(it)) }
        resolveResource(mangaUrl)?.let { resource ->
            if (indexedLibraryItem(resource)?.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                return@withIOContext runCatching { firstImageBytes(resource.file) }
                    .onFailure { error ->
                        logcat(LogPriority.WARN, error) { "Unable to read suggested local item cover" }
                    }
                    .getOrNull()
            }
        }
        val directory = incomingDirectory ?: resolveResource(mangaUrl)?.file?.takeIf(UniFile::isDirectory)
            ?: return@withIOContext null
        val firstChapter = findFirstChapter(
            directory.listFiles().orEmpty().filterNot { it.name.orEmpty().startsWith('.') },
        ) ?: return@withIOContext null

        try {
            firstImageBytes(firstChapter)
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Unable to read suggested local series cover" }
            null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    internal fun isIndividualFileEntry(mangaUrl: String): Boolean {
        val location = LocalLibraryLocator.location(mangaUrl, id) ?: return false
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
        return item?.kind == LocalLibraryItem.Kind.FILE_ENTRY
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
                    file.archiveReader(context).use { reader ->
                        reader.useEntries { entries ->
                            entries.count { entry ->
                                entry.isFile && ImageUtil.isImage(entry.name) {
                                    reader.getInputStream(entry.name)!!
                                }
                            }
                        }
                    }
                }
                file.extension.equals("pdf", ignoreCase = true) -> {
                    context.contentResolver.openFileDescriptor(file.uri, "r")?.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
                    } ?: 0
                }
                else -> 0
            }
        }.getOrNull()?.takeIf { it > 0 }
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
        fingerprint = preferences.getIndex().itemsByKey[LocalLibraryLocator.itemKey(rootId, relativePath)]?.fingerprint,
    )

    private suspend fun refreshAfterImport(destinationRootId: String) {
        val error = refreshLibrary().exceptionOrNull() ?: return
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
            val progress = if (item.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                LocalReadProgressIndex(indexedChapterCount = 1, isIndividualFile = true)
            } else {
                LocalReadProgressIndex(
                    indexedChapterCount = chapterCounts[itemKey]?.size ?: 0,
                    isIndividualFile = false,
                )
            }
            mangaUrl.trimEnd('/') to progress
        }.toMap()
    }

    private fun indexedLibraryItem(resource: ResolvedLocalResource): LocalLibraryItem? {
        val itemKey = LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath)
        return preferences.getIndex().itemsByKey[itemKey]?.takeIf {
            it.kind in setOf(LocalLibraryItem.Kind.SERIES, LocalLibraryItem.Kind.FILE_ENTRY)
        }
    }

    private fun LocalLibraryItem.organizationMode(): LocalLibraryOrganizationMode = when (kind) {
        LocalLibraryItem.Kind.FILE_ENTRY -> LocalLibraryOrganizationMode.INDIVIDUAL_FILES
        LocalLibraryItem.Kind.SERIES, LocalLibraryItem.Kind.CHAPTER -> LocalLibraryOrganizationMode.SERIES
    }

    private fun applyIndividualMetadata(
        manga: SManga,
        resource: ResolvedLocalResource,
        metadataOverrides: Map<String, LocalMetadataOverride>,
        scannedFiles: List<UniFile>? = null,
    ) {
        val file = resource.file
        manga.title = if (file.isDirectory) file.name.orEmpty() else file.nameWithoutExtension.orEmpty()
        manga.thumbnail_url = if (file.isDirectory) {
            findCover(scannedFiles ?: file.listFiles().orEmpty().toList())?.uri?.toString()
                ?: LocalLibraryLocator.chapterUrl(id, resource.root.id, resource.relativePath)
        } else {
            LocalLibraryLocator.chapterUrl(id, resource.root.id, resource.relativePath)
        }
        applyComicInfoMetadata(manga, file)
        if (!file.isDirectory && file.extension.equals("epub", ignoreCase = true)) {
            readEpubMetadata(file)?.let { metadata ->
                metadata.title?.let { manga.title = it }
                metadata.authors.takeIf(List<String>::isNotEmpty)?.let { manga.author = it.joinToString(", ") }
                metadata.contributors.takeIf(List<String>::isNotEmpty)?.let { manga.artist = it.joinToString(", ") }
                metadata.description?.let { manga.description = it }
                metadata.subjects.takeIf(List<String>::isNotEmpty)?.let { manga.genre = it.joinToString(", ") }
            }
        } else if (!file.isDirectory && isMobiFile(file)) {
            readMobiMetadata(file)?.let { metadata ->
                metadata.title?.let { manga.title = it }
                metadata.authors.takeIf(List<String>::isNotEmpty)?.let { manga.author = it.joinToString(", ") }
            }
        }
        if (!file.isDirectory) {
            val directory = if (scannedFiles == null) metadataDirectory(resource) else null
            val stem = file.nameWithoutExtension.orEmpty()
            fun scannedSidecar(suffix: String, fallback: String): UniFile? = scannedFiles?.firstOrNull {
                it.name.equals("$stem.$suffix", ignoreCase = true)
            } ?: scannedFiles?.firstOrNull { it.name.equals(fallback, ignoreCase = true) }
            if (resource.root.contentType == LocalLibraryContentType.BOOKS) {
                val sidecar =
                    scannedSidecar("metadata.opf", "metadata.opf") ?: directory?.findFile("$stem.metadata.opf")
                        ?: directory?.takeIf { hasSingleSupportedMedia(it, resource.root.contentType) }
                            ?.findFile("metadata.opf")
                sidecar?.let(::readOpfFile)?.let { metadata ->
                    metadata.title?.let { manga.title = it }
                    metadata.authors.takeIf(List<String>::isNotEmpty)?.let { manga.author = it.joinToString(", ") }
                    metadata.contributors.takeIf(List<String>::isNotEmpty)?.let {
                        manga.artist = it.joinToString(", ")
                    }
                    metadata.description?.let { manga.description = it }
                    metadata.subjects.takeIf(List<String>::isNotEmpty)?.let { manga.genre = it.joinToString(", ") }
                }
            } else {
                val sidecar =
                    scannedSidecar("ComicInfo.xml", COMIC_INFO_FILE) ?: directory?.findFile("$stem.ComicInfo.xml")
                        ?: directory?.takeIf { hasSingleSupportedMedia(it, resource.root.contentType) }
                            ?.findFile(COMIC_INFO_FILE)
                sidecar?.let { sidecar ->
                    applyComicInfoFile(manga, sidecar)
                }
            }
        }

        val legacyRelativePath = listOf(resource.root.relativePath, resource.relativePath)
            .filter(String::isNotBlank)
            .joinToString("/")
        val override = metadataOverrides[LocalLibraryLocator.itemKey(resource.root.id, resource.relativePath)]
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
        if (file.isDirectory) {
            file.findFile(COMIC_INFO_FILE)?.let { applyComicInfoFile(manga, it) }
            return
        }
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

    private fun applyComicInfoFile(manga: SManga, file: UniFile) {
        runCatching {
            AndroidXmlReader(file.openInputStream(), StandardCharsets.UTF_8.name()).use { reader ->
                manga.copyFromComicInfo(xml.decodeFromReader<ComicInfo>(reader))
            }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Unable to read ComicInfo.xml sidecar" }
        }
    }

    private fun hasSingleSupportedMedia(
        directory: UniFile,
        contentType: LocalLibraryContentType,
    ): Boolean {
        return directory.listFiles().orEmpty().count {
            !it.isDirectory && it.extension.orEmpty().lowercase() in supportedExtensions(contentType)
        } == 1
    }

    private fun applySeriesMetadata(
        manga: SManga,
        root: LocalLibraryRootConfig,
        directory: UniFile,
        relativePath: String,
        files: List<UniFile>,
        metadataOverrides: Map<String, LocalMetadataOverride>,
    ) {
        manga.thumbnail_url = findCover(files)?.uri?.toString()
            ?: findFirstChapter(files)?.let { chapter ->
                val chapterPath = listOf(relativePath, chapter.name.orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString("/")
                LocalLibraryLocator.chapterUrl(id, root.id, chapterPath)
            }
        directory.findFile(COMIC_INFO_FILE)?.let { file ->
            runCatching {
                AndroidXmlReader(file.openInputStream(), StandardCharsets.UTF_8.name()).use { reader ->
                    manga.copyFromComicInfo(xml.decodeFromReader<ComicInfo>(reader))
                }
            }
        }
        if (root.contentType != LocalLibraryContentType.COMICS) {
            readBookMetadata(directory, files)?.let { metadata ->
                metadata.title?.let { manga.title = it }
                metadata.authors.takeIf(List<String>::isNotEmpty)?.let { manga.author = it.joinToString(", ") }
                metadata.contributors.takeIf(List<String>::isNotEmpty)?.let { manga.artist = it.joinToString(", ") }
                metadata.description?.let { manga.description = it }
                metadata.subjects.takeIf(List<String>::isNotEmpty)?.let { manga.genre = it.joinToString(", ") }
            }
        }

        val legacyRelativePath = listOf(root.relativePath, relativePath)
            .filter(String::isNotBlank)
            .joinToString("/")
        val override = metadataOverrides[LocalLibraryLocator.itemKey(root.id, relativePath)]
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

    private fun readBookMetadata(directory: UniFile, files: List<UniFile>): LocalEmbeddedMetadata? {
        directory.findFile("metadata.opf")?.let { file ->
            readOpfFile(file)?.let { return it.forSeriesDisplay(isDirectoryMetadata = true) }
        }

        val epubFile = files
            .firstOrNull { !it.isDirectory && it.extension.equals("epub", ignoreCase = true) }
        if (epubFile != null) {
            return readEpubMetadata(epubFile)?.forSeriesDisplay(isDirectoryMetadata = false)
        }

        val mobiFile = files.firstOrNull { !it.isDirectory && isMobiFile(it) } ?: return null
        return readMobiMetadata(mobiFile)?.forSeriesDisplay(isDirectoryMetadata = false)
    }

    private fun readEmbeddedMetadata(
        directory: UniFile,
        items: List<UniFile>,
    ): List<LocalEmbeddedMetadata> = buildList {
        directory.findFile("metadata.opf")?.let(::readOpfFile)?.let(::add)
        items.filter { !it.isDirectory && it.extension.equals("epub", ignoreCase = true) }
            .mapNotNullTo(this, ::readEpubMetadata)
        items.filter { !it.isDirectory && isMobiFile(it) }
            .mapNotNullTo(this, ::readMobiMetadata)
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

    private fun firstImageBytes(file: UniFile): ByteArray? {
        if (file.isDirectory) {
            return file.listFiles().orEmpty()
                .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .minWithOrNull { first, second ->
                    first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
                }
                ?.openInputStream()
                ?.use { it.readBytes() }
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
            else -> file.archiveReader(context).use { reader ->
                val entry = reader.useEntries { entries ->
                    entries
                        .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .minWithOrNull { first, second ->
                            first.name.compareToCaseInsensitiveNaturalOrder(second.name)
                        }
                }
                entry?.let { reader.getInputStream(it.name)?.use { input -> input.readBytes() } }
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

    private fun candidateResources(root: ResolvedLocalLibraryRoot): List<ScanCandidate> {
        val reader = LocalScanDirectoryReader(context)
        return when (preferences.getConfig().organizationMode(root.config)) {
            LocalLibraryOrganizationMode.SERIES -> candidateSeriesDirectories(root, reader)
            LocalLibraryOrganizationMode.INDIVIDUAL_FILES -> candidateIndividualEntries(root, reader)
        }
    }

    private fun scanImage(entry: LocalScanFile): Boolean = !entry.directory &&
        (
            LocalMediaFormats.isImage(entry.extension) ||
                (
                    entry.extension !in SUPPORTED_FILE_EXTENSIONS &&
                        try {
                            ImageUtil.isImage(entry.name) { entry.file.openInputStream() }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                    )
            )

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
                    if (supportedMedia.size == 1) {
                        add("metadata.opf")
                        add(COMIC_INFO_FILE)
                    }
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
            val base = preferences.resolveRoot(context, root) ?: return@forEach
            if (relative == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY) {
                return ResolvedLocalResource(root, base, relative)
            }
            val file = LocalLibraryLocator.normalize(relative)
                .split('/')
                .filter(String::isNotBlank)
                .fold(base) { parent, segment -> parent.findFile(segment) ?: return@forEach }
            return ResolvedLocalResource(root, file, LocalLibraryLocator.normalize(relative))
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

    private suspend fun scanLibrary(): ConnectionLibraryRefreshResult = withIOContext {
        val defaultChapterFlags = Injekt.get<SharedAppPreferences>().libraryPreferences().defaultChapterFlags()
        val previousIndex = preferences.getIndex()
        val existingByUrl = mangaRepository.getMangaBySourceId(id).associateBy(Manga::url)
        val metadataOverrides = preferences.getMetadataOverrides()
        val config = preferences.getConfig()
        val configuredRootIds = config.roots.mapTo(mutableSetOf(), LocalLibraryRootConfig::id)
        val successfulRootIds = mutableSetOf<String>()
        val failedRoots = mutableListOf<LocalLibraryRootConfig>()
        val candidates = buildList {
            config.roots.forEach { root ->
                try {
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
        val previousItems = previousIndex.items.ifEmpty {
            recoverLocalLibraryItems(
                sourceId = id,
                config = config,
                mangaUrls = existingByUrl.keys,
            )
        }
        val effectivePreviousIndex = previousIndex.copy(items = previousItems)
        val refreshedAt = System.currentTimeMillis()
        val refreshedIndex = buildIndex(
            candidates = candidates,
            previousIndex = effectivePreviousIndex,
            scannedAt = refreshedAt.takeIf { successfulRootIds.isNotEmpty() } ?: previousIndex.scannedAt,
            configuredRootIds = configuredRootIds,
            successfulRootIds = successfulRootIds,
        )
        val previousEntriesByKey = effectivePreviousIndex.items
            .filter { it.kind in setOf(LocalLibraryItem.Kind.SERIES, LocalLibraryItem.Kind.FILE_ENTRY) }
            .associateBy { it.itemKey }

        val changedManga = candidates.chunked(SCAN_METADATA_BATCH_SIZE).flatMap { batch ->
            coroutineScope {
                batch.map { candidate ->
                    async {
                        try {
                            val url = LocalLibraryLocator.entryUrl(id, candidate.root.id, candidate.relativePath)
                            val existing = existingByUrl[url]
                            val itemKey = LocalLibraryLocator.itemKey(candidate.root.id, candidate.relativePath)
                            val unchanged = existing?.initialized == true &&
                                previousEntriesByKey[itemKey]?.fingerprint == candidate.fingerprint
                            if (unchanged) return@async null

                            SManga.create().apply {
                                title = if (candidate.resource.isDirectory) {
                                    candidate.resource.name.orEmpty()
                                } else {
                                    candidate.resource.nameWithoutExtension.orEmpty()
                                }
                                this.url = url
                                if (candidate.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                                    applyIndividualMetadata(
                                        manga = this,
                                        resource = ResolvedLocalResource(
                                            root = candidate.root,
                                            file = candidate.resource,
                                            relativePath = candidate.relativePath,
                                        ),
                                        metadataOverrides = metadataOverrides,
                                        scannedFiles = candidate.files,
                                    )
                                } else {
                                    applySeriesMetadata(
                                        manga = this,
                                        root = candidate.root,
                                        directory = candidate.resource,
                                        relativePath = candidate.relativePath,
                                        files = candidate.files,
                                        metadataOverrides = metadataOverrides,
                                    )
                                }
                                initialized = true
                            }.toDomainManga(id).let { manga ->
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
                it.kind in setOf(LocalLibraryItem.Kind.SERIES, LocalLibraryItem.Kind.FILE_ENTRY)
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
            if (candidate.kind == LocalLibraryItem.Kind.FILE_ENTRY) {
                return@flatMap listOf(
                    LocalLibraryItem(
                        itemKey = LocalLibraryLocator.itemKey(candidate.root.id, candidate.relativePath),
                        rootId = candidate.root.id,
                        relativePath = candidate.relativePath,
                        contentType = candidate.contentType,
                        kind = LocalLibraryItem.Kind.FILE_ENTRY,
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
                    )
                }
            listOf(seriesItem) + chapterItems
        }
        val items = mergeLocalLibraryScanItems(
            scannedItems = scannedItems,
            previousItems = previousIndex.items,
            configuredRootIds = configuredRootIds,
            successfulRootIds = successfulRootIds,
        )

        val previousChapterSignatures = previousIndex.chapterSignatures()
        val currentIndex = LocalLibraryIndex(
            schemaVersion = 5,
            scannedAt = scannedAt,
            items = items,
        )
        val currentChapterSignatures = currentIndex.chapterSignatures()
        val currentSeriesKeys = items
            .filter { it.kind == LocalLibraryItem.Kind.SERIES }
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
        return items.asSequence()
            .filter { it.kind == LocalLibraryItem.Kind.CHAPTER }
            .groupBy(
                keySelector = { item ->
                    LocalLibraryLocator.itemKey(
                        item.rootId,
                        item.relativePath.substringBeforeLast('/', missingDelimiterValue = ""),
                    )
                },
                valueTransform = { item ->
                    listOf(
                        item.relativePath,
                        item.format,
                        item.sizeBytes.toString(),
                        item.modifiedAt.toString(),
                        item.fingerprint.orEmpty(),
                    ).joinToString("\u0000")
                },
            )
            .mapValues { (_, values) -> values.sorted() }
    }

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
    ) {
        val fingerprint = fingerprint(files.map { attributes.getValue(it.uri) })
    }

    private data class ResolvedLocalResource(
        val root: LocalLibraryRootConfig,
        val file: UniFile,
        val relativePath: String,
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
