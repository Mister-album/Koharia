package koharia.connection

import androidx.compose.runtime.Composable
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File

interface ConnectionBrowseAdapter {
    fun availableContentScopes(): Set<LibraryContentScope> = setOf(LibraryContentScope.ALL)

    fun contentScopesChanges(): Flow<Set<LibraryContentScope>> = flowOf(availableContentScopes())

    fun createBrowseScreen(
        scope: LibraryContentScope,
        listingQuery: String? = null,
        showNavigationUp: Boolean = true,
    ): ConnectionBrowseScreen
}

interface ConnectionPageAdapter {
    val pageLoadConcurrency: Int

    suspend fun getConnectionPageList(chapter: SChapter, forceNetwork: Boolean): List<Page>

    fun decoratePageImageUrls(pages: List<Page>, chapterMemo: JsonObject): List<Page> = pages
}

/** Provides a source-owned local file for the reader without routing it through downloads. */
interface ConnectionLocalFileAdapter {
    fun localChapterFile(chapterUrl: String): UniFile?
}

/** Supplies a provider-selected image that can be saved as the series custom cover. */
interface ConnectionSeriesCoverAdapter {
    suspend fun loadSuggestedSeriesCover(mangaUrl: String): ByteArray?
}

interface ConnectionBrowseScreen {
    val sourceId: Long

    val refreshOnReselect: Boolean
        get() = true

    @Composable
    fun Content()

    suspend fun search(query: String)

    suspend fun searchGenre(name: String)

    suspend fun refresh()
}

data class ConnectionLibraryRefreshResult(
    val itemCount: Int,
    val refreshedAt: Long,
) {
    init {
        require(itemCount >= 0) { "Refreshed item count cannot be negative" }
        require(refreshedAt >= 0L) { "Refresh timestamp cannot be negative" }
    }
}

interface ConnectionLibraryRefreshAdapter {
    val libraryRefreshes: Flow<ConnectionLibraryRefreshResult>
        get() = flowOf()

    suspend fun refreshLibrary(): Result<ConnectionLibraryRefreshResult>
}

interface ConnectionLibraryMembershipAdapter {
    suspend fun filterLibraryEntries(mangas: List<Manga>): List<Manga>
}

data class ConnectionLibraryShelf(
    val id: String,
    val name: String,
    val contentScope: LibraryContentScope,
    val isDefault: Boolean = false,
)

interface ConnectionLibraryShelfAdapter {
    val libraryShelves: Flow<List<ConnectionLibraryShelf>>

    suspend fun currentLibraryShelfId(mangaUrl: String): String?

    suspend fun compatibleLibraryShelves(mangaUrl: String): List<ConnectionLibraryShelf> = libraryShelves.first()

    suspend fun moveMangaToLibraryShelf(mangaUrl: String, shelfId: String): Result<Unit>
}

enum class ConnectionMediaType {
    COMIC,
    BOOK,
    MIXED,
}

enum class ConnectionMediaGrouping {
    SERIES,
    INDIVIDUAL,
}

data class ConnectionMediaImportItem(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val extension: String,
)

data class ConnectionMediaImportDestination(
    val id: String,
    val name: String,
    val mediaType: ConnectionMediaType,
    val supportedExtensions: Set<String>,
    val defaultShelfId: String? = null,
    val grouping: ConnectionMediaGrouping = ConnectionMediaGrouping.SERIES,
    val compatibleShelfIds: Set<String> = emptySet(),
)

data class ConnectionMediaImportRequest(
    val destinationId: String,
    val shelfId: String?,
    val seriesName: String,
    val items: List<ConnectionMediaImportItem>,
    val existingSeriesId: String? = null,
)

data class ConnectionMediaImportSeries(
    val id: String,
    val name: String,
    val destinationId: String,
    val shelfId: String? = null,
)

data class ConnectionMediaImportResult(
    val resourceUrls: List<String>,
    val importedFileNames: List<String>,
) {
    val primaryResourceUrl: String?
        get() = resourceUrls.singleOrNull()
}

interface ConnectionMediaImportAdapter {
    suspend fun mediaImportDestinations(): List<ConnectionMediaImportDestination>

