package koharia.source.smanga

import android.content.Context
import androidx.preference.PreferenceScreen
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
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
import koharia.connection.ConnectionAddressRouter
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
import koharia.connection.ConnectionPdfFileAdapter
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.ConnectionRawDownloadResumePolicy
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.ConnectionReaderRoutingAdapter
import koharia.connection.ConnectionRestoreState
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import koharia.smanga.SmangaApi
import koharia.smanga.SmangaCatalog
import koharia.smanga.SmangaChapter
import koharia.smanga.SmangaException
import koharia.smanga.SmangaManga
import koharia.smanga.SmangaPdfCache
import koharia.smanga.SmangaReadingCoordinator
import koharia.smanga.checkedSmangaImageResponse
import koharia.smanga.selectSmangaHistoryState
import koharia.smanga.ui.SmangaLibraryScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
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

class SmangaSource(private val context: Context, override val connectionProfile: LibraryConnectionProfile) :
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
    AutoCloseable {
    override val id = connectionProfile.id
    override val name = connectionProfile.name
    override val lang = "other"
    override val supportsLatest = true
    val preferences = SmangaPreferences(id)
    val epoch = MutableStateFlow(0L)
    val instanceKey: String = java.util.UUID.randomUUID().toString()
    override val historyScopeChanges get() = epoch.map { Unit }
    override suspend fun historyMangaIds(): Set<Long> {
        val session = session()
        val ids = mangas.getMangaBySourceId(id).filter {
            it.url.startsWith(session.prefix)
        }.mapTo(hashSetOf()) { it.id }
        session.checkActive()
        return ids
    }
    private val json: Json = Injekt.get()
    private val repository: SmangaRepository = Injekt.get()
    private val mangas: MangaRepository by lazy { Injekt.get() }
    private val chapters: ChapterRepository by lazy { Injekt.get() }
    private val materializeMutex = Mutex()

    @Volatile private var active: Session? = null

    @Volatile private var closed = false
    private var registered = false

    inner class Session internal constructor(val accountKey: String) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val publicAddress = preferences.address
        private val internalAddress = preferences.internalAddress
        val api = SmangaApi(
            network.client,
            json,
            publicAddress,
            preferences.username,
            preferences.password,
            "$id-$accountKey",
            addressRouter = ConnectionAddressRouter.forAndroid(
                context,
                { publicAddress },
                { internalAddress },
                "deploy/status",
                authenticateProbe = false,
            ),
        )
        val catalog = SmangaCatalog(id, accountKey, repository, api, json, ::checkActive)
        val pdf = SmangaPdfCache(context, id, accountKey)
        val reading =
            SmangaReadingCoordinator(id, accountKey, api, repository, scope, ::checkActive) { applyState(this, it) }
        val prefix = "/smanga/$id/$accountKey/"
        fun checkActive() {
            if (closed || active !== this) throw CancellationException("Connection session changed")
        }
        fun mangaUrl(remoteId: Long) = "${prefix}manga/$remoteId"
        fun chapterUrl(chapter: SmangaChapter): String {
            val extension = if (chapter.format.equals("pdf", ignoreCase = true)) "pdf" else "pages"
            return "${prefix}chapter/${chapter.mediaId}/${chapter.mangaId}/${chapter.id}.$extension"
        }
        fun mangaId(url: String): Long {
            require(url.startsWith("${prefix}manga/")) { "Manga belongs to another account" }
            return url.substringAfterLast('/').toLong().also { require(it > 0) }
        }
        fun chapterId(url: String): Long {
            require(url.startsWith("${prefix}chapter/")) { "Chapter belongs to another account" }
            return url.substringAfterLast('/').substringBefore('.').toLong().also { require(it > 0) }
        }
        fun chapterFromUrl(url: String): SmangaChapter {
            val chapterId = chapterId(url)
            val pieces = url.removePrefix("${prefix}chapter/").split('/')
            require(pieces.size == 3)
            return SmangaChapter(
                chapterId,
                pieces[1].toLong(),
                pieces[0].toLong(),
                "",
                format = if (url.endsWith(".pdf")) "pdf" else "",
            )
        }
        override fun close() {
            scope.cancel()
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
        if (registered && hasValidConnection()) observeReading(session())
    }
    override fun close() {
        closed = true
        active?.close()
        active = null
    }

    @Synchronized override fun onRegistered() {
        if (registered || closed) return
        registered = true
        if (hasValidConnection()) observeReading(session())
    }
    private fun observeReading(session: Session) {
        session.scope.launch {
            Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.collect { available ->
                if (available) session.reading.retryPending()
            }
        }
    }
    override val baseUrl get() = preferences.address.trimEnd('/')
    override val client get() = session().api.opdsClient
    override val pageLoadConcurrency = 2
    override val pagePrefetchOnActivate = false
    override val pagePrefetchSize = 2
    override val preserveDownloadPageBoundaries = true
    override val allowsUnvalidatedNetwork = true
    override val usesSharedDownloadStorage = false
    override val mangaBehavior =
        ConnectionMangaBehavior(
            allowsTagSearch = false,
            providerManagedLibrary = true,
            allowsLocalLibraryManagement = false,
            allowsCategoryManagement = false,
            allowsFetchIntervalManagement = false,
            supportsChapterCoverGrid = true,
        )
    override fun hasValidConnection() =
        preferences.username.isNotBlank() && runCatching { SmangaApi.normalizeBase(preferences.address) }.isSuccess
    override suspend fun isConnectionReachable() = runCatching { session().api.account() }.isSuccess
    override suspend fun getAccount() = session().api.account().let {
        ConnectionAccount(it.nickname.ifBlank { it.userName }, listOf(it.role))
    }
    override fun availableContentScopes() = setOf(LibraryContentScope.COMIC)
    override suspend fun readerContentScope(manga: Manga, chapter: Chapter) = LibraryContentScope.COMIC
    override fun createBrowseScreen(
        scope: LibraryContentScope,
        listingQuery: String?,
        showNavigationUp: Boolean,
    ) = SmangaLibraryScreen(id, listingQuery, showNavigationUp)
    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit
    override fun downloadDirectoryName() = "Smanga_${id}_${preferences.accountKey}"
    override fun downloadDirectoryNames() = listOf(downloadDirectoryName())
    override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())
    override fun legacyDownloadDirectoryNames() = emptyList<String>()
    override fun chapterThumbnailUrl(chapterUrl: String) =
        session().let { it.api.chapterCoverUrl(it.chapterId(chapterUrl)) }

    fun toSManga(entry: SmangaManga, session: Session = session()) = SManga.create().apply {
        url = session.mangaUrl(entry.id)
        title = entry.name
        author = entry.author
        description = entry.description
        genre = entry.tags.joinToString()
        status = SManga.UNKNOWN
        thumbnail_url = session.api.coverUrl(entry.id)
        initialized = true
    }
    fun toManga(entry: SmangaManga, session: Session = session()): Manga {
        val remote = toSManga(entry, session)
        return Manga.create().copy(
            id = -entry.id, source = id, url = remote.url, title = remote.title,
            author = remote.author, description = remote.description, genre = entry.tags,
            thumbnailUrl = remote.thumbnail_url, initialized = false,
        )
    }
    suspend fun materialize(manga: Manga): Manga = materializeMutex.withLock {
        val session = session()
        session.mangaId(manga.url)
        mangas.getMangaByUrlAndSourceId(manga.url, id)
            ?: mangas.insertNetworkManga(listOf(manga.copy(id = -1))).single()
    }
    override suspend fun getMangaDetails(manga: SManga) = getMangaDetails(manga, false)
    override suspend fun getMangaDetails(manga: SManga, forceNetwork: Boolean): SManga {
        val session = session()
        return toSManga(session.catalog.manga(session.mangaId(manga.url), forceNetwork), session)
    }
    override fun getMangaUrl(manga: SManga) = baseUrl.removeSuffix("/api")
    override fun getChapterUrl(chapter: SChapter) = baseUrl.removeSuffix("/api")
    override suspend fun getChapterList(manga: SManga) = getChapterList(manga, false)
    override suspend fun getChapterList(manga: SManga, forceNetwork: Boolean): List<SChapter> {
        val session = session()
        if (forceNetwork) session.api.invalidatePageOrder()
        val remote = session.catalog.chapters(session.mangaId(manga.url), forceNetwork)
        val localManga = mangas.getMangaByUrlAndSourceId(manga.url, id)
        val local = localManga?.let { chapters.getChapterByMangaId(it.id) }.orEmpty()
        val localByUrl = local.associateBy { it.url }
        val result = remote.map { entry ->
            val resourceUrl = session.chapterUrl(entry)
            if (forceNetwork && entry.format == "pdf") session.pdf.invalidate(pdfKey(resourceUrl))
            SChapter.create().apply {
                url = resourceUrl
                name = entry.name
                chapter_number = entry.number
                date_upload = entry.createdAt
                memo = buildJsonObject {
                    localByUrl[url]?.memo?.forEach { (key, value) -> put(key, value) }
                    if (entry.pageCount > 0) put("pagesCount", entry.pageCount)
                    put("smangaFormat", entry.format)
                    put("smangaUpdatedAt", entry.updatedAt)
                }
            }
        }.reversed()
        // Preserve local chapter identities when a server hides or removes an item.
        val urls = result.mapTo(hashSetOf()) { it.url }
        return result + local.filter { it.url !in urls }.map { old ->
            SChapter.create().apply {
                url = old.url
                name = old.name
                chapter_number = old.chapterNumber.toFloat()
                date_upload = old.dateUpload
                memo = old.memo
            }
        }
    }
    override suspend fun getPageList(chapter: SChapter) = getConnectionPageList(chapter, false).pages
    override suspend fun getConnectionPageList(chapter: SChapter, forceNetwork: Boolean): ConnectionPageList {
        val session = session()
        check(!isPdfChapter(chapter.url)) { "PDF requires a complete original file" }
        val manifest = session.catalog.manifest(session.chapterId(chapter.url), forceNetwork)
        return ConnectionPageList(
            manifest.pages.map {
                val image = session.api.pageUrl(manifest.chapterId, it.opdsPage).toHttpUrl().newBuilder()
                    .addQueryParameter("smangaVersion", manifest.version).build().toString()
                Page(it.displayIndex, url = chapter.url, imageUrl = image)
            },
        )
    }
    override suspend fun getImage(page: Page): Response {
        val session = session()
        session.chapterId(page.url)
        val first = session.api.opdsClient.newCall(imageRequest(page)).await()
        if (first.code != 404) return checkedSmangaImageResponse(first)
        first.close()
        val manifest = session.catalog.manifest(session.chapterId(page.url), refresh = true)
        val previous = page.imageUrl?.toHttpUrl()?.queryParameter("smangaVersion")
        if (previous != null && previous != manifest.version) throw SmangaException(SmangaException.Reason.PROTOCOL)
        val entry = manifest.pages.getOrNull(page.index) ?: error("Page is no longer available")
        page.imageUrl = session.api.pageUrl(manifest.chapterId, entry.opdsPage).toHttpUrl().newBuilder()
            .addQueryParameter("smangaVersion", manifest.version).build().toString()
        return checkedSmangaImageResponse(session.api.opdsClient.newCall(imageRequest(page)).await())
    }
    override val rawDownloadClient get() = client
    override val resumePolicy = ConnectionRawDownloadResumePolicy.RESTART
    override fun preferRawDownload(chapter: Chapter) = isPdfChapter(chapter.url)
    override fun rawFileRequest(
        resourceUrl: String,
        rangeStart: Long?,
    ) = session().let { it.api.rawFileRequest(it.chapterId(resourceUrl)) }
    override suspend fun validateRawDownload(file: UniFile) = SmangaPdfCache.validate(file)
    override fun isPdfChapter(chapterUrl: String): Boolean {
        val session = session()
        session.chapterId(chapterUrl)
        return chapterUrl.endsWith(".pdf")
    }
    private fun pdfKey(chapterUrl: String) = chapterUrl.encodeUtf8().sha256().hex()
    override fun findCompletePdfFile(chapterUrl: String) = session().let { session ->
        session.chapterId(chapterUrl)
        session.pdf.find(pdfKey(chapterUrl))
    }
    override suspend fun preparePdfFile(chapterUrl: String): UniFile {
        val session = session()
        return session.pdf.prepare(pdfKey(chapterUrl), session.chapterId(chapterUrl), session.api, session::checkActive)
    }
    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) {
        val session = session()
        session.reading.record(session.chapterFromUrl(chapterUrl), pageIndex, totalPages, readAt, initialPage)
    }
    override suspend fun pushPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) {
        session().reading.retryPending()
    }
    override suspend fun pullPageProgress(chapterUrl: String, chapterMemo: JsonObject) = session().let { session ->
        val snapshot = session.reading.pull(session.chapterFromUrl(chapterUrl), chapterMemo)
        if (snapshot == null || chapterUrl.endsWith(".pdf")) {
            snapshot
        } else {
            val manifest = session.catalog.manifest(session.chapterId(chapterUrl), refresh = true)
            val oldVersion = chapterMemo["smangaManifestVersion"]?.jsonPrimitive?.contentOrNull
            val mappingChanged = (oldVersion != null && oldVersion != manifest.version) ||
                (snapshot.totalPages > 0 && snapshot.totalPages != manifest.pageCount)
            if (mappingChanged) session.reading.requireMappingConfirmation(session.chapterId(chapterUrl))
            snapshot.copy(
                previousPublicationVersion = oldVersion,
                publicationVersion = manifest.version,
                updatedChapterMemo = buildJsonObject {
                    snapshot.updatedChapterMemo.forEach { (key, value) -> put(key, value) }
                    put("smangaManifestVersion", manifest.version)
                },
                requiresConfirmation = snapshot.requiresConfirmation || mappingChanged,
                requiresPageMappingConfirmation = snapshot.requiresPageMappingConfirmation || mappingChanged,
            )
        }
    }
    override suspend fun acceptRemotePageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
    ) {
        session().let { it.reading.accept(it.chapterFromUrl(chapterUrl), pageIndex, totalPages, readAt) }
    }
    override suspend fun setChapterReadStatus(chapterUrl: String, read: Boolean) {
        session().let { session ->
            val descriptor = session.chapterFromUrl(chapterUrl)
            val manga = mangas.getMangaByUrlAndSourceId(session.mangaUrl(descriptor.mangaId), id)
            val local = manga?.let { chapters.getChapterByMangaId(it.id) }?.firstOrNull { it.url == chapterUrl }
            session.reading.setRead(
                descriptor.copy(
                    pageCount = local?.memo?.let(ConnectionChapterMetadata::pagesCount) ?: 0,
                ),
                read,
            )
        }
    }
    override fun beginReadingSession(chapterUrl: String) {
        session().let { it.reading.beginSession(it.chapterId(chapterUrl)) }
    }
    override suspend fun confirmLocalPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) {
        session().let { it.reading.confirmLocal(it.chapterFromUrl(chapterUrl), pageIndex, totalPages, readAt) }
    }
    override suspend fun syncMangaProgress(manga: Manga) {
        session().let { it.reading.syncManga(it.mangaId(manga.url)) }
    }
    override suspend fun prepareReadingStateRestore(chapterUrls: List<String>) {
        session().let { session ->
            session.reading.prepareRestore(chapterUrls.filter { it.startsWith(session.prefix) }.map(session::chapterId))
        }
    }
    private suspend fun applyState(session: Session, state: SmangaReadState) {
        session.checkActive()
        val manga = mangas.getMangaByUrlAndSourceId(session.mangaUrl(state.mangaId), id) ?: return
        val local = chapters.getChapterByMangaId(manga.id).filter {
            runCatching { session.chapterId(it.url) }.getOrNull() == state.chapterId
        }
        session.checkActive()
        chapters.updateAll(
            local.map {
                ChapterUpdate(
                    it.id,
                    read = state.completed,
                    lastPageRead = state.pageIndex.coerceAtLeast(0).toLong(),
                    memo = ConnectionChapterMetadata.withPagesCount(it.memo, state.totalPages),
                )
            },
        )
    }
    override suspend fun syncConnectionHistory() {
        if (ConnectionRestoreState.isRestoring) return
        val session = session()
        session.reading.retryPending()
        val visitedManga = mutableSetOf<Long>()
        var page = 1
        do {
            val response = session.api.history(page++)
            for (entry in response.data) {
                session.checkActive()
                if (ConnectionRestoreState.isRestoring) return
                if (!visitedManga.add(entry.mangaId)) continue
                val manga = materialize(toManga(session.catalog.manga(entry.mangaId), session))
                Injekt.get<SyncChaptersWithSource>().await(getChapterList(manga.toSManga()), manga, this)
                session.reading.syncManga(entry.mangaId)
                // Aggregates only discover manga; MAX(chapterId) is not a resume location.
                val selected = selectSmangaHistoryState(
                    entry.mangaId,
                    repository.readStatesForManga(id, session.accountKey, entry.mangaId),
                ) ?: continue
                val chapter = chapters.getChapterByMangaId(manga.id).singleOrNull {
                    runCatching { session.chapterId(it.url) }.getOrNull() == selected.chapterId
                } ?: continue
                val existing = Injekt.get<GetHistory>().await(manga.id)
                    .filter { it.chapterId == chapter.id }
                    .maxOfOrNull { it.readAt?.time ?: 0 } ?: 0
                session.checkActive()
                if (ConnectionRestoreState.isRestoring) return
                if (selected.readAt > existing) {
                    Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(selected.readAt), 0))
                }
            }
        } while (response.hasNext)
    }
    private suspend fun listing(page: Int, query: String): MangasPage {
        val session = session()
        val media = session.catalog.media()
        val selected = preferences.mediaId.takeIf { id -> media.any { it.id == id } } ?: 0
        val mediaIds = media.filter { selected == 0L || it.id == selected }.map { it.id }
        val result = session.catalog.page(mediaIds, query, preferences.order, page)
        return MangasPage(result.data.map { toSManga(it, session) }, result.hasNext)
    }
    override suspend fun getPopularManga(page: Int) = listing(page, "")
    override suspend fun getLatestUpdates(page: Int) = listing(page, "")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = listing(page, query)
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        Observable.fromCallable { runBlocking { getMangaDetails(manga) } }
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable { runBlocking { getChapterList(manga) } }
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        Observable.fromCallable { runBlocking { getPageList(chapter) } }
    private fun unsupported(): Nothing = error("Use the authenticated Smanga API")
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
