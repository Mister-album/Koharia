package koharia.connection

import androidx.compose.runtime.Composable
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.flow.Flow
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

interface ConnectionBrowseScreen {
    val sourceId: Long

    @Composable
    fun Content()

    suspend fun search(query: String)

    suspend fun searchGenre(name: String)

    suspend fun refresh()
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
}

data class ConnectionMangaBehavior(
    val usesCacheTerminology: Boolean = false,
    val supportsChapterCoverGrid: Boolean = false,
    val allowsLocalLibraryManagement: Boolean = true,
    val allowsFetchIntervalManagement: Boolean = true,
    val showSourceName: Boolean = true,
    val detailsRefreshIntervalMillis: Long? = null,
) {
    init {
        require(detailsRefreshIntervalMillis == null || detailsRefreshIntervalMillis > 0L) {
            "Details refresh interval must be positive"
        }
    }

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
