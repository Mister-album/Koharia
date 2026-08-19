package koharia.source.komga

import android.content.SharedPreferences
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionAccountAdapter
import koharia.connection.ConnectionBrowseAdapter
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionEpubHistorySyncAdapter
import koharia.connection.ConnectionEpubProgressAdapter
import koharia.connection.ConnectionHealthAdapter
import koharia.connection.ConnectionHistorySyncAdapter
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.connection.ConnectionMangaProgressAdapter
import koharia.connection.ConnectionPageAdapter
import koharia.connection.ConnectionPageList
import koharia.connection.ConnectionPageProgressAdapter
import koharia.connection.ConnectionPagePublication
import koharia.connection.ConnectionPublicationAdapter
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.ConnectionSource
import koharia.connection.ConnectionViewerSettingsAdapter
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.komga.api.KomgaApiClient
import koharia.komga.api.KomgaSearchCapabilities
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.isDivinaCompatibleEpub
import koharia.komga.api.dto.isEpub
import koharia.komga.api.dto.offlineFilterMetadata
import koharia.komga.domain.repository.KomgaRepository
import koharia.komga.download.KomgaChapterMemo
import koharia.komga.ui.library.KomgaLibraryScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class KomgaSource(
    override val id: Long = ID,
    private val customName: String = SOURCE_NAME,
    override val connectionProfile: LibraryConnectionProfile = LibraryConnectionProfile(
        id = id,
        providerId = KomgaConnectionProvider.ID,
        name = customName,
    ),
) :
    HttpSource(),
    ConfigurableSource,
    UnmeteredSource,
    ConnectionSource,
    ConnectionBrowseAdapter,
    ConnectionPageAdapter,
    ConnectionAccountAdapter,
    ConnectionMangaBehaviorAdapter,
    ConnectionHealthAdapter,
    ConnectionRawDownloadAdapter,
    ConnectionDownloadStorageAdapter,
    ConnectionPublicationAdapter,
    ConnectionViewerSettingsAdapter,
    ConnectionMangaProgressAdapter,
    ConnectionReadStatusAdapter,
    ConnectionHistorySyncAdapter,
    ConnectionEpubHistorySyncAdapter,
    ConnectionPageProgressAdapter,
    ConnectionEpubProgressAdapter {

    private val preferences: SharedPreferences by lazy { sourcePreferences() }
    private val json: Json by injectLazy()
    private val application: android.app.Application by lazy { Injekt.get() }

    override val name: String = customName
    override val lang: String = SOURCE_LANG
    override val supportsLatest: Boolean = true
    override val versionId: Int = SOURCE_VERSION

    // PDF pages are rasterized by Komga on demand. Two requests avoid saturating the server's
    // PDF renderer while still keeping a visible double-page spread responsive.
    override val pageLoadConcurrency: Int = 2
    override val mangaBehavior = MANGA_BEHAVIOR
    override val allowsUnvalidatedNetwork: Boolean = true
    override val rawDownloadClient: OkHttpClient
        get() = client
    override val usesSharedDownloadStorage: Boolean
        get() = Injekt.get<KomgaServerPreferences>().downloadDirectoryMode.get() == DownloadDirectoryMode.Shared

    override fun availableContentScopes(): Set<LibraryContentScope> {
        return if (Injekt.get<KomgaLibraryClassificationManager>().enabled.get()) {
            setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK)
        } else {
            setOf(LibraryContentScope.ALL)
        }
    }

    override fun contentScopesChanges(): kotlinx.coroutines.flow.Flow<Set<LibraryContentScope>> {
        return Injekt.get<KomgaLibraryClassificationManager>().enabled.changes().map { enabled ->
            if (enabled) {
                setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK)
            } else {
                setOf(LibraryContentScope.ALL)
            }
        }
    }

    override fun shouldRefreshMangaDetails(
        manga: tachiyomi.domain.manga.model.Manga,
        nowMillis: Long,
    ): Boolean {
        return manga.memo.offlineFilterMetadata() == null ||
            super<ConnectionMangaBehaviorAdapter>.shouldRefreshMangaDetails(manga, nowMillis)
    }

    override fun createBrowseScreen(
        scope: LibraryContentScope,
        listingQuery: String?,
        showNavigationUp: Boolean,
    ) = KomgaLibraryScreen(
        sourceId = id,
        listingQuery = listingQuery,
        showNavigationUp = showNavigationUp,
        libraryScope = scope,
    )

    override fun chapterThumbnailUrl(chapterUrl: String): String = "$chapterUrl/thumbnail"

    override val baseUrl: String
        get() = preferences.getString(PREF_ADDRESS, "")!!.trim().trimEnd('/')

    private val username: String
        get() = preferences.getString(PREF_USERNAME, "")!!

    private val password: String
        get() = preferences.getString(PREF_PASSWORD, "")!!

    private val authMode: String
        get() = preferences.getString(PREF_AUTH_MODE, null) ?: defaultAuthMode()

    private val apiKey: String
        get() = preferences.getString(PREF_API_KEY, null)
            ?: preferences.getString(PREF_API_KEY_WRONG_CASE, "")!!

    private val shelfLibraryIds: Set<String>
        get() = preferences.getStringSet(PREF_DEFAULT_LIBRARIES, emptySet()) ?: emptySet()

    private val chapterNameTemplate: String
        get() = preferences.getString(PREF_CHAPTER_NAME_TEMPLATE, PREF_CHAPTER_NAME_TEMPLATE_DEFAULT)!!

    private val searchCapabilities = KomgaSearchCapabilities()
    private val apiClient: KomgaApiClient
        get() = KomgaApiClient(baseUrl, currentHeaders(), client, json, searchCapabilities)

    private val repository: KomgaRepository
        get() = KomgaRepository(baseUrl, apiClient)
    private val metadataCacheStore by lazy { KomgaMetadataCacheStore(application.applicationContext) }
    private val scopedBasePreferences by lazy {
        Injekt.get<KomgaScopedPreferenceStoreFactory>().basePreferences(id)
    }
    private val forceBrowseRequestsUntil = AtomicLong(0L)

    fun currentHeaders(): Headers = headersBuilder().build()

    fun currentReadiumHeaders(): Headers = currentHeaders().newBuilder()
        .also { builder ->
            builder.removeAll("X-API-Key")
            builder.removeAll("Authorization")
            when (authMode) {
                AUTH_MODE_API_KEY -> if (apiKey.isNotBlank()) {
                    builder.set("X-API-Key", apiKey)
                }
                AUTH_MODE_CREDENTIALS -> if (username.isNotBlank() && password.isNotBlank()) {
                    builder.set("Authorization", Credentials.basic(username, password))
                }
            }
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "KohariaKomga/${AppInfo.getVersionName()}")
        .also { builder ->
            if (apiKey.isNotBlank()) {
                builder.set("X-API-Key", apiKey)
            }
        }

    override val client = super.client.newBuilder()
        .addInterceptor(KomgaOfflineInterceptor(application) { scopedBasePreferences.downloadedOnly.get() })
        .addNetworkInterceptor(KomgaCacheControlInterceptor(application))
        .addInterceptor { chain ->
            val original = chain.request()
            val newBuilder = original.newBuilder()

            if (authMode == AUTH_MODE_API_KEY && apiKey.isNotBlank()) {
                newBuilder.addHeader("X-Komga-Api-Key", apiKey)
            } else if (authMode == AUTH_MODE_CREDENTIALS && username.isNotBlank() && password.isNotBlank()) {
                newBuilder.addHeader("Authorization", Credentials.basic(username, password))
            }
            chain.proceed(newBuilder.build())
        }
        .dns(Dns.SYSTEM)
        .build()

    override fun popularMangaRequest(page: Int): Request =
        repository.popularMangaRequest(page, shelfLibraryIds, consumeBrowseCachePolicy())

    override fun popularMangaParse(response: Response) = repository.parseMangasPage(response)

    override fun latestUpdatesRequest(page: Int): Request =
        repository.latestUpdatesRequest(page, shelfLibraryIds, consumeBrowseCachePolicy())

    override fun latestUpdatesParse(response: Response) = repository.parseMangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        repository.searchMangaRequest(page, query, filters, shelfLibraryIds, consumeBrowseCachePolicy())

    override fun searchMangaParse(response: Response) = repository.parseMangasPage(response)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
        repository.getSearchManga(page, query, filters, shelfLibraryIds, consumeBrowseCachePolicy())

    override fun getMangaUrl(manga: eu.kanade.tachiyomi.source.model.SManga): String = manga.url.replace("/api/v1", "")

    override fun mangaDetailsRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request =
        repository.mangaDetailsRequest(manga, KomgaCachePolicy.NetworkFirst)

    override fun mangaDetailsParse(response: Response) = repository.mangaDetailsParse(response)

    override fun chapterListRequest(manga: eu.kanade.tachiyomi.source.model.SManga): Request =
        repository.chapterListRequest(manga, KomgaCachePolicy.NetworkFirst)

    override fun chapterListParse(response: Response) = repository.chapterListParse(response, chapterNameTemplate)

    override fun pageListRequest(chapter: eu.kanade.tachiyomi.source.model.SChapter): Request =
        repository.pageListRequest(chapter, KomgaCachePolicy.Default)

    override fun pageListParse(response: Response): List<Page> = repository.pageListParse(response).pages

    suspend fun getPageList(
        chapter: eu.kanade.tachiyomi.source.model.SChapter,
        cachePolicy: KomgaCachePolicy,
    ): List<Page> {
        return client.newCall(repository.pageListRequest(chapter, cachePolicy))
            .awaitSuccess()
            .let(repository::pageListParse)
            .pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(
            KomgaChapterMemo.networkPageImageUrl(page.imageUrl!!),
            headersBuilder().add("Accept", "image/*,*/*;q=0.8").build(),
        )

    override fun rawFileRequest(resourceUrl: String, rangeStart: Long?): Request = apiClient.bookFileRequest(
        resourceUrl,
        rangeStart,
    )

    fun hasValidBaseUrl(): Boolean = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")

    override fun hasValidConnection(): Boolean = hasValidBaseUrl()

    override suspend fun getAccount(): koharia.connection.ConnectionAccount? {
        return getMe()?.let { user ->
            koharia.connection.ConnectionAccount(
                displayName = user.email,
                roles = user.roles,
            )
        }
    }

    override suspend fun isConnectionReachable(): Boolean {
        if (!hasValidBaseUrl()) return false
        return runCatching {
            client.newCall(GET("$baseUrl/api/v1/libraries?size=1", currentHeaders()))
                .execute()
                .use(Response::isSuccessful)
        }.getOrDefault(false)
    }

    override suspend fun getConnectionPageList(
        chapter: eu.kanade.tachiyomi.source.model.SChapter,
        forceNetwork: Boolean,
    ): ConnectionPageList {
        return client.newCall(
            repository.pageListRequest(
                chapter,
                if (forceNetwork) KomgaCachePolicy.NetworkFirst else KomgaCachePolicy.Default,
            ),
        )
            .awaitSuccess()
            .let(repository::pageListParse)
    }

    override fun decoratePageImageUrls(
        pages: List<Page>,
        chapterMemo: kotlinx.serialization.json.JsonObject,
    ): List<Page> {
        val pageImageCacheToken = KomgaChapterMemo.pageImageCacheToken(chapterMemo)
        val isPdf = KomgaChapterMemo.mediaProfile(chapterMemo).equals("PDF", ignoreCase = true) ||
            KomgaChapterMemo.fileName(chapterMemo)?.substringBefore('?')?.endsWith(".pdf", ignoreCase = true) == true
        return pages.map { page ->
            val imageUrl = page.imageUrl ?: return@map page
            val networkImageUrl = KomgaChapterMemo.networkPageImageUrl(imageUrl)
            val optimizedImageUrl = if (isPdf) {
                networkImageUrl.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.setQueryParameter("convert", "jpeg")
                    ?.build()
                    ?.toString()
                    ?: networkImageUrl
            } else {
                networkImageUrl
            }
            Page(
                index = page.index,
                url = page.url,
                imageUrl = KomgaChapterMemo.versionedPageImageUrl(optimizedImageUrl, pageImageCacheToken),
            )
        }
    }

    suspend fun getMe(): koharia.komga.api.dto.UserDto? {
        if (!hasValidBaseUrl()) return null
        return try {
            client.newCall(apiClient.meRequest())
                .awaitSuccess()
                .let { apiClient.parse<koharia.komga.api.dto.UserDto>(it) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getBookDetails(bookUrl: String): BookDto? {
        if (!hasValidBaseUrl()) return null
        return try {
            client.newCall(apiClient.detailsRequest(bookUrl, KomgaCachePolicy.NetworkFirst))
                .awaitSuccess()
                .let { apiClient.parse<BookDto>(it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun updateMangaViewerFlags(mangaId: String, viewerFlags: Long) {
        if (!hasValidBaseUrl()) return
        try {
            apiClient.updateClientSettings(
                mapOf(
                    "koharia.manga.$mangaId.viewerFlags" to
                        koharia.komga.api.dto.ClientSettingUpdateDto(value = viewerFlags.toString()),
                ),
            )
        } catch (e: Exception) {
            // Ignore for now
        }
    }

    override suspend fun updateViewerFlags(resourceUrl: String, viewerFlags: Long) {
        val mangaId = resourceUrl.substringAfterLast('/')
        if (mangaId.isNotBlank()) updateMangaViewerFlags(mangaId, viewerFlags)
    }

    override suspend fun getViewerFlags(resourceUrl: String): Long? {
        val mangaId = resourceUrl.substringAfterLast('/')
        return mangaId.takeIf(String::isNotBlank)?.let { getMangaViewerFlags(it) }
    }

    override suspend fun syncMangaProgress(manga: tachiyomi.domain.manga.model.Manga) {
        Injekt.get<eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService>().syncFromServer(manga)
    }

    override suspend fun setChapterReadStatus(chapterUrl: String, read: Boolean) {
        if (!apiClient.isBook(chapterUrl)) return
        apiClient.setBookReadStatus(chapterUrl, read)
        Injekt.get<TrackerManager>().komga.api.invalidateProgressCache(id)
    }

    override suspend fun syncConnectionHistory() {
        Injekt.get<eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService>()
            .syncHistoryFromServer(sourceId = id, includeCompleted = true)
    }

    override suspend fun syncConnectionEpubProgress() {
        Injekt.get<eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService>()
            .syncEpubProgressFromServer(sourceId = id)
    }

    override suspend fun pullPageProgress(
        chapterUrl: String,
        chapterMemo: kotlinx.serialization.json.JsonObject,
    ): koharia.connection.ConnectionPageProgressSnapshot? {
        val remote = Injekt.get<eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService>()
            .pullBookProgress(id, chapterUrl)
            ?: return null
        val previousPublicationVersion = KomgaChapterMemo.publicationVersion(chapterMemo)
        val updatedMemo = KomgaChapterMemo.mergePublicationMetadata(
            existing = chapterMemo,
            bookUrl = remote.url,
            fileHash = remote.fileHash,
            fileLastModified = remote.fileLastModified,
            sizeBytes = remote.sizeBytes,
            fileName = remote.fileName,
            isEpub = remote.isEpub,
            epubDivinaCompatible = remote.isDivinaCompatibleEpub.takeIf { remote.isEpub },
            pagesCount = remote.totalPages,
        )
        return koharia.connection.ConnectionPageProgressSnapshot(
            resourceId = remote.url,
            pageIndex = remote.pageIndex,
            totalPages = remote.totalPages,
            completed = remote.completed,
            readDate = remote.readDate,
            isEpub = remote.isEpub,
            canOpenAsPages = remote.isEpub && KomgaChapterMemo.canOpenEpubAsPages(updatedMemo),
            updatedChapterMemo = updatedMemo,
            previousPublicationVersion = previousPublicationVersion,
            publicationVersion = KomgaChapterMemo.publicationVersion(updatedMemo),
        )
    }

    override suspend fun pushPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) {
        Injekt.get<eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService>().pushPageProgress(
            sourceId = id,
            chapterUrl = chapterUrl,
            pageIndex = pageIndex,
            totalPages = totalPages,
        )
    }

    override fun findSharedChapterFile(chapterUrl: String, mangaTitle: String): com.hippo.unifile.UniFile? {
        return Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>()
            .findSharedChapterDir(chapterUrl, mangaTitle, this)
    }

    override fun findCompletePagePublication(
        chapter: eu.kanade.tachiyomi.data.database.models.Chapter,
    ): ConnectionPagePublication? {
        if (!KomgaChapterMemo.canOpenEpubAsPages(chapter.memo)) return null
        val pageCount = KomgaChapterMemo.pagesCount(chapter.memo) ?: return null
        val fingerprint = KomgaChapterMemo.readFingerprint(chapter.memo)
        val publicationKey = koharia.epub.cache.EpubCachePolicy.publicationKey(
            fileHash = fingerprint?.fileHash,
            fileLastModified = KomgaChapterMemo.fileLastModified(chapter.memo),
            sizeBytes = fingerprint?.sizeBytes ?: 0L,
            fallback = "book:${chapter.id}:${chapter.url}",
        )
        val file = Injekt.get<koharia.epub.cache.EpubCacheManager>().completeBookFile(id, publicationKey)
            ?: return null
        return ConnectionPagePublication(file, pageCount)
    }

    override fun hasCompleteCachedPublication(chapter: tachiyomi.domain.chapter.model.Chapter): Boolean {
        if (KomgaChapterMemo.isEpub(chapter.memo) != true) return false
        val fingerprint = KomgaChapterMemo.readFingerprint(chapter.memo)
        val publicationKey = koharia.epub.cache.EpubCachePolicy.publicationKey(
            fileHash = fingerprint?.fileHash,
            fileLastModified = KomgaChapterMemo.fileLastModified(chapter.memo),
            sizeBytes = fingerprint?.sizeBytes ?: 0L,
            fallback = "book:${chapter.id}:${chapter.url}",
        )
        return Injekt.get<koharia.epub.cache.EpubCacheManager>().hasCompleteBook(id, publicationKey)
    }

    override suspend fun openRemotePublication(
        request: koharia.epub.model.EpubOpenRequest,
        initialLocator: org.readium.r2.shared.publication.Locator?,
    ): koharia.epub.session.EpubReaderSession {
        return Injekt.get<koharia.epub.service.KomgaEpubPublicationService>().open(request, initialLocator)
    }

    override fun canOpenAsPages(chapterMemo: kotlinx.serialization.json.JsonObject): Boolean =
        KomgaChapterMemo.canOpenEpubAsPages(chapterMemo)

    override fun pageCountFromMemo(chapterMemo: kotlinx.serialization.json.JsonObject): Int? =
        KomgaChapterMemo.pagesCount(chapterMemo)

    override fun hasMigratedPageProgress(chapterMemo: kotlinx.serialization.json.JsonObject): Boolean =
        KomgaChapterMemo.isEpubPageProgressMigrated(chapterMemo)

    override fun markPageProgressMigrated(
        chapterMemo: kotlinx.serialization.json.JsonObject,
    ): kotlinx.serialization.json.JsonObject =
        KomgaChapterMemo.markEpubPageProgressMigrated(chapterMemo)

    override suspend fun resolvePublication(
        chapter: tachiyomi.domain.chapter.model.Chapter,
        allowRemoteLookup: Boolean,
    ): koharia.connection.ConnectionPublicationMetadata {
        val memoFingerprint = KomgaChapterMemo.readFingerprint(chapter.memo)
        val memoIsEpub = KomgaChapterMemo.isEpub(chapter.memo)
        val memoIsDivinaCompatible = KomgaChapterMemo.isEpubDivinaCompatible(chapter.memo)
        val memoPagesCount = KomgaChapterMemo.pagesCount(chapter.memo) ?: 0
        val memoBookUrl = memoFingerprint?.bookUrl ?: chapter.url.substringBefore('#').removeSuffix("/")
        val fallbackPublicationKey = koharia.epub.cache.EpubCachePolicy.publicationKey(
            fileHash = memoFingerprint?.fileHash,
            fileLastModified = KomgaChapterMemo.fileLastModified(chapter.memo),
            sizeBytes = memoFingerprint?.sizeBytes ?: 0L,
            fallback = "book:${chapter.id}:${chapter.url}",
        )
        val shouldLookup = allowRemoteLookup && !KomgaChapterMemo.hasCompleteEpubClassification(chapter.memo)
        val remoteLookup = if (shouldLookup) runCatching { getBookDetails(chapter.url) } else Result.success(null)
        val remoteBook = remoteLookup.getOrNull()
        if (remoteBook != null) {
            val updatedMemo = KomgaChapterMemo.mergeInto(
                existing = chapter.memo,
                baseUrl = baseUrl.trimEnd('/'),
                book = remoteBook,
            )
            if (updatedMemo != chapter.memo) {
                Injekt.get<tachiyomi.domain.chapter.interactor.UpdateChapter>().await(
                    tachiyomi.domain.chapter.model.ChapterUpdate(id = chapter.id, memo = updatedMemo),
                )
            }
        }
        val publicationKey = remoteBook?.let { book ->
            koharia.epub.cache.EpubCachePolicy.publicationKey(
                fileHash = book.fileHash,
                fileLastModified = book.fileLastModified,
                sizeBytes = book.sizeBytes,
                fallback = fallbackPublicationKey,
            )
        } ?: fallbackPublicationKey
        val remoteResourceId = when {
            remoteBook?.isEpub == true -> chapter.url.substringBefore('#').removeSuffix("/")
            memoIsEpub == true -> memoBookUrl
            else -> null
        }
        val isPageCompatible = remoteBook?.media?.let { media ->
            media.isDivinaCompatibleEpub && media.pagesCount > 0
        } ?: (memoIsEpub == true && memoIsDivinaCompatible == true && memoPagesCount > 0)

        return koharia.connection.ConnectionPublicationMetadata(
            remoteResourceId = remoteResourceId,
            publicationKey = publicationKey,
            isPageCompatible = isPageCompatible,
            fileName = remoteBook?.name ?: KomgaChapterMemo.fileName(chapter.memo),
            sizeBytes = remoteBook?.sizeBytes?.takeIf { it > 0L }
                ?: memoFingerprint?.sizeBytes?.takeIf { it > 0L },
            metadataError = remoteLookup.exceptionOrNull(),
        )
    }

    override suspend fun getCachedEpubProgress(
        chapterId: Long,
    ): koharia.domain.epub.model.EpubRemoteProgressCache? {
        return Injekt.get<koharia.epub.progress.KomgaEpubRemoteProgressCoordinator>().get(chapterId)
    }

    override suspend fun refreshEpubProgress(
        mangaId: Long,
        chapter: tachiyomi.domain.chapter.model.Chapter,
    ): koharia.domain.epub.model.EpubRemoteProgressCache? {
        return Injekt.get<koharia.epub.progress.KomgaEpubRemoteProgressCoordinator>()
            .refreshChapter(mangaId, chapter, id)
    }

    override suspend fun syncMangaEpubProgress(
        mangaId: Long,
        chapters: List<tachiyomi.domain.chapter.model.Chapter>,
        force: Boolean,
    ): List<koharia.domain.epub.model.EpubRemoteProgressCache> {
        return Injekt.get<koharia.epub.progress.KomgaEpubRemoteProgressCoordinator>().syncManga(
            mangaId = mangaId,
            sourceId = id,
            chapters = chapters,
            force = force,
        )
    }

    override suspend fun pullEpubProgress(
        resourceId: String,
    ): koharia.connection.RemoteEpubProgression? {
        val progression = Injekt.get<koharia.epub.progress.KomgaEpubProgressSyncService>()
            .pullProgression(id, resourceId)
            .progression
            ?: return null
        return koharia.connection.RemoteEpubProgression(
            locator = progression.locator,
            modifiedAt = progression.modifiedAt,
        )
    }

    override suspend fun pushEpubProgress(
        resourceId: String,
        locator: org.readium.r2.shared.publication.Locator,
        positions: List<org.readium.r2.shared.publication.Locator>,
        modifiedAt: java.util.Date,
    ) {
        Injekt.get<koharia.epub.progress.KomgaEpubProgressSyncService>().pushProgression(
            sourceId = id,
            bookUrl = resourceId,
            locator = locator,
            positions = positions,
            modifiedAt = modifiedAt,
        )
    }

    override suspend fun indexDownloadedChapter(
        chapter: tachiyomi.domain.chapter.model.Chapter,
        localFile: com.hippo.unifile.UniFile,
    ) {
        Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>()
            .indexDownloadedChapter(chapter, this, localFile)
    }

    override suspend fun deleteIndexedFile(file: com.hippo.unifile.UniFile) {
        Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>().deleteIndexedPath(file)
    }

    override suspend fun deleteIndexedPathPrefix(relativePath: String) {
        Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>()
            .deleteIndexedPathPrefix(relativePath)
    }

    override suspend fun deleteIndexedManga(mangaId: Long) {
        Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>()
            .deleteMangaIndexedDownloads(mangaId, id)
    }

    override suspend fun updateIndexedFilePath(
        oldRelativePath: String,
        newFile: com.hippo.unifile.UniFile,
    ) {
        Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>()
            .updateIndexedPath(oldRelativePath, newFile)
    }

    override suspend fun updateIndexedPathPrefix(
        oldRelativePath: String,
        newRelativePath: String,
    ) {
        Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>()
            .updateIndexedPathPrefix(oldRelativePath, newRelativePath)
    }

    override fun indexedRelativePath(file: com.hippo.unifile.UniFile): String? {
        return Injekt.get<eu.kanade.tachiyomi.data.download.KomgaSharedDownloadIndexManager>().relativePathOf(file)
    }

    override fun downloadDirectoryName(): String {
        val provider = Injekt.get<eu.kanade.tachiyomi.data.download.DownloadProvider>()
        return if (usesSharedDownloadStorage) {
            provider.getKomgaSharedDirName()
        } else {
            provider.getKomgaServerDirName(name)
        }
    }

    override fun downloadDirectoryNames(): List<String> {
        val provider = Injekt.get<eu.kanade.tachiyomi.data.download.DownloadProvider>()
        val preferences = Injekt.get<KomgaServerPreferences>()
        return buildList {
            add(downloadDirectoryName())
            if (usesSharedDownloadStorage) {
                addAll(legacySharedDownloadDirectoryNames(provider, preferences))
            } else {
                addAll(legacyDownloadDirectoryNamesForName(name))
                preferences.getDirectoryAliases(id).forEach { alias ->
                    add(provider.getKomgaServerDirName(alias))
                    addAll(legacyDownloadDirectoryNamesForName(alias))
                }
                add(provider.getKomgaSharedDirName())
            }
        }.distinct()
    }

    override fun ownedDownloadDirectoryNames(): Set<String> {
        if (usesSharedDownloadStorage) return setOf(downloadDirectoryName())
        val provider = Injekt.get<eu.kanade.tachiyomi.data.download.DownloadProvider>()
        val preferences = Injekt.get<KomgaServerPreferences>()
        return buildSet {
            add(downloadDirectoryName())
            addAll(legacyDownloadDirectoryNamesForName(name))
            preferences.getDirectoryAliases(id).forEach { alias ->
                add(provider.getKomgaServerDirName(alias))
                addAll(legacyDownloadDirectoryNamesForName(alias))
            }
        }
    }

    override fun legacyDownloadDirectoryNames(): List<String> {
        return if (usesSharedDownloadStorage) {
            legacySharedDownloadDirectoryNames(
                Injekt.get<eu.kanade.tachiyomi.data.download.DownloadProvider>(),
                Injekt.get<KomgaServerPreferences>(),
            )
        } else {
            legacyDownloadDirectoryNamesForName(name)
        }
    }

    private fun legacySharedDownloadDirectoryNames(
        provider: eu.kanade.tachiyomi.data.download.DownloadProvider,
        preferences: KomgaServerPreferences,
    ): List<String> {
        return buildList {
            add(eu.kanade.tachiyomi.util.storage.DiskUtil.buildValidFilename(SOURCE_NAME))
            addAll(legacyDownloadDirectoryNamesForName(SOURCE_NAME))
            preferences.getProfiles().forEach { profile ->
                add(provider.getKomgaServerDirName(profile.name))
                addAll(legacyDownloadDirectoryNamesForName(profile.name))
            }
            add(provider.getKomgaServerDirName(name))
            addAll(legacyDownloadDirectoryNamesForName(name))
            preferences.getDirectoryAliases(id).forEach { alias ->
                add(provider.getKomgaServerDirName(alias))
                addAll(legacyDownloadDirectoryNamesForName(alias))
            }
        }.distinct()
    }

    private fun legacyDownloadDirectoryNamesForName(sourceName: String): List<String> {
        val legacyName = "$sourceName (${SOURCE_LANG.uppercase()})"
        return listOf(
            eu.kanade.tachiyomi.util.storage.DiskUtil.buildValidFilename(legacyName),
            eu.kanade.tachiyomi.util.storage.DiskUtil.buildValidFilename(legacyName, disallowNonAscii = true),
        ).distinct()
    }

    suspend fun getMangaViewerFlags(mangaId: String): Long? {
        if (!hasValidBaseUrl()) return null
        return try {
            val settings = client.newCall(
                eu.kanade.tachiyomi.network.GET("$baseUrl/api/v1/client-settings/user/list", headers),
            )
                .awaitSuccess()
                .let { apiClient.parse<Map<String, koharia.komga.api.dto.ClientSettingDto>>(it) }
            settings["koharia.manga.$mangaId.viewerFlags"]?.value?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    override fun getFilterList(): FilterList {
        fetchFilterOptions()

        val filters = mutableListOf<Filter<*>>(
            TypeSelect(),
            CollectionSelect(
                buildList {
                    add(CollectionFilterEntry("None"))
                    collections.forEach { add(CollectionFilterEntry(it.name, it.id)) }
                },
            ),
            LibraryFilter(libraries, shelfLibraryIds),
            ReadingStateGroup(),
            UriMultiSelectFilter(
                "Status",
                listOf("Ongoing", "Ended", "Abandoned", "Hiatus").map {
                    UriMultiSelectOption(it, it.uppercase(Locale.ROOT))
                },
            ),
            UriMultiSelectFilter("Genres", genres.map { UriMultiSelectOption(it) }),
            UriMultiSelectFilter("Tags", tags.map { UriMultiSelectOption(it) }),
            UriMultiSelectFilter("Publishers", publishers.map { UriMultiSelectOption(it) }),
        ).apply {
            if (fetchFilterStatus != FetchFilterStatus.FETCHED) {
                val message = if (fetchFilterStatus == FetchFilterStatus.NOT_FETCHED && fetchFiltersAttempts >= 3) {
                    application.stringResource(MR.strings.komga_filter_fetch_failed)
                } else {
                    application.stringResource(MR.strings.komga_filter_fetch_hint)
                }

                add(0, Filter.Header(message))
                add(1, Filter.Separator())
            }

            if (authors.isNotEmpty()) {
                add(Filter.Header(application.stringResource(MR.strings.author)))
                addAll(authors.map { (role, items) -> AuthorGroup(role, items.map { AuthorFilter(it) }) })
            }
            add(SeriesSort())
        }

        return FilterList(filters).also {
            if (isPersistentFilteringEnabled()) {
                applyPersistentFilterState(it)
            }
        }
    }

    fun isPersistentFilteringEnabled(libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL): Boolean {
        val scopedKey = persistentFilteringEnabledKey(libraryScope)
        if (libraryScope != KomgaLibraryScope.ALL && !preferences.contains(scopedKey)) {
            return preferences.getBoolean(PREF_PERSISTENT_FILTERS_ENABLED, false)
        }
        return preferences.getBoolean(scopedKey, false)
    }

    fun setPersistentFilteringEnabled(
        enabled: Boolean,
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ) {
        preferences.edit()
            .putBoolean(persistentFilteringEnabledKey(libraryScope), enabled)
            .apply()

        if (enabled) {
            savePersistentFilterState(filters, libraryScope)
        } else {
            preferences.edit()
                .remove(persistentFilterStateKey(libraryScope))
                .apply()
        }
    }

    fun resetPersistentFilters(libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL) {
        val editor = preferences.edit()
        if (libraryScope == KomgaLibraryScope.ALL) {
            editor.remove(PREF_PERSISTENT_FILTERS_STATE)
        } else {
            // A scoped default prevents the legacy unscoped state from being restored again.
            editor.putString(
                persistentFilterStateKey(libraryScope),
                json.encodeToString(defaultLibraryPersistentFilterState()),
            )
        }
        editor.apply()
    }

    fun savePersistentFilterState(
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ) {
        if (!isPersistentFilteringEnabled(libraryScope)) return

        preferences.edit()
            .putString(
                persistentFilterStateKey(libraryScope),
                json.encodeToString(filters.toPersistentFilterState()),
            )
            .apply()
    }

    fun saveSessionFilterState(
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ) {
        synchronized(sessionFilterStates) {
            sessionFilterStates[libraryScope] = filters.toPersistentFilterState()
        }
    }

    fun resetSessionFilterState(libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL) {
        synchronized(sessionFilterStates) {
            sessionFilterStates.remove(libraryScope)
        }
    }

    private fun applyPersistentFilterState(
        filters: FilterList,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ): Boolean {
        val scopedState = preferences.getString(persistentFilterStateKey(libraryScope), null)
        val legacyState = if (libraryScope != KomgaLibraryScope.ALL) {
            preferences.getString(PREF_PERSISTENT_FILTERS_STATE, null)
        } else {
            null
        }
        val saved = (scopedState ?: legacyState)
            ?.let { runCatching { json.decodeFromString<PersistentFilterState>(it) }.getOrNull() }
            ?: return false
        val migrated = saved.migratePersistentFilterState()
        if (migrated != saved) {
            preferences.edit()
                .putString(persistentFilterStateKey(libraryScope), json.encodeToString(migrated))
                .apply()
        }

        if (scopedState != null && libraryScope != KomgaLibraryScope.ALL) {
            filters.resetFilterState()
        }
        filters.applyPersistentFilterState(migrated)
        return true
    }

    private fun FilterList.applyPersistentFilterState(saved: PersistentFilterState) {
        forEach { filter ->
            when (filter) {
                is Filter.CheckBox -> saved.checkBoxes[filter.name]?.let { filter.state = it }
                is Filter.Select<*> -> saved.selects[filter.name]?.let { index ->
                    if (index in filter.values.indices) {
                        filter.state = index
                    }
                }
                is Filter.Sort -> saved.sorts[filter.name]?.let { sort ->
                    if (sort.index in filter.values.indices) {
                        filter.state = Filter.Sort.Selection(sort.index, sort.ascending)
                    }
                }
                is Filter.Group<*> -> {
                    val selected = saved.groups[filter.name]
                    filter.state
                        .filterIsInstance<Filter.CheckBox>()
                        .forEach { option ->
                            option.state = if (selected != null) {
                                option.persistentOptionKey() in selected
                            } else {
                                // Preserve filters saved before standalone checkboxes were grouped in the UI.
                                saved.checkBoxes[option.name] ?: option.state
                            }
                        }
                }
                else -> {}
            }
        }
    }

    private fun persistentFilterStateKey(libraryScope: KomgaLibraryScope): String {
        return when (libraryScope) {
            KomgaLibraryScope.ALL -> PREF_PERSISTENT_FILTERS_STATE
            KomgaLibraryScope.COMIC -> PREF_PERSISTENT_FILTERS_STATE_COMIC
            KomgaLibraryScope.BOOK -> PREF_PERSISTENT_FILTERS_STATE_BOOK
        }
    }

    private fun persistentFilteringEnabledKey(libraryScope: KomgaLibraryScope): String {
        return when (libraryScope) {
            KomgaLibraryScope.ALL -> PREF_PERSISTENT_FILTERS_ENABLED
            KomgaLibraryScope.COMIC -> PREF_PERSISTENT_FILTERS_ENABLED_COMIC
            KomgaLibraryScope.BOOK -> PREF_PERSISTENT_FILTERS_ENABLED_BOOK
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fetchFilterOptions()

        val serverProfileManager = Injekt.get<KomgaServerProfileManager>()
        val serverName = Injekt.get<KomgaServerPreferences>()
            .getProfiles()
            .find { it.id == id }
            ?.name
            ?: name
        screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_server_name_title),
            default = serverName,
            summary = serverName,
            dialogMessage = screen.context.stringResource(MR.strings.komga_server_name_help),
            validate = { value ->
                value.trim().isNotEmpty() &&
                    serverProfileManager.isDirectoryNameAvailable(value, id)
            },
            validationMessage = screen.context.stringResource(MR.strings.komga_server_name_validation),
            key = PREF_SERVER_PROFILE_NAME,
            allowBlank = false,
            showValueAsSummary = true,
        )

        screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_address_title),
            default = "",
            summary = baseUrl.ifBlank { screen.context.stringResource(MR.strings.komga_pref_address_summary) },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = {
                val address = it.trim()
                address.startsWith("http://") || address.startsWith("https://")
            },
            validationMessage = screen.context.stringResource(MR.strings.komga_pref_address_validation),
            key = PREF_ADDRESS,
        )
        val authModePref = androidx.preference.ListPreference(screen.context).apply {
            key = PREF_AUTH_MODE
            title = screen.context.stringResource(MR.strings.komga_pref_auth_mode_title)
            entries = arrayOf(
                screen.context.stringResource(MR.strings.komga_pref_auth_mode_credentials),
                screen.context.stringResource(MR.strings.komga_pref_auth_mode_api_key),
            )
            entryValues = arrayOf(AUTH_MODE_CREDENTIALS, AUTH_MODE_API_KEY)
            setDefaultValue(defaultAuthMode())
            summary = "%s"
        }.also(screen::addPreference)

        val usernamePref = screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_username_title),
            default = "",
            summary = username.ifBlank { screen.context.stringResource(MR.strings.komga_pref_username_summary) },
            key = PREF_USERNAME,
        )
        val passwordPref = screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_password_title),
            default = "",
            summary = if (password.isBlank()) {
                screen.context.stringResource(MR.strings.komga_pref_password_summary)
            } else {
                "*".repeat(password.length)
            },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            key = PREF_PASSWORD,
        )
        val apiKeyPref = screen.addEditTextPreference(
            title = screen.context.stringResource(MR.strings.komga_pref_api_key_title),
            default = "",
            summary = if (apiKey.isBlank()) {
                screen.context.stringResource(MR.strings.komga_pref_api_key_summary)
            } else {
                "*".repeat(apiKey.length)
            },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            key = PREF_API_KEY,
        )

        fun updateAuthFieldsVisibility(mode: String) {
            val isCredentials = mode == AUTH_MODE_CREDENTIALS
            usernamePref.isVisible = isCredentials
            passwordPref.isVisible = isCredentials
            apiKeyPref.isVisible = !isCredentials
        }

        authModePref.setOnPreferenceChangeListener { _, newValue ->
            updateAuthFieldsVisibility(newValue as String)
            true
        }

        val initialAuthMode = screen.preferenceManager.preferenceDataStore
            ?.getString(PREF_AUTH_MODE, null)
            ?: preferences.getString(PREF_AUTH_MODE, null)
            ?: defaultAuthMode()
        updateAuthFieldsVisibility(initialAuthMode)

        androidx.preference.Preference(screen.context).apply {
            key = PREF_DEFAULT_LIBRARIES
            title = screen.context.stringResource(MR.strings.komga_pref_default_libraries_title)
            setDefaultValue(emptySet<String>())

            var isFetching = false

            setOnPreferenceClickListener { pref ->
                if (isFetching) return@setOnPreferenceClickListener true
                isFetching = true

                val dataStore = pref.preferenceManager.preferenceDataStore
                val currentAddress = (dataStore?.getString(PREF_ADDRESS, "") ?: "").trim().trimEnd('/')
                val currentAuthMode =
                    dataStore?.getString(PREF_AUTH_MODE, null) ?: defaultAuthMode()
                val currentUsername = dataStore?.getString(PREF_USERNAME, "") ?: ""
                val currentPassword = dataStore?.getString(PREF_PASSWORD, "") ?: ""
                val currentApiKey = dataStore?.getString(PREF_API_KEY, null)
                    ?: dataStore?.getString(PREF_API_KEY_WRONG_CASE, null)
                    ?: ""

                val currentSelection = dataStore?.getStringSet(PREF_DEFAULT_LIBRARIES, emptySet()) ?: emptySet()

                scope.launch(Dispatchers.Main) {
                    val fetchedLibraries = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        try {
                            if (currentAddress.isBlank()) return@withContext emptyList<LibraryDto>()

                            val tempHeaders = Headers.Builder().apply {
                                if (currentAuthMode == AUTH_MODE_API_KEY && currentApiKey.isNotBlank()) {
                                    add("X-Komga-Api-Key", currentApiKey)
                                } else if (currentAuthMode == AUTH_MODE_CREDENTIALS && currentUsername.isNotBlank() &&
                                    currentPassword.isNotBlank()
                                ) {
                                    add("Authorization", Credentials.basic(currentUsername, currentPassword))
                                }
                            }.build()

                            val cleanClient = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
                            val tempApiClient = KomgaApiClient(currentAddress, tempHeaders, cleanClient, json)
                            val tempRepo = KomgaRepository(currentAddress, tempApiClient)
                            tempRepo.fetchFilterOptions(forceRefresh = true).libraries
                        } catch (e: Exception) {
                            emptyList<LibraryDto>()
                        }
                    }

                    if (fetchedLibraries.isEmpty()) {
                        isFetching = false
                        return@launch
                    }

                    val availableIds = fetchedLibraries.mapTo(linkedSetOf(), LibraryDto::id)
                    val newSelection = currentSelection.intersect(availableIds).toMutableSet()
                    val names = listOf(screen.context.stringResource(MR.strings.all))
                        .plus(fetchedLibraries.map { it.name })
                        .toTypedArray<CharSequence>()
                    val checkedItems = BooleanArray(names.size) { index ->
                        if (index == 0) newSelection.isEmpty() else fetchedLibraries[index - 1].id in newSelection
                    }

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(screen.context)
                        .setTitle(screen.context.stringResource(MR.strings.komga_pref_default_libraries_title))
                        .setMultiChoiceItems(names, checkedItems) { dialog, which, isChecked ->
                            val alertDialog = dialog as? androidx.appcompat.app.AlertDialog
                            if (which == 0) {
                                if (isChecked) {
                                    newSelection.clear()
                                    fetchedLibraries.indices.forEach { index ->
                                        alertDialog?.listView?.setItemChecked(index + 1, false)
                                    }
                                } else if (newSelection.isEmpty()) {
                                    alertDialog?.listView?.setItemChecked(0, true)
                                }
                            } else {
                                val id = fetchedLibraries[which - 1].id
                                if (isChecked) newSelection.add(id) else newSelection.remove(id)
                                alertDialog?.listView?.setItemChecked(0, newSelection.isEmpty())
                            }
                        }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            if (newSelection != currentSelection) {
                                if (callChangeListener(newSelection)) {
                                    dataStore?.putStringSet(PREF_DEFAULT_LIBRARIES, newSelection)
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener { isFetching = false }
                        .show()
                }
                true
            }
        }.also(screen::addPreference)

        screen.addEditTextPreference(
            key = PREF_CHAPTER_NAME_TEMPLATE,
            title = screen.context.stringResource(MR.strings.komga_pref_chapter_name_template_title),
            summary = screen.context.stringResource(MR.strings.komga_pref_chapter_name_template_summary),
            inputType = InputType.TYPE_CLASS_TEXT,
            default = PREF_CHAPTER_NAME_TEMPLATE_DEFAULT,
            dialogMessage = screen.context.stringResource(MR.strings.komga_pref_chapter_name_template_dialog),
        )
    }

    suspend fun getBrowseLibraries(forceRefresh: Boolean = false): List<LibraryDto> {
        if (!hasValidBaseUrl()) {
            fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
            return emptyList()
        }

        return try {
            val options = repository.fetchFilterOptions(forceRefresh)
            libraries = options.libraries
            collections = options.collections
            genres = options.genres
            tags = options.tags
            publishers = options.publishers
            authors = options.authors
            fetchFilterStatus = FetchFilterStatus.FETCHED
            libraries
        } catch (e: Exception) {
            fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
            Log.e("KomgaSource", "Failed to load Komga libraries", e)
            throw e
        }
    }

    fun invalidateBrowseCache() {
        libraries = emptyList()
        collections = emptyList()
        genres = emptySet()
        tags = emptySet()
        publishers = emptySet()
        authors = emptyMap()
        fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
        fetchFiltersAttempts = 0
    }

    fun refreshBrowseRequests() {
        forceBrowseRequestsUntil.set(System.currentTimeMillis() + BROWSE_REFRESH_WINDOW_MILLIS)
    }

    fun configuredShelfLibraryIds(): Set<String> = shelfLibraryIds.toSet()

    fun findCachedLibraryId(contentUrl: String): String? = metadataCacheStore.findLibraryId(contentUrl)

    fun findCachedLibraryIds(contentUrl: String): Set<String> = metadataCacheStore.findLibraryIds(contentUrl)

    fun registerServerSettingsChangeListener(
        onChanged: (shelfLibrariesChanged: Boolean) -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in SERVER_SETTING_KEYS) {
                searchCapabilities.clear()
                invalidateBrowseCache()
                onChanged(key == PREF_DEFAULT_LIBRARIES)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterServerSettingsChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun buildFilterListForLibrary(
        libraryId: String?,
        preservePersistentFilters: Boolean = false,
        allowedLibraryIds: Set<String>? = null,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
        currentFilters: FilterList? = null,
        preserveSessionFilters: Boolean = false,
        fallbackLibraries: List<LibraryDto>? = null,
        librarySelectionOverride: Set<String>? = null,
        resetLibrarySelection: Boolean = false,
        forceConfiguredLibrarySelection: Boolean = false,
    ): FilterList {
        val filters = getFilterList().withFallbackLibraries(fallbackLibraries)
        val currentState = currentFilters
            ?.takeIf { it.isNotEmpty() }
            ?.toPersistentFilterState()
        val sessionState = if (preserveSessionFilters) {
            synchronized(sessionFilterStates) { sessionFilterStates[libraryScope] }
        } else {
            null
        }
        val hasPreservedState = when {
            currentState != null -> {
                filters.applyPersistentFilterState(currentState)
                true
            }
            sessionState != null -> {
                filters.applyPersistentFilterState(sessionState)
                true
            }
            preservePersistentFilters && isPersistentFilteringEnabled(libraryScope) -> {
                applyPersistentFilterState(filters, libraryScope)
            }
            else -> false
        }
        val scopedFilters = filters.restrictLibraries(allowedLibraryIds)

        if (!hasPreservedState) {
            scopedFilters.resetFilterState()
            scopedFilters.filterIsInstance<TypeSelect>().firstOrNull()?.state = TYPE_SERIES_INDEX
            scopedFilters.filterIsInstance<SeriesSort>().firstOrNull()?.state = Filter.Sort.Selection(0, true)
        }
        scopedFilters.filterIsInstance<LibraryFilter>().firstOrNull()?.state?.let { options ->
            when {
                librarySelectionOverride != null -> {
                    val availableSelection = librarySelectionOverride.intersect(options.mapTo(linkedSetOf()) { it.id })
                    val selection = if (availableSelection.isEmpty() && allowedLibraryIds != null) {
                        options.mapTo(linkedSetOf()) { it.id }
                    } else {
                        availableSelection
                    }
                    options.forEach { option -> option.state = option.id in selection }
                }
                libraryId != null -> options.forEach { option -> option.state = option.id == libraryId }
                forceConfiguredLibrarySelection || (resetLibrarySelection && !hasPreservedState) -> {
                    val availableDefaults = shelfLibraryIds.intersect(options.mapTo(linkedSetOf()) { it.id })
                    val selection = if (availableDefaults.isEmpty() && allowedLibraryIds != null) {
                        options.mapTo(linkedSetOf()) { it.id }
                    } else {
                        availableDefaults
                    }
                    options.forEach { option -> option.state = option.id in selection }
                }
                allowedLibraryIds != null && options.none { it.state } -> options.forEach { it.state = true }
            }
        }
        return scopedFilters
    }

    fun buildFilterListForTagSearch(
        tag: String,
        allowedLibraryIds: Set<String>? = null,
        libraryScope: KomgaLibraryScope = KomgaLibraryScope.ALL,
    ): FilterList {
        val targetGroup = when {
            tags.any { it.equals(tag, true) } -> "Tags"
            genres.any { it.equals(tag, true) } -> "Genres"
            else -> "Tags"
        }
        Log.d("KomgaSource", "buildFilterListForTagSearch: tag=$tag targetGroup=$targetGroup")

        val filters = buildFilterListForLibrary(
            libraryId = null,
            preservePersistentFilters = true,
            allowedLibraryIds = allowedLibraryIds,
            libraryScope = libraryScope,
            preserveSessionFilters = true,
        )
            .withSelectedMultiOption(targetGroup, tag)
        filters.filterIsInstance<TypeSelect>().firstOrNull()?.state = 0
        return filters
    }

    private var libraries = emptyList<koharia.komga.api.dto.LibraryDto>()
    private var collections = emptyList<koharia.komga.api.dto.CollectionDto>()
    private var genres = emptySet<String>()
    private var tags = emptySet<String>()
    private var publishers = emptySet<String>()
    private var authors = emptyMap<String, List<koharia.komga.api.dto.AuthorDto>>()
    private val sessionFilterStates = mutableMapOf<KomgaLibraryScope, PersistentFilterState>()

    private var fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
    private var fetchFiltersAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun fetchFilterOptions() {
        if (!hasValidBaseUrl() || fetchFilterStatus != FetchFilterStatus.NOT_FETCHED || fetchFiltersAttempts >= 3) {
            return
        }

        fetchFilterStatus = FetchFilterStatus.FETCHING
        fetchFiltersAttempts++

        scope.launch {
            try {
                repository.fetchFilterOptions().let {
                    libraries = it.libraries
                    collections = it.collections
                    genres = it.genres
                    tags = it.tags
                    publishers = it.publishers
                    authors = it.authors
                }
                fetchFilterStatus = FetchFilterStatus.FETCHED
            } catch (e: Exception) {
                fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
                Log.e("KomgaSource", "Failed to fetch filtering options", e)
            }
        }
    }

    private fun consumeBrowseCachePolicy(): KomgaCachePolicy {
        return if (System.currentTimeMillis() <= forceBrowseRequestsUntil.get()) {
            KomgaCachePolicy.NetworkFirst
        } else {
            KomgaCachePolicy.Default
        }
    }

    override fun chapterPageParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        const val SOURCE_NAME = "Komga"
        private const val KOMGA_DETAILS_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L
        internal val MANGA_BEHAVIOR = ConnectionMangaBehavior(
            usesCacheTerminology = true,
            supportsChapterCoverGrid = true,
            allowsLocalLibraryManagement = false,
            allowsFetchIntervalManagement = false,
            showSourceName = false,
            detailsRefreshIntervalMillis = KOMGA_DETAILS_REFRESH_INTERVAL_MS,
        )
        const val SOURCE_LANG = "all"
        internal const val PREF_SERVER_PROFILE_NAME = "Koharia server profile name"
        const val SOURCE_VERSION = 1
        const val TYPE_SERIES = "Series"
        const val TYPE_READ_LISTS = "Read lists"
        const val TYPE_BOOKS = "Books"
        const val TYPE_ALL = "All"
        private const val BROWSE_REFRESH_WINDOW_MILLIS = 30_000L

        private val SERVER_SETTING_KEYS = setOf(
            PREF_SERVER_PROFILE_NAME,
            PREF_ADDRESS,
            PREF_USERNAME,
            PREF_PASSWORD,
            PREF_API_KEY,
            PREF_API_KEY_WRONG_CASE,
            PREF_AUTH_MODE,
            PREF_DEFAULT_LIBRARIES,
            PREF_CHAPTER_NAME_TEMPLATE,
        )

        val ID: Long by lazy {
            val key = "${SOURCE_NAME.lowercase()}/$SOURCE_LANG/$SOURCE_VERSION"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }

    private fun defaultAuthMode(): String {
        return if (apiKey.isNotBlank()) AUTH_MODE_API_KEY else AUTH_MODE_CREDENTIALS
    }
}

@Serializable
internal data class PersistentFilterState(
    val version: Int = 0,
    val checkBoxes: Map<String, Boolean> = emptyMap(),
    val selects: Map<String, Int> = emptyMap(),
    val sorts: Map<String, PersistentSortState> = emptyMap(),
    val groups: Map<String, Set<String>> = emptyMap(),
)

@Serializable
internal data class PersistentSortState(
    val index: Int,
    val ascending: Boolean,
)

private fun defaultLibraryPersistentFilterState(): PersistentFilterState {
    return PersistentFilterState(
        version = CURRENT_PERSISTENT_FILTER_VERSION,
        selects = mapOf("Search for" to TYPE_SERIES_INDEX),
        sorts = mapOf("Sort" to PersistentSortState(index = 0, ascending = true)),
    )
}

internal fun PersistentFilterState.migratePersistentFilterState(): PersistentFilterState {
    if (version >= CURRENT_PERSISTENT_FILTER_VERSION) return this
    return copy(version = CURRENT_PERSISTENT_FILTER_VERSION)
}

private enum class FetchFilterStatus {
    NOT_FETCHED,
    FETCHING,
    FETCHED,
}

private fun FilterList.withSelectedMultiOption(groupName: String, optionId: String): FilterList {
    var groupFound = false
    val updatedFilters = list.map { filter ->
        if (filter !is UriMultiSelectFilter || filter.name != groupName) {
            return@map filter
        }

        groupFound = true
        var optionFound = false
        val options = filter.state.map { option ->
            option.apply {
                if (id.equals(optionId, true) || name.equals(optionId, true)) {
                    state = true
                    optionFound = true
                }
            }
        }.let { options ->
            if (optionFound) {
                options
            } else {
                options + UriMultiSelectOption(optionId).apply { state = true }
            }
        }
        UriMultiSelectFilter(groupName, options)
    }

    return FilterList(
        if (groupFound) {
            updatedFilters
        } else {
            list + UriMultiSelectFilter(
                groupName,
                listOf(UriMultiSelectOption(optionId).apply { state = true }),
            )
        },
    )
}

private fun FilterList.restrictLibraries(allowedLibraryIds: Set<String>?): FilterList {
    if (allowedLibraryIds == null) return this
    return FilterList(
        map { filter ->
            if (filter is LibraryFilter) {
                val selectedLibraryIds = filter.state
                    .filter { it.state && it.id in allowedLibraryIds }
                    .mapTo(linkedSetOf(), UriMultiSelectOption::id)
                LibraryFilter(
                    libraries = filter.state
                        .filter { it.id in allowedLibraryIds }
                        .map { LibraryDto(id = it.id, name = it.name) },
                    defaultLibraries = selectedLibraryIds,
                )
            } else {
                filter
            }
        },
    )
}

private fun FilterList.withFallbackLibraries(fallbackLibraries: List<LibraryDto>?): FilterList {
    if (fallbackLibraries.isNullOrEmpty()) return this
    return FilterList(
        map { filter ->
            if (filter is LibraryFilter && filter.state.isEmpty()) {
                LibraryFilter(fallbackLibraries, emptySet())
            } else {
                filter
            }
        },
    )
}

private fun FilterList.toPersistentFilterState(): PersistentFilterState {
    val checkBoxes = mutableMapOf<String, Boolean>()
    val selects = mutableMapOf<String, Int>()
    val sorts = mutableMapOf<String, PersistentSortState>()
    val groups = mutableMapOf<String, Set<String>>()

    forEach { filter ->
        when (filter) {
            is Filter.CheckBox -> checkBoxes[filter.name] = filter.state
            is Filter.Select<*> -> selects[filter.name] = filter.state
            is Filter.Sort -> {
                filter.state?.let { sorts[filter.name] = PersistentSortState(it.index, it.ascending) }
            }
            is Filter.Group<*> -> {
                groups[filter.name] = filter.state
                    .filterIsInstance<Filter.CheckBox>()
                    .filter { it.state }
                    .map { it.persistentOptionKey() }
                    .toSet()
            }
            else -> {}
        }
    }

    return PersistentFilterState(
        version = CURRENT_PERSISTENT_FILTER_VERSION,
        checkBoxes = checkBoxes,
        selects = selects,
        sorts = sorts,
        groups = groups,
    )
}

private fun FilterList.resetFilterState() {
    forEach { filter ->
        when (filter) {
            is Filter.CheckBox -> filter.state = false
            is Filter.Select<*> -> filter.state = 0
            is Filter.Sort -> filter.state = null
            is Filter.Group<*> ->
                filter.state
                    .filterIsInstance<Filter.CheckBox>()
                    .forEach { it.state = false }
            else -> {}
        }
    }
}

private fun Filter.CheckBox.persistentOptionKey(): String {
    return when (this) {
        is UriMultiSelectOption -> id
        is AuthorFilter -> "${author.name}\u001F${author.role}"
        else -> name
    }
}

private const val PREF_ADDRESS = "Address"
private const val PREF_USERNAME = "Username"
private const val PREF_PASSWORD = "Password"
private const val PREF_API_KEY = "API key"
private const val PREF_API_KEY_WRONG_CASE = "Api key"

private const val PREF_AUTH_MODE = "AuthMode"
private const val AUTH_MODE_CREDENTIALS = "Credentials"
private const val AUTH_MODE_API_KEY = "ApiKey"
private const val PREF_DEFAULT_LIBRARIES = "Default libraries"
private const val PREF_CHAPTER_NAME_TEMPLATE = "Chapter name template"
private const val PREF_CHAPTER_NAME_TEMPLATE_DEFAULT = "{number} - {title} ({size})"
private const val PREF_PERSISTENT_FILTERS_ENABLED = "Persistent filters enabled"
private const val PREF_PERSISTENT_FILTERS_ENABLED_COMIC = "Persistent filters enabled comic"
private const val PREF_PERSISTENT_FILTERS_ENABLED_BOOK = "Persistent filters enabled book"
private const val PREF_PERSISTENT_FILTERS_STATE = "Persistent filters state"
private const val PREF_PERSISTENT_FILTERS_STATE_COMIC = "Persistent filters state comic"
private const val PREF_PERSISTENT_FILTERS_STATE_BOOK = "Persistent filters state book"
private const val CURRENT_PERSISTENT_FILTER_VERSION = 1

private fun PreferenceScreen.addEditTextPreference(
    title: String,
    default: String,
    summary: String,
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: String? = null,
    key: String = title,
    allowBlank: Boolean = true,
    showValueAsSummary: Boolean = false,
): EditTextPreference {
    return EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        if (showValueAsSummary) {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
        setDefaultValue(default)
        dialogTitle = title
        this.dialogMessage = dialogMessage

        fun isValidValue(text: String): Boolean {
            return if (text.isBlank()) allowBlank else validate?.invoke(text) ?: true
        }

        setOnBindEditTextListener { editText ->
            if (inputType != null) {
                editText.inputType = inputType
            }
            if (validate != null) {
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(editable: Editable?) {
                            val text = editable?.toString().orEmpty()
                            val isValid = isValidValue(text)
                            editText.error = if (!isValid) validationMessage else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)?.isEnabled =
                                editText.error == null
                        }
                    },
                )
            }
        }

        setOnPreferenceChangeListener { _, newValue ->
            val text = newValue as String
            isValidValue(text)
        }
    }.also(::addPreference)
}