    suspend fun mediaImportSeries(destinationId: String): List<ConnectionMediaImportSeries> = emptyList()

    suspend fun importMedia(request: ConnectionMediaImportRequest): Result<ConnectionMediaImportResult>
}

interface ConnectionAccountAdapter {
    fun hasValidConnection(): Boolean

    suspend fun getAccount(): ConnectionAccount?
}

interface ConnectionMangaBehaviorAdapter {
    val mangaBehavior: ConnectionMangaBehavior

    fun chapterThumbnailUrl(chapterUrl: String): String? = null

    fun shouldRefreshMangaDetails(
        manga: Manga,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val interval = mangaBehavior.detailsRefreshIntervalMillis ?: return false
        return manga.lastUpdate <= 0L || nowMillis - manga.lastUpdate >= interval
    }

    fun shouldRefreshChapters(
        manga: Manga,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = shouldRefreshMangaDetails(manga, nowMillis)
}

/** Loads a provider-owned thumbnail for a chapter cover grid. */
interface ConnectionChapterThumbnailAdapter {
    suspend fun loadChapterThumbnail(chapterUrl: String): ByteArray?
}

data class LibraryMetadata(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: Int? = null,
    val lockedFields: Set<String> = emptySet(),
    val source: String = "unknown",
)

interface ConnectionMetadataAdapter {
    suspend fun readMetadata(resourceUrl: String): LibraryMetadata?

    suspend fun updateMetadata(resourceUrl: String, metadata: LibraryMetadata): Result<Unit>
}

enum class MetadataFilenameTemplate {
    AUTO,
    SERIES_VOLUME_TITLE,
    SERIES_CHAPTER_TITLE,
    SERIES_TITLE,
    FOLDER_ITEM_TITLE,
}

enum class LibraryMetadataField {
    TITLE,
    AUTHOR,
    ARTIST,
    DESCRIPTION,
    GENRES,
    STATUS,
}

enum class MetadataSuggestionSource {
    FOLDER,
    EPUB_EMBEDDED,
    ITEM_FILENAME,
}

data class LibraryMetadataSuggestion(
    val metadata: LibraryMetadata,
    val fieldSources: Map<LibraryMetadataField, MetadataSuggestionSource>,
    val matchedFilenameCount: Int,
    val totalItemCount: Int,
)

interface ConnectionMetadataGenerationAdapter {
    suspend fun generateMetadataSuggestion(
        resourceUrl: String,
        filenameTemplate: MetadataFilenameTemplate,
    ): Result<LibraryMetadataSuggestion>
}

data class ConnectionMangaBehavior(
    val usesCacheTerminology: Boolean = false,
    val supportsChapterCoverGrid: Boolean = false,
    val allowsChapterDownloads: Boolean = true,
    val providerManagedLibrary: Boolean = false,
    val allowsLocalLibraryManagement: Boolean = true,
    val allowsCategoryManagement: Boolean = allowsLocalLibraryManagement,
    val allowsFetchIntervalManagement: Boolean = true,
    val showSourceName: Boolean = true,
    val detailsRefreshIntervalMillis: Long? = null,
) {
    init {
        require(detailsRefreshIntervalMillis == null || detailsRefreshIntervalMillis > 0L) {
            "Details refresh interval must be positive"
        }
    }

    fun isLibraryEntry(manga: Manga): Boolean = manga.favorite || providerManagedLibrary

    companion object {
        val Default = ConnectionMangaBehavior()
    }
}

data class ConnectionAccount(
    val displayName: String,
    val roles: List<String> = emptyList(),
)

interface ConnectionHealthAdapter {
    val allowsUnvalidatedNetwork: Boolean

    suspend fun isConnectionReachable(): Boolean
}

interface ConnectionRawDownloadAdapter {
    val rawDownloadClient: OkHttpClient

    fun rawFileRequest(resourceUrl: String, rangeStart: Long? = null): Request
}

interface ConnectionDownloadStorageAdapter {
    val usesSharedDownloadStorage: Boolean

    fun findSharedChapterFile(chapterUrl: String, mangaTitle: String): UniFile? = null

    suspend fun indexDownloadedChapter(chapter: Chapter, localFile: UniFile) = Unit

