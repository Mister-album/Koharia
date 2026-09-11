package koharia.source.lanraragi

import android.content.Context
import androidx.preference.PreferenceScreen
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
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
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionHealthAdapter
import koharia.connection.ConnectionHistorySyncAdapter
import koharia.connection.ConnectionLibraryRefreshAdapter
import koharia.connection.ConnectionLibraryRefreshResult
import koharia.connection.ConnectionLocalPageProgressAdapter
import koharia.connection.ConnectionManagedLifecycle
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.connection.ConnectionMangaProgressAdapter
import koharia.connection.ConnectionPageAdapter
import koharia.connection.ConnectionPageList
import koharia.connection.ConnectionPageProgressAdapter
import koharia.connection.ConnectionPageProgressSnapshot
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.ConnectionReaderRoutingAdapter
import koharia.connection.ConnectionRestoreState
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.lanraragi.LanraragiApi
import koharia.lanraragi.LanraragiArchiveDto
import koharia.lanraragi.LanraragiCatalogSyncCoordinator
import koharia.lanraragi.LanraragiException
import koharia.lanraragi.LanraragiFilter
import koharia.lanraragi.LanraragiTimedBody
import koharia.lanraragi.acceptsLocalReading
import koharia.lanraragi.filterLanraragiCatalog
import koharia.lanraragi.flattenLanraragiTank
import koharia.lanraragi.localProgressWins
import koharia.lanraragi.shouldKeepLanraragiReading
import koharia.lanraragi.synchronizeLanraragiProgress
import koharia.lanraragi.ui.LanraragiLibraryScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.Date

data class LanraragiSyncStatus(
    val running: Boolean = false,
    val count: Int = 0,
    val completedAt: Long = 0,
    val error: Throwable? = null,
)

