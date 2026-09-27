package koharia.source.kavita

import android.content.Context
import androidx.preference.PreferenceScreen
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import koharia.connection.ConnectionAccount
import koharia.connection.ConnectionAccountAdapter
import koharia.connection.ConnectionBackupRestoreAdapter
import koharia.connection.ConnectionBrowseAdapter
import koharia.connection.ConnectionCatalogAdapter
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionHealthAdapter
import koharia.connection.ConnectionHistorySyncAdapter
import koharia.connection.ConnectionLocalPageProgressAdapter
import koharia.connection.ConnectionManagedLifecycle
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.connection.ConnectionMangaProgressAdapter
import koharia.connection.ConnectionPageAdapter
import koharia.connection.ConnectionPageList
import koharia.connection.ConnectionPageProgressAdapter
import koharia.connection.ConnectionPageProgressSnapshot
import koharia.connection.ConnectionPdfFileAdapter
import koharia.connection.ConnectionPublicationMetadata
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.ConnectionRawDownloadResumePolicy
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.ConnectionReaderRoutingAdapter
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.connection.RemoteEpubProgression
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.kavita.KavitaRepository
import koharia.epub.model.EpubOpenRequest
import koharia.kavita.KavitaApiClient
import koharia.kavita.KavitaCatalog
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaEndpoint
import koharia.kavita.KavitaException
import koharia.kavita.KavitaFilter
import koharia.kavita.KavitaFilterStatement
import koharia.kavita.KavitaIdentity
import koharia.kavita.KavitaPdfCache
import koharia.kavita.KavitaReadingCoordinator
import koharia.kavita.KavitaReadingState
import koharia.kavita.KavitaSeries
import koharia.kavita.KavitaSort
import koharia.kavita.kavitaTimestamp
import koharia.kavita.ui.KavitaLibraryScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import org.readium.r2.shared.publication.Locator
import rx.Observable
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class KavitaSource(private val context: Context, override val connectionProfile: LibraryConnectionProfile) :
    HttpSource(),
    ConfigurableSource,
    UnmeteredSource,
    ConnectionSource,
    ConnectionManagedLifecycle,
    ConnectionBrowseAdapter,
    ConnectionCatalogAdapter,
    ConnectionPageAdapter,
    ConnectionAccountAdapter,
    ConnectionHealthAdapter,
    ConnectionMangaBehaviorAdapter,
    koharia.connection.ConnectionChapterTitleAdapter,
    ConnectionDownloadStorageAdapter,
    ConnectionReaderRoutingAdapter,
    ConnectionPageProgressAdapter,
    ConnectionLocalPageProgressAdapter,
    ConnectionMangaProgressAdapter,
    ConnectionReadStatusAdapter,
    ConnectionHistorySyncAdapter,
    ConnectionBackupRestoreAdapter,
    ConnectionRawDownloadAdapter,
    ConnectionPdfFileAdapter,
    koharia.connection.ConnectionPublicationAdapter,
    koharia.connection.ConnectionEpubProgressAdapter,
    koharia.connection.ConnectionLocalEpubProgressAdapter,
    koharia.connection.ConnectionReflowProgressAdapter,
    koharia.connection.ConnectionReadingQueueAdapter,
    koharia.connection.ConnectionSeriesActionsAdapter,
    koharia.connection.ConnectionRemoteAnnotationsAdapter,
    koharia.connection.ConnectionRemoteBookmarksAdapter,
    koharia.connection.ConnectionDownloadAuthorizationAdapter,
    AutoCloseable {
    override val id = connectionProfile.id
    override val name = connectionProfile.name
    override val lang = "other"
    override val supportsLatest = true
    val preferences = KavitaPreferences(id)
    val epoch = MutableStateFlow(0L)
    val instanceKey = java.util.UUID.randomUUID().toString()
    private val repository: KavitaRepository = Injekt.get()
    private val mangas: MangaRepository by lazy { Injekt.get() }
    private val chapters: ChapterRepository by lazy { Injekt.get() }
    private val materializeMutex = Mutex()
    private val historyMutex = Mutex()

    @Volatile private var active: Session? = null

    @Volatile private var closed = false
    private var registered = false

    inner class Session internal constructor(val accountKey: String) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identity = KavitaIdentity(id, accountKey)
        val prefix get() = identity.prefix
        val api =
            KavitaApiClient(
                network.client,
                preferences.address,
                preferences.key,
                "$id-$accountKey",
                preferences.principal.takeIf { preferences.identity == null },
                router = koharia.connection.ConnectionAddressRouter.forAndroid(
                    context,
                    {
                        preferences.address
                    },
                    { preferences.internalAddress },
                    "api/health",
                    authenticateProbe = false,
                ),
                expectedIdentity = preferences.identity,
            )
        val catalog = KavitaCatalog(
            id,
            accountKey,
            repository,
            api,
            onChapterChanged = { invalidateChapterContent(it, confirmedReplacement = true) },
            checkSession = ::checkActive,
        )
        val pdf = KavitaPdfCache(context, id, accountKey)
        val organization = koharia.kavita.KavitaOrganization(id, catalog, ::checkActive)
        val bookmarks = koharia.kavita.KavitaBookmarks(id, accountKey, repository, catalog, scope, ::checkActive) {
            preferences.capabilities.writable &&
                !Injekt.get<koharia.connection.SharedAppPreferences>().basePreferences().incognitoMode.get()
        }
        val annotations = koharia.kavita.KavitaAnnotationCoordinator(
            id,
            accountKey,
            preferences.identity?.userId ?: 0,
            repository,
            catalog,
            scope,
            ::checkActive,
        ) {
            preferences.capabilities.annotations && preferences.capabilities.writable &&
                !Injekt.get<koharia.connection.SharedAppPreferences>().basePreferences().incognitoMode.get()
        }
        val downloadClient = api.client.newBuilder()
            .dispatcher(okhttp3.Dispatcher())
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).apply {
                interceptors().add(
                    0,
                    koharia.kavita.KavitaPublicationDownload(
                        this@Session,
                        java.io.File(context.cacheDir, "kavita-transfer/$id/$accountKey"),
                    ),
                )
            }.build()
        val reading =
            KavitaReadingCoordinator(id, accountKey, repository, api, scope, ::checkActive) { applyState(this, it) }
        suspend fun invalidateChapterContent(chapterId: Long, confirmedReplacement: Boolean = false) {
            checkActive()
            if (confirmedReplacement) annotations.contentChanged(chapterId)
            pdf.invalidate(chapterId.toString())
            val ids = mangas.getMangaBySourceId(id).filter { it.url.startsWith(prefix) }.map { it.id }
            val cache: eu.kanade.tachiyomi.data.cache.ChapterCache = Injekt.get()
            chapters.getChaptersByMangaIds(ids).filter {
                runCatching { identity.chapter(it.url).chapterId == chapterId }.getOrDefault(false)
            }.forEach(cache::removePageListFromCache)
        }
        fun checkActive() {
            if (closed || active !== this) throw CancellationException("Connection session changed")
        }
        override fun close() {
            scope.cancel()
            downloadClient.dispatcher.cancelAll()
            api.close()
        }
    }

    @Synchronized fun session(): Session {
        check(!closed)
        return active ?: Session(preferences.accountKey).also { active = it }
    }

    @Synchronized fun reload() {
        if (closed) return
        active?.close()
        active = null
        epoch.value++
        if (registered && hasValidConnection()) observe(session())
    }
    override fun close() {
        closed = true
        active?.close()
        active = null
    }

    @Synchronized override fun onRegistered() {
        if (registered || closed) return
        registered = true
        if (hasValidConnection()) observe(session())
    }
    private fun observe(session: Session) {
        session.scope.launch {
            Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.collect { available ->
                if (available) {
                    session.reading.retryPending()
                    session.annotations.retryPending()
                    session.bookmarks.retryPending()
                }
            }
        }
        session.scope.launch {
            Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.collectLatest { available ->
                if (available) {
                    koharia.kavita.KavitaEvents(session.api, session.scope) { events ->
                        session.checkActive()
                        var shelfChanged = false
                        for (event in events) {
                            val seriesId = event.body["seriesId"]?.jsonPrimitive?.longOrNull
                            when (event.name) {
                                "UserProgressUpdate" -> {
                                    session.catalog.invalidateGroups("resource/Stats/")
                                    val local = mangas.getMangaBySourceId(id).filter {
                                        it.url.startsWith(session.prefix) &&
                                            (seriesId == null || session.identity.seriesId(it.url) == seriesId)
                                    }
                                    local.forEach { syncMangaProgress(it) }
                                }
                                "LibraryModified", "UserUpdate", "AuthKeyUpdate", "AuthKeyDeleted" -> {
                                    session.api.invalidateAuthentication()
                                    val account = session.api.getAccount()
                                    session.checkActive()
                                    preferences.save(preferences.address, preferences.key, account)
                                    session.catalog.invalidate()
                                    session.catalog.libraries(true)
                                    shelfChanged = true
                                }
                                "SeriesAdded", "SeriesRemoved", "SeriesUpdated",
                                "ChapterRemoved", "VolumeRemoved", "CoverUpdate",
                                -> {
                                    if (event.name != "CoverUpdate") {
                                        val chapterId = event.body["chapterId"]?.jsonPrimitive?.longOrNull
                                        val changed =
                                            chapterId?.let { listOf(it) } ?: session.catalog.cachedChapterIds(seriesId)
                                        changed.forEach {
                                            session.catalog.invalidateChapter(it)
                                            session.invalidateChapterContent(it)
                                        }
                                    }
                                    session.catalog.invalidateGroups("shelf/")
                                    if (seriesId != null) {
                                        session.catalog.invalidateGroups(
                                            "series/$seriesId",
                                            "metadata/$seriesId",
                                            "volumes/$seriesId",
                                        )
                                    } else {
                                        session.catalog.invalidateGroups("series/", "metadata/", "volumes/")
                                    }
                                    event.body["chapterId"]?.jsonPrimitive?.longOrNull?.let {
                                        session.catalog.invalidateChapter(it)
                                    } ?: session.catalog.invalidateGroups("chapter/", "book/", "toc/")
                                    shelfChanged = true
                                }
                                "CollectionUpdated", "ReadingListUpdated", "SmartCollectionSync", "DashboardUpdate" -> {
                                    session.catalog.invalidateGroups("resource/", "shelf/")
                                    shelfChanged = true
                                }
                                "AnnotationUpdate" -> {
                                    session.catalog.invalidateGroups(
                                        "annotations/",
                                        "resource/Annotation/",
                                        "annotation-browser/",
                                    )
                                    session.annotations.changes.tryEmit(Unit)
                                }
                                "LicenseInfoUpdate" -> session.catalog.invalidateGroups("resource/License/")
                                "ScrobbleProviderUpdated" -> session.catalog.invalidateGroups("scrobbling/")
                                "ExternalMetadataUpdate" -> session.catalog.invalidateGroups(
                                    "resource/Metadata/series-detail-plus",
                                )
                            }
                        }
                        if (shelfChanged) koharia.connection.ConnectionShelfUpdates.notify(id)
                    }.run()
                }
            }
        }
    }

    override val baseUrl get() = preferences.address.trimEnd('/')

    override fun supportsRemoteAnnotations(chapterUrl: String): Boolean =
        preferences.capabilities.annotations && runCatching {
            session().identity.chapter(chapterUrl).format == 3
        }.getOrDefault(false)

    override fun annotationChanges(chapterUrl: String) = session().annotations.changes

    override fun supportsPersonalToc(chapterUrl: String): Boolean =
        runCatching { session().identity.chapter(chapterUrl).format == 3 }.getOrDefault(false)

    @androidx.compose.runtime.Composable
    override fun BookmarkAction(chapterUrl: String, page: Int, imageOffset: Int, anchor: String, readOnly: Boolean) {
        koharia.kavita.ui.KavitaBookmarkAction(this, chapterUrl, page, imageOffset, anchor, readOnly)
    }

    @androidx.compose.runtime.Composable
    override fun PersonalTocDialog(
        chapterUrl: String,
        locator: Locator?,
        readOnly: Boolean,
        onNavigate: (Locator) -> Unit,
        onDismiss: () -> Unit,
    ) {
        koharia.kavita.ui.KavitaPersonalTocDialog(this, chapterUrl, locator, readOnly, onNavigate, onDismiss)
    }

    override suspend fun annotationHighlights(chapterUrl: String): List<koharia.connection.ConnectionTextHighlight> {
        val session = session()
        val chapterId = session.identity.chapter(chapterUrl).chapterId
        val records = try {
            session.annotations.load(chapterId)
        } catch (failure: java.io.IOException) {
            if (failure is KavitaException && failure.reason != KavitaException.Reason.NETWORK) throw failure
            session.annotations.cached(chapterId)
        }
        val colors = try {
            koharia.kavita.kavitaHighlightColors(session.catalog.resource("Users/get-preferences").jsonObject)
        } catch (failure: java.io.IOException) {
            if (failure is KavitaException && failure.reason !in setOf(
                    KavitaException.Reason.NETWORK,
                    KavitaException.Reason.UNSUPPORTED,
                )
            ) {
                throw failure
            }
            emptyMap()
        }
        return records.filter { !it.state.deleted && !it.state.anchorStale }.map {
            val value = it.state.annotation
            koharia.connection.ConnectionTextHighlight(
                koharia.connection.ConnectionTextSelection(
                    koharia.kavita.ui.annotationLocator(session.api, value),
                    value.xPath,
                    value.endingXPath ?: value.xPath,
                    value.selectedText,
                ),
                value.selectedSlotIndex,
                colors[value.selectedSlotIndex],
            )
        }
    }

    @androidx.compose.runtime.Composable
    override fun AnnotationsDialog(
        chapterUrl: String,
        selection: koharia.connection.ConnectionTextSelection?,
        readOnly: Boolean,
        onNavigate: (Locator) -> Unit,
        onDismiss: () -> Unit,
    ) {
        koharia.kavita.ui.KavitaAnnotationsDialog(this, chapterUrl, selection, readOnly, onNavigate, onDismiss)
    }
    override val client get() = session().api.client
    override val pageLoadConcurrency = 2
    override val pagePrefetchOnActivate = false
    override val pagePrefetchSize = 2
    override val preserveDownloadPageBoundaries = true
    override val allowsUnvalidatedNetwork = true
    override val usesSharedDownloadStorage = false
    override val mangaBehavior get() = ConnectionMangaBehavior(
        allowsChapterDownloads = preferences.capabilities.downloads,
        providerManagedLibrary = true,
        allowsLocalLibraryManagement = false,
        allowsCategoryManagement = false,
        allowsFetchIntervalManagement = false,
        supportsChapterCoverGrid = true,
    )
    override fun hasValidConnection() = preferences.key.isNotBlank() && preferences.accountKey.isNotBlank() &&
        runCatching { KavitaEndpoint.parse(preferences.address) }.isSuccess
    override suspend fun isConnectionReachable() = runCatching { session().api.getAccount() }.isSuccess
    override suspend fun getAccount() = session().api.getAccount().let { ConnectionAccount(it.username, it.roles) }
    override fun availableContentScopes() = setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK)
    override suspend fun readerContentScope(manga: Manga, chapter: Chapter): LibraryContentScope {
        val session = session()
        val ref = session.identity.chapter(chapter.url)
        if (ref.format == 3) return LibraryContentScope.BOOK
        if (ref.format == 4 && (chapter.memo["kavitaFileCount"]?.jsonPrimitive?.longOrNull ?: 1) > 1) {
            return LibraryContentScope.COMIC
        }
        val library = session.catalog.libraries().firstOrNull { it.id == ref.libraryId }
        return if (library?.type in setOf(2, 4)) LibraryContentScope.BOOK else LibraryContentScope.COMIC
    }
    override fun seriesSettingsAvailable() = flowOf(true)
    override fun createBrowseScreen(scope: LibraryContentScope, listingQuery: String?, showNavigationUp: Boolean) =
        KavitaLibraryScreen(id, listingQuery, showNavigationUp, contentScope = scope)
    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit
    override fun downloadDirectoryName() = "Kavita_${id}_${preferences.accountKey}"
    override fun downloadDirectoryNames() = listOf(downloadDirectoryName())
    override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())
    override fun legacyDownloadDirectoryNames() = emptyList<String>()
    override fun chapterThumbnailUrl(
        chapterUrl: String,
    ) = session().let { it.api.chapterCover(it.identity.chapter(chapterUrl).chapterId) }

    fun toSManga(entry: KavitaSeries, session: Session = session()) = SManga.create().apply {
        url = session.identity.series(entry.id)
        title = entry.localizedName.ifBlank { entry.name }
        thumbnail_url = session.api.cover(entry.id, entry.coverImage)
        memo = buildJsonObject { put("kavitaLibraryId", entry.libraryId) }
        initialized = false
    }
    fun toManga(entry: KavitaSeries, session: Session = session()) = Manga.create().copy(
        id = -entry.id,
        source = id,
        url = session.identity.series(entry.id),
        title = entry.localizedName.ifBlank {
            entry.name
        },
        thumbnailUrl = session.api.cover(entry.id, entry.coverImage),
        memo = buildJsonObject { put("kavitaLibraryId", entry.libraryId) },
    )
    suspend fun materialize(manga: Manga): Manga = materializeMutex.withLock {
        val session = session()
        session.identity.seriesId(manga.url)
        session.checkActive()
        mangas.getMangaByUrlAndSourceId(manga.url, id)
            ?: mangas.insertNetworkManga(listOf(manga.copy(id = -1))).single()
    }
    override suspend fun getMangaDetails(manga: SManga) = getMangaDetails(manga, false)
    override suspend fun getMangaDetails(manga: SManga, forceNetwork: Boolean): SManga {
        val session = session()
        val seriesId = session.identity.seriesId(manga.url)
        val entry = session.catalog.series(seriesId, forceNetwork)
        session.catalog.requireLibrary(entry.libraryId)
        val meta = session.catalog.metadata(seriesId, forceNetwork)
        return toSManga(entry, session).apply {
            author = meta.writers.joinToString { it.name }
            artist = meta.coverArtists.joinToString { it.name }
            description = meta.summary
            genre = (meta.genres + meta.tags).map { it.title }.distinct().joinToString()
            status =
                when (meta.publicationStatus) {
                    0 -> SManga.ONGOING
                    1 -> SManga.ON_HIATUS
                    2, 4 -> SManga.COMPLETED
                    3 -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            initialized = true
        }
    }
    override fun seriesActionsScreen(manga: Manga) = koharia.kavita.ui.KavitaSeriesScreen(
        id,
        session().identity.seriesId(manga.url),
    )
    override fun getMangaUrl(manga: SManga): String {
        val seriesId = session().identity.seriesId(manga.url)
        val libraryId = manga.memo["kavitaLibraryId"]?.jsonPrimitive?.longOrNull ?: return baseUrl
        return "$baseUrl/library/$libraryId/series/$seriesId"
    }
    override fun getChapterUrl(chapter: SChapter): String {
        val ref = session().identity.chapter(chapter.url)
        return "$baseUrl/library/${ref.libraryId}/series/${ref.seriesId}"
    }
    override fun detailsChapterTitle(chapterMemo: JsonObject): String? =
        koharia.kavita.KavitaChapterTitle.render(preferences.chapterTitleTemplate, chapterMemo)
    override fun detailsChapterNumber(chapterMemo: JsonObject): String? =
        chapterMemo["kavitaRange"]?.jsonPrimitive?.contentOrNull
    override fun detailsChapterFileName(chapterMemo: JsonObject): String? =
        chapterMemo["kavitaFilename"]?.jsonPrimitive?.contentOrNull
    override suspend fun getChapterList(manga: SManga) = getChapterList(manga, false)
    override suspend fun getChapterList(manga: SManga, forceNetwork: Boolean): List<SChapter> {
        val session = session()
        val series = session.catalog.series(session.identity.seriesId(manga.url), forceNetwork)
        session.catalog.requireLibrary(series.libraryId)
        val volumes = session.catalog.volumes(series.id, forceNetwork)
        val ordered = volumes.sortedBy { it.minNumber }.flatMap { volume ->
            volume.chapters.sortedWith(compareBy({ it.sortOrder }, { it.minNumber }, { it.id })).map { volume to it }
        }.distinctBy { it.second.id }
        return ordered.mapIndexed { index, (volume, chapter) ->
            val format =
                chapter.files.firstOrNull()?.format?.takeIf { it != 2 } ?: chapter.format.takeIf { it != 2 }
                    ?: series.format
            val ref = KavitaChapterRef(series.libraryId, series.id, volume.id, chapter.id, format)
            SChapter.create().apply {
                url = session.identity.chapter(ref)
                name = chapter.titleName.ifBlank { chapter.title }.ifBlank {
                    listOf(
                        volume.name,
                        chapter.range.ifBlank {
                            chapter.number
                        },
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                }.ifBlank {
                    chapter.files.firstOrNull()?.filePath?.substringAfterLast('/').orEmpty()
                }.ifBlank { chapter.id.toString() }
                // The index is only a legacy UI sort key. Remote identity and chapter ranges remain structured.
                chapter_number = (index + 1).toFloat()
                date_upload =
                    kavitaTimestamp(chapter.releaseDate).takeIf { it > 0 } ?: kavitaTimestamp(chapter.createdUtc)
                memo = buildJsonObject {
                    put("pagesCount", chapter.pages)
                    put("kavitaFormat", format)
                    put("kavitaVolume", volume.name)
                    put("kavitaRange", chapter.range.ifBlank { chapter.number })
                    put("kavitaTitle", chapter.titleName.ifBlank { chapter.title })
                    put(
                        "kavitaFilename",
                        chapter.files.firstOrNull()?.filePath
                            ?.replace('\\', '/')?.substringAfterLast('/').orEmpty(),
                    )
                    put("kavitaSpecial", chapter.isSpecial)
                    put("kavitaVersion", koharia.kavita.kavitaContentVersion(chapter))
                    put("kavitaFileCount", chapter.files.size)
                }
            }
        }.reversed()
    }
    override suspend fun getPageList(chapter: SChapter) = getConnectionPageList(chapter, false).pages
    override suspend fun getConnectionPageList(chapter: SChapter, forceNetwork: Boolean): ConnectionPageList {
        val session = session()
        val ref = session.identity.chapter(chapter.url)
        check(ref.format != 3)
        session.catalog.requireLibrary(ref.libraryId)
        val remote = session.catalog.chapter(ref.chapterId, forceNetwork)
        return ConnectionPageList(
            (0 until remote.pages).map {
                Page(
                    it,
                    chapter.url,
                    session.api.url(
                        "Reader/image",
                        "chapterId" to ref.chapterId,
                        "page" to it,
                        "extractPdf" to true,
                        "v" to koharia.kavita.kavitaContentVersion(remote),
                    ).toString(),
                )
            },
        )
    }
    override suspend fun getImage(page: Page): Response {
        val session = session()
        session.identity.chapter(page.url)
        return session.api.client.newCall(imageRequest(page)).await().also {
            try {
                KavitaApiClient.checkResponse(it)
            } catch (error: Exception) {
                it.close()
                throw error
            }
        }
    }
    override val rawDownloadClient get() = session().downloadClient
    override val resumePolicy = ConnectionRawDownloadResumePolicy.RESTART
    override suspend fun authorizeDownload(resourceUrl: String) {
        val session = session()
        session.identity.chapter(resourceUrl)
        if (!session.api.capabilities(
                session.api.getAccount(refresh = true),
            ).downloads
        ) {
            throw KavitaException(KavitaException.Reason.PERMISSION)
        }
    }
    override fun preferRawDownload(chapter: Chapter) = chapter.url.endsWith(".epub") ||
        (chapter.url.endsWith(".pdf") && (chapter.memo["kavitaFileCount"]?.jsonPrimitive?.longOrNull ?: 1) <= 1)
    override fun rawFileRequest(resourceUrl: String, rangeStart: Long?): Request {
        if (!preferences.capabilities.downloads) throw KavitaException(KavitaException.Reason.PERMISSION)
        return session().let {
            val ref = it.identity.chapter(resourceUrl)
            if (ref.format == 3) {
                it.api.request("Koharia/epub", "chapterId" to ref.chapterId)
            } else {
                it.api.rawRequest(ref.chapterId)
            }
        }
    }
    override suspend fun validateRawDownload(file: UniFile) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val signature = context.contentResolver.openInputStream(file.uri)?.use {
                ByteArray(4).also { bytes -> require(it.read(bytes) == 4) }
            } ?: throw java.io.IOException("Unreadable publication")
            if (signature.toString(Charsets.US_ASCII) == "%PDF") {
                KavitaPdfCache.validate(file)
            } else {
                var container = false
                var mime = false
                java.util.zip.ZipInputStream(context.contentResolver.openInputStream(file.uri)).use { zip ->
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 100_000)
                        if (entry.name == "META-INF/container.xml") container = true
                        if (entry.name == "mimetype") {
                            val bytes = koharia.kavita.boundedRead(zip, 128) {}
                            mime = bytes.toString(Charsets.US_ASCII) == "application/epub+zip"
                        }
                        if (container && mime) break
                    }
                }
                if (!container || !mime) throw java.io.IOException("Download is not a readable EPUB")
            }
        }
    }
    override fun isPdfChapter(chapterUrl: String) = session().identity.chapter(chapterUrl).format == 4
    private fun pdfKey(url: String) = session().identity.chapter(url).chapterId.toString()
    override fun findCompletePdfFile(chapterUrl: String) = session().let {
        it.identity.chapter(chapterUrl)
        it.pdf.find(pdfKey(chapterUrl))
    }
    override suspend fun preparePdfFile(chapterUrl: String): UniFile = session().let {
        it.catalog.chapter(it.identity.chapter(chapterUrl).chapterId)
        it.pdf.prepare(pdfKey(chapterUrl), it.identity.chapter(chapterUrl).chapterId, it.api, it::checkActive)
    }
    override suspend fun openRemotePublication(request: EpubOpenRequest, initialLocator: Locator?) =
        koharia.kavita.KavitaEpubPublicationService(session()).open(request, initialLocator)

    override suspend fun readingQueuePosition(context: String, chapterUrl: String) =
        koharia.kavita.KavitaReadingQueue(this, repository).position(context, chapterUrl)

    override suspend fun resolveReadingQueueChapter(context: String, chapterUrl: String) =
        koharia.kavita.KavitaReadingQueue(this, repository).resolve(context, chapterUrl)

    suspend fun createReadingQueue(title: String, items: List<koharia.kavita.KavitaListItem>) =
        koharia.kavita.KavitaReadingQueue(this, repository).create(title, items)

    override suspend fun resolvePublication(
        chapter: Chapter,
        allowRemoteLookup: Boolean,
    ): ConnectionPublicationMetadata {
        val session = session()
        val ref = session.identity.chapter(chapter.url)
        val version = if (allowRemoteLookup) {
            koharia.kavita.kavitaContentVersion(session.catalog.chapter(ref.chapterId))
        } else {
            session.catalog.contentVersion(ref.chapterId)
                ?: chapter.memo["kavitaVersion"]?.jsonPrimitive?.content.orEmpty()
        }
        return ConnectionPublicationMetadata(
            remoteResourceId = chapter.url.takeIf { ref.format == 3 },
            publicationKey = chapter.url + ":render-v2:" + version.encodeUtf8().sha256().hex(),
            isPageCompatible = ref.format != 3,
            fileName = "${ref.chapterId}.${ref.extension}",
            sizeBytes = null,
            mediaType = when (ref.format) {
                3 -> "application/epub+zip"
                4 -> "application/pdf"
                else -> null
            },
        )
    }
    override suspend fun getCachedEpubProgress(chapterId: Long): EpubRemoteProgressCache? {
        val chapter = chapters.getChapterById(chapterId) ?: return null
        val session = session()
        val ref = session.identity.chapter(chapter.url)
        if (ref.format != 3) return null
        val state = session.reading.cached(ref.chapterId) ?: return null
        return epubCache(chapter, state)
    }
    override suspend fun refreshEpubProgress(mangaId: Long, chapter: Chapter): EpubRemoteProgressCache? {
        val session = session()
        val ref = session.identity.chapter(chapter.url)
        if (ref.format != 3) return null
        return epubCache(chapter, session.reading.pull(ref, session.catalog.book(ref.chapterId).pages))
    }
    private fun epubCache(
        chapter: Chapter,
        state: KavitaReadingState,
    ): EpubRemoteProgressCache {
        val date = Date(kavitaTimestamp(state.progress.lastModifiedUtc))
        val locator = koharia.kavita.kavitaLocator(session().api, state)
        return EpubRemoteProgressCache(
            chapter.id, chapter.mangaId, chapter.url, locator.toJSON().toString(),
            state.progress.pageNum.toDouble() / state.totalPages.coerceAtLeast(1), null, date, Date(), date,
        )
    }
    override suspend fun pullEpubProgress(resourceId: String): RemoteEpubProgression {
        val session = session()
        val ref = session.identity.chapter(resourceId)
        val state = session.reading.pull(ref, session.catalog.book(ref.chapterId).pages)
        return RemoteEpubProgression(
            koharia.kavita.kavitaLocator(session.api, state),
            Date(kavitaTimestamp(state.progress.lastModifiedUtc)),
        )
    }
    override suspend fun pushEpubProgress(
        resourceId: String,
        locator: Locator,
        positions: List<Locator>,
        modifiedAt: Date,
    ) {
        session().reading.retryPending()
    }
    override suspend fun recordLocalEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        recordEpubProgress(resourceId, locator, modifiedAt, explicit = false)
    }
    override suspend fun confirmLocalEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        recordEpubProgress(resourceId, locator, modifiedAt, explicit = true)
    }
    override suspend fun acceptRemoteEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        val session = session()
        val ref = session.identity.chapter(resourceId)
        val total = session.reading.cached(ref.chapterId)?.totalPages ?: session.catalog.book(ref.chapterId).pages
        val page = koharia.kavita.KavitaEpubPublicationService.pageIndex(locator.href.toString()) ?: return
        session.reading.accept(ref, page, total, modifiedAt.time)
    }
    private suspend fun recordEpubProgress(
        resourceId: String,
        locator: Locator,
        modifiedAt: Date,
        explicit: Boolean,
    ) {
        val session = session()
        val ref = session.identity.chapter(resourceId)
        val page = koharia.kavita.KavitaEpubPublicationService.pageIndex(locator.href.toString()) ?: return
        val total = session.reading.cached(ref.chapterId)?.totalPages ?: session.catalog.book(ref.chapterId).pages
        val location = locator.toJSON().optJSONObject("locations")
        val anchor = location?.optString("kavitaXPath")?.takeIf(String::isNotBlank)
            ?: locator.locations.fragments.firstOrNull()
        val completed = page == total - 1 && (locator.locations.progression ?: 0.0) >= 0.99
        session.reading.record(
            ref,
            if (completed) total else page,
            total,
            modifiedAt.time,
            anchor = anchor,
            explicit = explicit,
        )
    }
    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) {
        session().let {
            it.reading.record(
                it.identity.chapter(chapterUrl),
                serverPage(pageIndex, totalPages),
                totalPages,
                readAt,
                initialPage,
            )
        }
    }
    override suspend fun pushPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) = session().reading.retryPending()
    override suspend fun pullPageProgress(chapterUrl: String, chapterMemo: JsonObject): ConnectionPageProgressSnapshot {
        val session = session()
        val ref = session.identity.chapter(chapterUrl)
        val chapter = session.catalog.chapter(ref.chapterId)
        val snapshot = session.reading.pull(ref, chapter.pages)
        val oldTotal = ConnectionChapterMetadata.pagesCount(chapterMemo)
        val previousVersion = chapterMemo["kavitaVersion"]?.jsonPrimitive?.contentOrNull
        val version = koharia.kavita.kavitaContentVersion(chapter)
        val memo = JsonObject(
            ConnectionChapterMetadata.withPagesCount(chapterMemo, chapter.pages) +
                ("kavitaVersion" to kotlinx.serialization.json.JsonPrimitive(version)),
        )
        return ConnectionPageProgressSnapshot(
            chapterUrl, snapshot.progress.pageNum.coerceAtMost((chapter.pages - 1).coerceAtLeast(0)),
            chapter.pages, snapshot.progress.pageNum >= chapter.pages && chapter.pages > 0,
            snapshot.progress.lastModifiedUtc, ref.format == 3, ref.format != 3,
            memo, previousVersion, version,
            requiresPageMappingConfirmation = (oldTotal != null && oldTotal != chapter.pages) ||
                (previousVersion != null && previousVersion != version),
        )
    }
    override suspend fun acceptRemotePageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) {
        session().let {
            it.reading.accept(it.identity.chapter(chapterUrl), serverPage(pageIndex, totalPages), totalPages, readAt)
        }
    }
    override suspend fun confirmLocalPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) {
        session().let {
            it.reading.record(
                it.identity.chapter(chapterUrl),
                serverPage(pageIndex, totalPages),
                totalPages,
                readAt,
                explicit = true,
            )
        }
    }
    override suspend fun setChapterReadStatus(chapterUrl: String, read: Boolean) {
        val session = session()
        val ref = session.identity.chapter(chapterUrl)
        val total = session.catalog.chapter(ref.chapterId).pages
        session.reading.record(
            ref,
            if (read) total else 0,
            total,
            System.currentTimeMillis(),
            explicit = true,
            unread = !read,
        )
    }
    override suspend fun syncMangaProgress(manga: Manga) {
        val session = session()
        session.identity.seriesId(manga.url)
        for (chapter in chapters.getChapterByMangaId(manga.id)) {
            val ref = session.identity.chapter(chapter.url)
            val remote = session.reading.pull(ref, ConnectionChapterMetadata.pagesCount(chapter.memo) ?: 0)
            if (!session.reading.hasPending(ref.chapterId)) applyState(session, remote)
        }
        session.reading.retryPending()
    }
    private suspend fun applyState(session: Session, state: KavitaReadingState) {
        session.checkActive()
        val manga = mangas.getMangaByUrlAndSourceId(session.identity.series(state.ref.seriesId), id) ?: return
        val chapter =
            chapters.getChapterByMangaId(manga.id).firstOrNull { it.url == session.identity.chapter(state.ref) }
                ?: return
        session.checkActive()
        chapters.updateAll(
            listOf(
                ChapterUpdate(
                    chapter.id,
                    read = state.totalPages > 0 && state.progress.pageNum >= state.totalPages,
                    lastPageRead = state.progress.pageNum.coerceIn(0, (state.totalPages - 1).coerceAtLeast(0)).toLong(),
                    memo = ConnectionChapterMetadata.withPagesCount(chapter.memo, state.totalPages),
                ),
            ),
        )
    }
    override val historyScopeChanges get() = epoch.map { Unit }
    override suspend fun historyMangaIds(): Set<Long> {
        val session = session()
        return mangas.getMangaBySourceId(id).filter { it.url.startsWith(session.prefix) }.mapTo(hashSetOf()) { it.id }
    }
    override suspend fun syncConnectionHistory() = historyMutex.withLock {
        val session = session()
        val remoteHistory = session.catalog.history(refresh = true)
        importHistorySnapshot(session, remoteHistory)
        for (manga in mangas.getMangaBySourceId(id).filter { it.url.startsWith(session.prefix) }) {
            syncMangaProgress(manga)
            for (chapter in chapters.getChapterByMangaId(manga.id)) {
                val state = session.reading.cached(session.identity.chapter(chapter.url).chapterId) ?: continue
                val timestamp = kavitaTimestamp(state.progress.lastModifiedUtc)
                if (state.progress.pageNum > 0 && timestamp > 0) {
                    session.checkActive()
                    Injekt.get<UpsertHistory>().awaitRemote(chapter.id, Date(timestamp))
                }
            }
        }
    }

    internal suspend fun importHistorySnapshot(
        session: Session,
        remoteHistory: List<koharia.kavita.KavitaHistoryEntry>,
    ) {
        session.checkActive()
        for ((seriesId, entries) in remoteHistory.groupBy { it.seriesId }) {
            session.checkActive()
            try {
                val series = session.catalog.series(seriesId)
                session.catalog.requireLibrary(series.libraryId)
                val manga = materialize(toManga(series))
                var local = chapters.getChapterByMangaId(manga.id)
                val known = local.map { session.identity.chapter(it.url).chapterId }.toSet()
                if (entries.any { it.chapterId !in known }) {
                    Injekt.get<eu.kanade.domain.chapter.interactor.SyncChaptersWithSource>().await(
                        getChapterList(manga.toSManga()),
                        manga,
                        this,
                    )
                    local = chapters.getChapterByMangaId(manga.id)
                }
                val byRemoteId = local.associateBy { session.identity.chapter(it.url).chapterId }
                for (entry in entries.filter { it.libraryId == series.libraryId }) {
                    val chapter = byRemoteId[entry.chapterId] ?: continue
                    if (entry.readAt <= 0) continue
                    session.checkActive()
                    Injekt.get<UpsertHistory>().awaitRemote(chapter.id, Date(entry.readAt))
                }
            } catch (failure: KavitaException) {
                // Deleted or revoked historical content must not prevent other authorized chapters importing.
                if (failure.status != 404 && failure.reason != KavitaException.Reason.PERMISSION) throw failure
            }
        }
    }
    override suspend fun prepareReadingStateRestore(chapterUrls: List<String>) = session().reading.prepareRestore()
    fun filter(media: List<Long>, query: String, order: String) = koharia.kavita.kavitaShelfFilter(media, query, order)
    private suspend fun listing(page: Int, query: String): MangasPage {
        val session = session()
        val libraries = session.catalog.libraries().filter { preferences.mediaId == 0L || it.id == preferences.mediaId }
        if (libraries.isEmpty()) return MangasPage(emptyList(), false)
        val result = session.catalog.page(page, filter(libraries.map { it.id }, query, preferences.order))
        return MangasPage(result.items.map { toSManga(it, session) }, result.hasNext)
    }
    override suspend fun getPopularManga(page: Int) = listing(page, "")
    override suspend fun getLatestUpdates(page: Int) = listing(page, "")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = listing(page, query)
    override fun fetchMangaDetails(
        manga: SManga,
    ): Observable<SManga> = Observable.fromCallable { runBlocking { getMangaDetails(manga) } }
    override fun fetchChapterList(
        manga: SManga,
    ): Observable<List<SChapter>> = Observable.fromCallable { runBlocking { getChapterList(manga) } }
    override fun fetchPageList(
        chapter: SChapter,
    ): Observable<List<Page>> = Observable.fromCallable { runBlocking { getPageList(chapter) } }
    private fun unsupported(): Nothing = error("Use the authenticated Kavita API")
    override fun popularMangaRequest(page: Int): Request = unsupported()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unsupported()
    override fun latestUpdatesRequest(page: Int): Request = unsupported()
    override fun popularMangaParse(response: Response): MangasPage = response.use { unsupported() }
    override fun searchMangaParse(response: Response): MangasPage = response.use { unsupported() }
    override fun latestUpdatesParse(response: Response): MangasPage = response.use { unsupported() }
    override fun mangaDetailsParse(response: Response): SManga = response.use { unsupported() }
    override fun chapterListParse(response: Response): List<SChapter> = response.use { unsupported() }
    override fun chapterPageParse(response: Response): SChapter = response.use { unsupported() }
    override fun pageListParse(response: Response): List<Page> = response.use { unsupported() }
    override fun imageUrlParse(response: Response): String = response.use { unsupported() }
}

internal fun serverPage(page: Int, total: Int): Int = if (total > 0 &&
    page >= total - 1
) {
    total
} else {
    page.coerceAtLeast(0)
}