    suspend fun deleteIndexedFile(file: UniFile) = Unit

    suspend fun deleteIndexedPathPrefix(relativePath: String) = Unit

    suspend fun deleteIndexedManga(mangaId: Long) = Unit

    suspend fun updateIndexedFilePath(oldRelativePath: String, newFile: UniFile) = Unit

    suspend fun updateIndexedPathPrefix(oldRelativePath: String, newRelativePath: String) = Unit

    fun indexedRelativePath(file: UniFile): String? = null

    fun downloadDirectoryName(): String

    fun downloadDirectoryNames(): List<String>

    fun ownedDownloadDirectoryNames(): Set<String>

    fun legacyDownloadDirectoryNames(): List<String>
}

interface ConnectionPublicationAdapter {
    suspend fun openRemotePublication(
        request: koharia.epub.model.EpubOpenRequest,
        initialLocator: Locator?,
    ): koharia.epub.session.EpubReaderSession

    fun findCompletePagePublication(
        chapter: eu.kanade.tachiyomi.data.database.models.Chapter,
    ): ConnectionPagePublication? = null

    fun hasCompleteCachedPublication(chapter: Chapter): Boolean = false

    fun canOpenAsPages(chapterMemo: JsonObject): Boolean = false

    fun pageCountFromMemo(chapterMemo: JsonObject): Int? = null

    fun hasMigratedPageProgress(chapterMemo: JsonObject): Boolean = true

    fun markPageProgressMigrated(chapterMemo: JsonObject): JsonObject = chapterMemo

    suspend fun resolvePublication(
        chapter: tachiyomi.domain.chapter.model.Chapter,
        allowRemoteLookup: Boolean,
    ): ConnectionPublicationMetadata
}

data class ConnectionPagePublication(
    val file: File,
    val pageCount: Int,
)

data class ConnectionPublicationMetadata(
    val remoteResourceId: String?,
    val publicationKey: String,
    val isPageCompatible: Boolean,
    val fileName: String?,
    val sizeBytes: Long?,
    val metadataError: Throwable? = null,
)

interface ConnectionViewerSettingsAdapter {
    suspend fun updateViewerFlags(resourceUrl: String, viewerFlags: Long)

    suspend fun getViewerFlags(resourceUrl: String): Long?
}

interface ConnectionMangaProgressAdapter {
    suspend fun syncMangaProgress(manga: Manga)
}

interface ConnectionHistorySyncAdapter {
    suspend fun syncConnectionHistory()
}

interface ConnectionPageProgressAdapter {
    suspend fun pullPageProgress(
        chapterUrl: String,
        chapterMemo: JsonObject,
    ): ConnectionPageProgressSnapshot?

    suspend fun pushPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    )
}

data class ConnectionPageProgressSnapshot(
    val resourceId: String,
    val pageIndex: Int?,
    val totalPages: Int,
    val completed: Boolean,
    val readDate: String?,
    val isEpub: Boolean,
    val canOpenAsPages: Boolean,
    val updatedChapterMemo: JsonObject,
    val previousPublicationVersion: String?,
    val publicationVersion: String?,
)

interface ConnectionEpubProgressAdapter {
    suspend fun getCachedEpubProgress(chapterId: Long): koharia.domain.epub.model.EpubRemoteProgressCache?

    suspend fun refreshEpubProgress(
        mangaId: Long,
        chapter: Chapter,
    ): koharia.domain.epub.model.EpubRemoteProgressCache?

    suspend fun syncMangaEpubProgress(
        mangaId: Long,
        chapters: List<Chapter>,
        force: Boolean,
    ): List<koharia.domain.epub.model.EpubRemoteProgressCache> {
        return chapters.mapNotNull { chapter -> refreshEpubProgress(mangaId, chapter) }
    }

    suspend fun pullEpubProgress(resourceId: String): RemoteEpubProgression?

    suspend fun pushEpubProgress(
        resourceId: String,
        locator: Locator,
        positions: List<Locator>,
        modifiedAt: java.util.Date,
    )
}

data class RemoteEpubProgression(
    val locator: Locator,
    val modifiedAt: java.util.Date,
)