class LanraragiSource(
    private val context: Context,
    override val connectionProfile: LibraryConnectionProfile,
) : HttpSource(),
    ConfigurableSource,
    UnmeteredSource,
    ConnectionSource,
    ConnectionManagedLifecycle,
    ConnectionBrowseAdapter,
    ConnectionPageAdapter,
    ConnectionHealthAdapter,
    ConnectionAccountAdapter,
    ConnectionMangaBehaviorAdapter,
    ConnectionDownloadStorageAdapter,
    ConnectionLibraryRefreshAdapter,
    ConnectionReaderRoutingAdapter,
    ConnectionMangaProgressAdapter,
    ConnectionHistorySyncAdapter,
    ConnectionPageProgressAdapter,
    ConnectionLocalPageProgressAdapter,
    ConnectionReadStatusAdapter,
    ConnectionBackupRestoreAdapter,
    AutoCloseable {

    override val id = connectionProfile.id
    override val name = connectionProfile.name
    override val lang = "other"
    override val supportsLatest = true
    val preferences = LanraragiPreferences(id)
    private val json: Json = Injekt.get()
    val repository: LanraragiRepository = Injekt.get()
    private val mangaRepository: MangaRepository by lazy { Injekt.get() }
    private val chapterRepository: ChapterRepository by lazy { Injekt.get() }
    private var lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogMutex = Injekt.get<LanraragiCatalogSyncCoordinator>().mutexFor(id)
    private val materializeMutex = Mutex()
    private val progressMutex = Mutex()
    private val progressSyncMutex = Mutex()
    private val readingEpoch = java.util.concurrent.atomic.AtomicLong()
    private var syncJob: Job? = null
    private var flushJob: Job? = null

    @Volatile private var flushRequested = false

    @Volatile private var closed = false

    @Volatile private var registered = false

    @Volatile private var apiInstance: LanraragiApi? = null
    val status = MutableStateFlow(LanraragiSyncStatus())
    private val refreshes = MutableSharedFlow<ConnectionLibraryRefreshResult>(replay = 1)
    override val libraryRefreshes = refreshes.asSharedFlow()
    private val networkMonitor: koharia.lanraragi.LanraragiNetworkMonitor = Injekt.get()
    private var coverCatalog: List<LanraragiEntry>? = null
    private var coverEntries = emptyMap<String, LanraragiEntry>()
    val networkAvailable = MutableStateFlow(networkMonitor.available.value)

    @Volatile private var lastRefreshAttempt = 0L
    private fun observeNetwork() {
        lifetime.launch {
            networkMonitor.available.collect { available ->
                networkAvailable.value = available
                if (available) retryPending()
            }
        }
    }

    @Synchronized
    override fun onRegistered() {
        if (closed || registered) return
        registered = true
        observeNetwork()
        lifetime.launch {
            delay(1000)
            if (hasValidConnection()) {
                retryPending()
                if (lastRefreshAttempt == 0L) startRefresh()
            }
        }
    }

    private var apiAddress: String? = null
    private var apiCredential: String? = null
    val api: LanraragiApi get() = synchronized(this) {
        val address = preferences.address
        val credential = preferences.apiKey
        if (apiAddress != address || apiCredential != credential) {
            apiInstance?.close()
            apiInstance = null
        }
        apiInstance ?: LanraragiApi(address, credential, network.client, json).also {
            apiAddress = address
            apiCredential = credential
            apiInstance = it
        }
    }
    override val baseUrl: String get() = preferences.address.trimEnd('/')
    override val client get() = api.imageClient
    override val pageLoadConcurrency = 2
    override val preserveDownloadPageBoundaries = true
    override val allowsUnvalidatedNetwork = true
    override val usesSharedDownloadStorage = false
    override val mangaBehavior = ConnectionMangaBehavior(
        providerManagedLibrary = true,
        allowsLocalLibraryManagement = false,
        allowsCategoryManagement = false,
        allowsFetchIntervalManagement = false,
    )

    override fun shouldRefreshChapters(manga: Manga, nowMillis: Long): Boolean = manga.url.contains("/tank/")

    fun reload() {
        if (closed) return
        lifetime.cancel()
        apiInstance?.close()
        apiInstance = null
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        syncJob = null
        flushJob = null
        if (registered) {
            observeNetwork()
            startRefresh()
            retryPending()
        }
    }

    override fun close() {
        closed = true
        lifetime.cancel()
        apiInstance?.close()
    }

    suspend fun removeLocalConnectionState() {
        close()
        catalogMutex.withLock { progressMutex.withLock { repository.remove(id) } }
    }

    override fun hasValidConnection() = runCatching { LanraragiApi.normalizeBase(preferences.address) }.isSuccess
    override suspend fun isConnectionReachable() = runCatching { api.serverInfo(true) }.isSuccess
    override suspend fun getAccount(): ConnectionAccount? {
        if (!hasValidConnection()) return null
        return ConnectionAccount("LANraragi ${api.serverInfo().version}")
    }
    override fun availableContentScopes() = setOf(LibraryContentScope.COMIC)
    override suspend fun readerContentScope(manga: Manga, chapter: Chapter) = LibraryContentScope.COMIC
    override fun createBrowseScreen(scope: LibraryContentScope, listingQuery: String?, showNavigationUp: Boolean) =
        LanraragiLibraryScreen(id, listingQuery, showNavigationUp)
    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit
    override fun downloadDirectoryName() = "LANraragi_$id"
    override fun downloadDirectoryNames() = listOf(downloadDirectoryName())
    override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())
    override fun legacyDownloadDirectoryNames() = emptyList<String>()

    fun resourceUrl(entry: LanraragiEntry) = "/lanraragi/$id/${entry.kind.name.lowercase()}/${entry.id}"
    fun archiveId(url: String): String {
        require(url.startsWith("/lanraragi/$id/")) { "Resource belongs to another connection" }
        return url.substringAfterLast('/')
    }

    fun toSManga(entry: LanraragiEntry, entries: List<LanraragiEntry> = emptyList()): SManga = SManga.create().apply {
        url = resourceUrl(entry)
        title = entry.title
        description = entry.summary
        genre = entry.tagList.joinToString()
        artist = entry.tagList.filter { it.startsWith("artist:") }.joinToString { it.substringAfter(':') }
        author =
            entry.tagList.filter {
                it.startsWith("group:")
            }.joinToString { it.substringAfter(':') }.ifEmpty { artist.orEmpty() }
        status = if (entry.kind == LanraragiEntry.Kind.ARCHIVE) SManga.COMPLETED else SManga.UNKNOWN
        thumbnail_url = thumbnail(entry, entries)
        initialized = true
    }

    fun thumbnail(entry: LanraragiEntry, entries: List<LanraragiEntry>): String? {
        if (!hasValidConnection()) return null
        if (entry.kind == LanraragiEntry.Kind.TANK && api.hasTankThumbnails) {
            return api.url("api/tankoubons/${entry.id}/thumbnail").toString()
        }
        val byId = synchronized(this) {
            if (entries !== coverCatalog) {
                coverCatalog = entries
                coverEntries = entries.associateBy { it.id }
            }
            coverEntries
        }
        val archive = if (entry.kind == LanraragiEntry.Kind.TANK) {
            flattenLanraragiTank(entry, byId).firstOrNull { it.id in byId }?.id
        } else {
            entry.id
        }
        return archive?.let { api.url("api/archives/$it/thumbnail").toString() }
    }

    fun toManga(entry: LanraragiEntry, entries: List<LanraragiEntry>, index: Int = 0): Manga {
        val remote = toSManga(entry, entries)
        return Manga.create().copy(
            id = -(index + 1L), source = id, url = remote.url, title = remote.title, artist = remote.artist,
            author = remote.author, description = remote.description, genre = entry.tagList,
            status = remote.status.toLong(), thumbnailUrl = remote.thumbnail_url, initialized = true,
        )
    }

    suspend fun materialize(entry: LanraragiEntry): Manga = materializeMutex.withLock {
        val all = repository.entries(id)
        val existing = mangaRepository.getMangaByUrlAndSourceId(resourceUrl(entry), id)
        if (existing ==
            null
        ) {
            return@withLock mangaRepository.insertNetworkManga(listOf(toManga(entry, all).copy(id = -1))).single()
        }
        Injekt.get<eu.kanade.domain.manga.interactor.UpdateManga>()
            .awaitUpdateFromSource(existing, toSManga(entry, all), manualFetch = false)
        mangaRepository.getMangaById(existing.id)
    }

    private suspend fun entry(url: String): LanraragiEntry {
        val resourceId = archiveId(url)
        repository.entries(id).firstOrNull { it.id == resourceId }?.let { return it }
        if (repository.lastSync(id) > 0) {
            val manga = mangaRepository.getMangaByUrlAndSourceId(url, id)
            if (manga != null) {
                return LanraragiEntry(
                    resourceId,
                    if (url.contains("/tank/")) LanraragiEntry.Kind.TANK else LanraragiEntry.Kind.ARCHIVE,
                    manga.title,
                    manga.genre.orEmpty().joinToString(),
                    manga.description.orEmpty() + "\n\n" +
                        context.stringResource(MR.strings.lanraragi_error_unavailable),
                )
            }
        }
        return if (url.contains("/tank/")) api.tank(resourceId) else api.archive(resourceId)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = toSManga(entry(manga.url), repository.entries(id))
    override fun getMangaUrl(manga: SManga): String {
        val builder = api.url("reader").newBuilder()
        return builder.addQueryParameter("id", archiveId(manga.url)).build().toString()
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val all = repository.entries(id)
        val entry = entry(manga.url)
        val archives = if (entry.kind == LanraragiEntry.Kind.TANK) {
            flattenLanraragiTank(entry, all.associateBy { it.id })
        } else {
            listOf(entry)
        }
        // Keep downloaded/history-bearing chapters when remote membership changes.
        val local = mangaRepository.getMangaByUrlAndSourceId(manga.url, id)?.let {
            chapterRepository.getChapterByMangaId(it.id)
        }.orEmpty()
        val byUrl = local.associateBy { it.url }
        val chapters = archives.mapIndexed { index, archive ->
            chapter(archive, index).apply {
                byUrl[url]?.memo?.get(UNREAD_AT)?.let { unreadAt ->
                    memo =
                        buildJsonObject {
                            memo.forEach { (key, value) -> put(key, value) }
                            put(UNREAD_AT, unreadAt)
                        }
                }
            }
        }.reversed()
        return chapters + local.filter { old -> chapters.none { it.url == old.url } }.map { old ->
            SChapter.create().apply {
                url = old.url
                name = old.name
                chapter_number = old.chapterNumber.toFloat()
                date_upload =
                    old.dateUpload
                memo = old.memo
            }
        }
    }

    private fun chapter(entry: LanraragiEntry, index: Int) = SChapter.create().apply {
        url = resourceUrl(entry.copy(kind = LanraragiEntry.Kind.ARCHIVE))
        name =
            if (entry.available) entry.title else context.stringResource(MR.strings.lanraragi_missing_member, entry.id)
        chapter_number = index + 1f
        date_upload = entry.addedAt * 1000
        memo = ConnectionChapterMetadata.withPagesCount(JsonObject(emptyMap()), entry.pageCount)
    }

    override suspend fun getPageList(chapter: SChapter) = getConnectionPageList(chapter, false).pages
    override suspend fun getConnectionPageList(chapter: SChapter, forceNetwork: Boolean): ConnectionPageList {
        val started = System.nanoTime()
        val pages = api.pages(archiveId(chapter.url))
        logcat {
            "LanraragiStartup: sourceId=$id phase=files pages=${pages.size} " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000}"
        }
        if (pages.isEmpty()) throw LanraragiException(LanraragiException.Reason.EMPTY)
        return ConnectionPageList(pages.mapIndexed { index, url -> Page(index, imageUrl = url) })
    }

    override suspend fun getImage(page: Page): Response {
        val started = System.nanoTime()
        val response = api.readerClient.newCachelessCallWithProgress(imageRequest(page), page).awaitSuccess()
        val headersMillis = (System.nanoTime() - started) / 1_000_000
        return response.newBuilder().body(
            LanraragiTimedBody(response.body) { bytes, complete ->
                logcat {
                    "LanraragiStartup: sourceId=$id phase=image page=${page.index + 1} bytes=$bytes " +
                        "complete=$complete headersMs=$headersMillis elapsedMs=${(System.nanoTime() - started) / 1_000_000}"
                }
            },
        ).build()
    }
    override fun decoratePageImageUrls(pages: List<Page>, chapterMemo: JsonObject) = pages.map { page ->
        val image = page.imageUrl ?: return@map page
        val apiPath = image.substringAfter("/api/archives/", "")
        if (apiPath.isEmpty()) {
            page
        } else {
            Page(
                page.index,
                page.url,
                api.url("api/archives/" + apiPath.substringBefore('?'))
                    .newBuilder().encodedQuery(image.substringAfter('?', "").ifEmpty { null }).build().toString(),
            )
        }
    }

    override suspend fun getPopularManga(page: Int) = localPage(page, LanraragiFilter())
    override suspend fun getLatestUpdates(page: Int) = localPage(page, LanraragiFilter(sort = 1, descending = true))
    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ) = localPage(page, LanraragiFilter(query = query))
    private suspend fun localPage(page: Int, filter: LanraragiFilter): MangasPage {
        val entries = repository.entries(id)
        val items = filterLanraragiCatalog(entries, repository.readStates(id), filter)
        val start = (page - 1).coerceAtLeast(0) * 50
        return MangasPage(items.drop(start).take(50).map { toSManga(it, entries) }, start + 50 < items.size)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        runBlocking { getMangaDetails(manga) }
    }
    override fun fetchChapterList(
        manga: SManga,
    ): Observable<List<SChapter>> = Observable.fromCallable { runBlocking { getChapterList(manga) } }
    override fun fetchPageList(
        chapter: SChapter,
    ): Observable<List<Page>> = Observable.fromCallable { runBlocking { getPageList(chapter) } }
    override fun popularMangaRequest(page: Int) = api.request("api/archives")
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ) = Request.Builder().url(api.url("api/search").newBuilder().addQueryParameter("filter", query).build()).build()
    override fun popularMangaParse(response: Response): MangasPage = response.use {
        MangasPage(
            json.decodeFromString<List<LanraragiArchiveDto>>(it.body.string()).map { dto ->
                toSManga(dto.entry())
            },
            false,
        )
    }
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response): MangasPage = response.use {
        val data = json.parseToJsonElement(it.body.string()).jsonObject["data"]?.jsonArray.orEmpty()
        MangasPage(
            data.map { dto ->
                toSManga(json.decodeFromJsonElement(LanraragiArchiveDto.serializer(), dto).entry())
            },
            false,
        )
    }
    override fun mangaDetailsRequest(manga: SManga) = api.request("api/archives/${archiveId(manga.url)}/metadata")
    override fun mangaDetailsParse(
        response: Response,
    ): SManga = response.use { toSManga(json.decodeFromString<LanraragiArchiveDto>(it.body.string()).entry()) }
    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response) = listOf(chapterPageParse(response))
    override fun chapterPageParse(
        response: Response,
    ): SChapter = response.use { chapter(json.decodeFromString<LanraragiArchiveDto>(it.body.string()).entry(), 0) }
    override fun pageListRequest(chapter: SChapter) = api.request("api/archives/${archiveId(chapter.url)}/files")
    override fun pageListParse(response: Response): List<Page> = response.use {
        json.parseToJsonElement(it.body.string()).jsonObject["pages"]?.jsonArray.orEmpty().mapIndexed { index, value ->
            Page(index, imageUrl = api.imageUrl(value.jsonPrimitive.content))
        }
    }
    override fun imageUrlParse(response: Response): String = response.use { it.request.url.toString() }

    @Synchronized
    fun startRefresh() {
        if (closed || syncJob?.isActive == true || !hasValidConnection()) return
        lastRefreshAttempt = System.currentTimeMillis()
        syncJob = lifetime.launch { refreshLibrary() }
    }

    suspend fun refreshIfStale() {
        val last = repository.lastSync(id)
        status.value = status.value.copy(completedAt = last)
        val now = System.currentTimeMillis()
        if (networkAvailable.value && now - lastRefreshAttempt >= 60_000 &&
            (preferences.address != preferences.indexedAddress || now - last >= 60_000)
        ) {
            startRefresh()
        }
    }

    override suspend fun refreshLibrary(): Result<ConnectionLibraryRefreshResult> = withContext(Dispatchers.IO) {
        refreshLibraryOnIO()
    }

    private suspend fun refreshLibraryOnIO(): Result<ConnectionLibraryRefreshResult> = catalogMutex.withLock {
        if (closed) throw CancellationException("Connection removed")
        lastRefreshAttempt = System.currentTimeMillis()
        val generation = maxOf(System.currentTimeMillis(), repository.lastSync(id) + 1)
        val activeApi = api
        logcat { "LanraragiCatalog: start connectionId=$id generation=$generation" }
        status.value = status.value.copy(running = true, count = 0, error = null)
        try {
            activeApi.serverInfo(true)
            repository.begin(id, generation)
            val untagged = activeApi.untagged()
            var count = 0
            activeApi.streamArchives { entries ->
                repository.stage(id, generation, entries.map { it.copy(untagged = it.id in untagged) })
                count += entries.size
                status.value = status.value.copy(count = count)
            }
            repository.stage(id, generation, activeApi.tanks())
            val categories = activeApi.categories().map { category ->
                if (category.search.isBlank()) {
                    category
                } else {
                    category.copy(
                        members = activeApi.search(category = category.id).map {
                            it.id
                        },
                    )
                }
            }
            repository.stage(id, generation, categories)
            val completedAt = System.currentTimeMillis()
            repository.publish(id, generation, completedAt)
            logcat { "LanraragiCatalog: published connectionId=$id generation=$generation archives=$count" }
            preferences.markIndexed(activeApi.base.toString())
            status.value = LanraragiSyncStatus(count = count, completedAt = completedAt)
            val result = ConnectionLibraryRefreshResult(count, completedAt)
            refreshes.emit(result)
            retryPending()
            Result.success(result)
        } catch (error: Throwable) {
            withContext(NonCancellable) { repository.abort(id, generation) }
            status.value = status.value.copy(running = false, error = error.takeUnless { it is CancellationException })
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    @Synchronized
    fun retryPending() {
        if (!registered || closed || ConnectionRestoreState.isRestoring || !hasValidConnection()) return
        flushRequested = true
        if (flushJob?.isActive == true) return
        flushJob = lifetime.launch {
            try {
                delay(1000)
                var failures = 0
                while (flushRequested && failures < 3) {
                    flushRequested = false
                    try {
                        flushProgress()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        status.value = status.value.copy(error = error)
                        failures++
                        if (failures < 3) {
                            delay(failures * 5000L)
                            flushRequested = true
                        }
                    }
                }
            } finally {
                synchronized(this@LanraragiSource) {
                    flushJob = null
                    if (flushRequested && !closed) retryPending()
                }
            }
        }
    }

    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) {
        if (closed || ConnectionRestoreState.isRestoring || pageIndex < 0 || totalPages <= 0 ||
            pageIndex >= totalPages
        ) {
            return
        }
        progressMutex.withLock {
            if (closed || ConnectionRestoreState.isRestoring) return
            val previous = repository.readStates(id).firstOrNull { it.archiveId == archiveId(chapterUrl) }
            if (initialPage && previous?.pending == true) return
            if (!acceptsLocalReading(previous, readAt)) return
            val state = LanraragiReadState(
                archiveId(chapterUrl),
                pageIndex,
                totalPages,
                readAt,
                initialPage = initialPage && previous?.localUnread != true,
            )
            repository.record(id, state)
            applyState(state)
        }
        retryPending()
    }

    private suspend fun restoreUnreadOverrides() {
        val known = repository.readStates(id).associateBy { it.archiveId }
        val mangas = mangaRepository.getMangaBySourceId(id)
        chapterRepository.getChaptersByMangaIds(mangas.map { it.id }).forEach { chapter ->
            val unreadAt = chapter.memo[UNREAD_AT]?.jsonPrimitive?.longOrNull ?: return@forEach
            val archiveId = archiveId(chapter.url)
            if (known[archiveId] ==
                null
            ) {
                repository.record(
                    id,
                    LanraragiReadState(
                        archiveId,
                        0,
                        ConnectionChapterMetadata.pagesCount(chapter.memo) ?: 0,
                        unreadAt,
                        localUnread = true,
                        pending = false,
                    ),
                )
            }
        }
    }

    private suspend fun flushProgress() = progressSyncMutex.withLock {
        if (ConnectionRestoreState.isRestoring || closed) return@withLock
        progressMutex.withLock { restoreUnreadOverrides() }
        val activeApi = api
        val epoch = readingEpoch.get()
        synchronizeLanraragiProgress(
            id,
            repository,
            activeApi::archive,
            activeApi::pushProgress,
            activeApi::clearNew,
            ::applyState,
            stateMutex = progressMutex,
            isCurrent = {
                !closed && !ConnectionRestoreState.isRestoring && readingEpoch.get() == epoch &&
                    api === activeApi
            },
        )
    }

    override suspend fun prepareReadingStateRestore(chapterUrls: List<String>) {
        val archiveIds = chapterUrls.filter { it.startsWith("/lanraragi/$id/archive/") }.map(::archiveId)
        progressMutex.withLock {
            readingEpoch.incrementAndGet()
            repository.resetReadStates(id, archiveIds)
        }
    }

    override suspend fun pushPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int) {
        retryPending()
    }

    override suspend fun pullPageProgress(
        chapterUrl: String,
        chapterMemo: JsonObject,
    ): ConnectionPageProgressSnapshot? {
        val archiveId = archiveId(chapterUrl)
        val (before, epoch) = progressMutex.withLock {
            if (ConnectionRestoreState.isRestoring || closed) return null
            restoreUnreadOverrides()
            repository.readStates(id).firstOrNull { it.archiveId == archiveId } to readingEpoch.get()
        }
        val activeApi = api
        val remote = if (before?.localUnread == true) null else activeApi.archive(archiveId)
        val state = progressMutex.withLock {
            if (ConnectionRestoreState.isRestoring || closed || epoch != readingEpoch.get() ||
                api !== activeApi
            ) {
                return null
            }
            val local = repository.readStates(id).firstOrNull { it.archiveId == archiveId }
            when {
                local != before -> local ?: return null
                local?.localUnread == true -> local
                local?.pending == true && remote != null && shouldKeepLanraragiReading(local, remote) -> local
                remote != null -> remote.readState().also {
                    repository.record(id, it)
                    applyState(it)
                }
                else -> return null
            }
        }
        return ConnectionPageProgressSnapshot(
            resourceId = archiveId, pageIndex = state.pageIndex.takeIf { it >= 0 }, totalPages = state.totalPages,
            completed = !state.localUnread && state.totalPages > 0 && state.pageIndex == state.totalPages - 1,
            readDate = Instant.ofEpochMilli(state.readAt).toString(), isEpub = false, canOpenAsPages = false,
            updatedChapterMemo = ConnectionChapterMetadata.withPagesCount(chapterMemo, state.totalPages),
            previousPublicationVersion = null, publicationVersion = null,
        )
    }

    override suspend fun setChapterReadStatus(chapterUrl: String, read: Boolean) {
        val archiveId = archiveId(chapterUrl)
        progressMutex.withLock {
            val entry = repository.entries(id).firstOrNull { it.id == archiveId }
            val prior = repository.readStates(id).firstOrNull { it.archiveId == archiveId }
            val count = entry?.pageCount?.takeIf { it > 0 } ?: prior?.totalPages ?: 0
            val state = LanraragiReadState(
                archiveId,
                if (read) (count - 1).coerceAtLeast(0) else 0,
                count,
                System.currentTimeMillis(),
                localUnread = !read,
                pending = read,
            )
            repository.record(id, state)
            applyState(state)
        }
        if (read) {
            retryPending()
        } else {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    context.stringResource(MR.strings.lanraragi_unread_local),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun LanraragiEntry.readState() = LanraragiReadState(
        id,
        (progress - 1).coerceAtLeast(-1),
        pageCount,
        lastRead,
        pending = false,
    )

    private suspend fun applyState(state: LanraragiReadState) {
        val chapters = chapterRepository.getChaptersByUrlAndSourceId("/lanraragi/$id/archive/${state.archiveId}", id)
        chapterRepository.updateAll(
            chapters.map { chapter ->
                ChapterUpdate(
                    chapter.id,
                    read = !state.localUnread && state.totalPages > 0 && state.pageIndex >= state.totalPages - 1,
                    lastPageRead = state.pageIndex.coerceAtLeast(0).toLong(),
                    memo = buildJsonObject {
                        chapter.memo.filterKeys { it != UNREAD_AT }.forEach { (key, value) -> put(key, value) }
                        if (state.localUnread) put(UNREAD_AT, state.readAt)
                        if (state.totalPages > 0) put("pagesCount", state.totalPages)
                    },
                )
            },
        )
    }

    override suspend fun syncMangaProgress(manga: Manga) {
        if (ConnectionRestoreState.isRestoring || closed) return
        for (chapter in chapterRepository.getChapterByMangaId(manga.id)) {
            val skip = progressMutex.withLock {
                restoreUnreadOverrides()
                val state = repository.readStates(id).firstOrNull { it.archiveId == archiveId(chapter.url) }
                if (state != null) applyState(state)
                state?.localUnread == true || state?.pending == true
            }
            if (!skip) pullPageProgress(chapter.url, chapter.memo)
        }
    }

    override suspend fun syncConnectionHistory() {
        if (ConnectionRestoreState.isRestoring) return
        refreshLibrary().getOrThrow()
        flushProgress()
        val entries = repository.entries(id)
        for (entry in entries.filter { it.kind == LanraragiEntry.Kind.ARCHIVE && it.lastRead > 0 }) {
            progressMutex.withLock {
                val local = repository.readStates(id).firstOrNull { it.archiveId == entry.id }
                if (local?.localUnread == true || local?.pending == true ||
                    (local?.readAt ?: 0) > entry.lastRead
                ) {
                    return@withLock
                }
                var aliases = chapterRepository.getChaptersByUrlAndSourceId(resourceUrl(entry), id)
                if (aliases.isEmpty()) {
                    val manga = materialize(entry)
                    Injekt.get<SyncChaptersWithSource>().await(getChapterList(manga.toSManga()), manga, this)
                    aliases = chapterRepository.getChapterByMangaId(manga.id)
                }
                val histories = aliases.map {
                    it.mangaId
                }.distinct().flatMap { Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().await(it) }
                    .filter { history -> aliases.any { it.id == history.chapterId } }
                val newest = histories.maxByOrNull { it.readAt?.time ?: 0 }
                val state = entry.readState()
                repository.record(id, state)
                applyState(state)
                val chapterId = newest?.chapterId ?: aliases.firstOrNull()?.id
                if (chapterId != null && (newest?.readAt?.time ?: 0) < entry.lastRead) {
                    Injekt.get<UpsertHistory>().await(HistoryUpdate(chapterId, Date(entry.lastRead), 0))
                }
            }
        }
    }

    companion object {
        const val UNREAD_AT = "lanraragiUnreadAt"
    }
}
