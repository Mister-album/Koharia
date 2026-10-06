package koharia.source.suwayomi

import android.content.Context
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.await
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
import koharia.connection.ConnectionChapterThumbnailAdapter
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionHealthAdapter
import koharia.connection.ConnectionHistorySyncAdapter
import koharia.connection.ConnectionLibraryMembershipAdapter
import koharia.connection.ConnectionLocalPageProgressAdapter
import koharia.connection.ConnectionManagedLifecycle
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.connection.ConnectionMangaProgressAdapter
import koharia.connection.ConnectionPageAdapter
import koharia.connection.ConnectionPageList
import koharia.connection.ConnectionPageProgressAdapter
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.ConnectionReaderRoutingAdapter
import koharia.connection.ConnectionRestoreState
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.suwayomi.SuwayomiApi
import koharia.suwayomi.SuwayomiCatalog
import koharia.suwayomi.SuwayomiDownloadStatus
import koharia.suwayomi.SuwayomiException
import koharia.suwayomi.SuwayomiIdentity
import koharia.suwayomi.SuwayomiManga
import koharia.suwayomi.SuwayomiReadState
import koharia.suwayomi.SuwayomiReadingCoordinator
import koharia.suwayomi.ui.SuwayomiLibraryScreen
import koharia.suwayomi.ui.suwayomiError
import koharia.suwayomi.withVisibleCategories
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
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import rx.Observable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.Date
import java.util.UUID

class SuwayomiSource(private val context: Context, override val connectionProfile: LibraryConnectionProfile) :
    HttpSource(),
    UnmeteredSource,
    ConnectionSource,
    ConnectionManagedLifecycle,
    ConnectionBrowseAdapter,
    ConnectionCatalogAdapter,
    ConnectionPageAdapter,
    ConnectionAccountAdapter,
    ConnectionHealthAdapter,
    ConnectionMangaBehaviorAdapter,
    ConnectionChapterThumbnailAdapter,
    ConnectionDownloadStorageAdapter,
    ConnectionReaderRoutingAdapter,
    ConnectionPageProgressAdapter,
    ConnectionLocalPageProgressAdapter,
    ConnectionMangaProgressAdapter,
    ConnectionReadStatusAdapter,
    ConnectionHistorySyncAdapter,
    ConnectionLibraryMembershipAdapter,
    ConnectionBackupRestoreAdapter,
    koharia.connection.ConnectionRemoteCategoriesAdapter,
    koharia.connection.ConnectionServerDownloadsAdapter,
    AutoCloseable {
    override val id = connectionProfile.id
    override val name = connectionProfile.name
    override val lang = "other"
    override val supportsLatest = false
    val preferences = SuwayomiPreferences(id)
    val epoch = MutableStateFlow(0L)
    val instanceKey = UUID.randomUUID().toString()
    private val repository: SuwayomiRepository = Injekt.get()
    private val json: Json = Injekt.get()
    private val mangas: MangaRepository by lazy { Injekt.get() }
    private val chapters: ChapterRepository by lazy { Injekt.get() }
    private val materializeMutex = Mutex()

    @Volatile private var active: Session? = null

    @Volatile private var closed = false

    @Volatile private var downloadAccount: String? = null
    private var registered = false
    override val historyScopeChanges get() = epoch.map { Unit }

    inner class Session internal constructor(val identity: SuwayomiIdentity) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val address = preferences.address
        private val internalAddress = preferences.internalAddress
        val api = SuwayomiApi(
            network.nonCloudflareClient,
            json,
            address,
            preferences.mode,
            preferences.username,
            preferences.password,
            internalAddress,
            ConnectionAddressRouter.forAndroid(context, {
                address
            }, { internalAddress }, SuwayomiApi.PROBE_PATH, authenticateProbe = false),
            imageScope = "${identity.connectionId}/${identity.account}",
        )
        val catalog = SuwayomiCatalog(identity, repository, api, json, ::checkActive)
        val downloadSnapshot = kotlinx.coroutines.flow.MutableStateFlow<koharia.suwayomi.SuwayomiDownloadStatus?>(null)
        val reading = SuwayomiReadingCoordinator(identity, repository, api, json, scope, ::checkActive) {
            applyState(this, it)
        }
        val prefix get() = identity.prefix

        /**
         * Chapters whose preview failed. A source that cannot serve pages fails for every one of its
         * chapters, so without this every scroll would re-request the same broken URLs. Scoped to the
         * session because remote chapter ids repeat across connections and accounts.
         */
        val failedPreviewChapters: MutableSet<Int> = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<Int, Boolean>(),
        )

        /**
         * Series whose extension the server no longer has. Detected once per series instead of once
         * per chapter, which is what kept a library full of dead sources from issuing a doomed page
         * request for every visible grid row.
         */
        val unavailableSourceMangas = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        fun checkActive() {
            if (closed || active !== this) throw CancellationException("Suwayomi connection session changed")
        }
        override fun close() {
            scope.cancel()
            api.close()
        }
    }

    @Synchronized fun session(): Session {
        check(!closed)
        return active ?: Session(SuwayomiIdentity(id, preferences.account)).also { active = it }
    }

    @Synchronized fun reload() {
        if (closed) return
        if (active?.identity?.account != preferences.account) {
            val downloads = Injekt.get<eu.kanade.tachiyomi.data.download.DownloadManager>()
            downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == id })
        }
        active?.close()
        active = null
        epoch.value++
        if (registered && hasValidConnection()) observeReading(session())
    }

    @Synchronized override fun onRegistered() {
        if (registered || closed) return
        registered = true
        if (hasValidConnection()) observeReading(session())
    }
    private fun observeReading(session: Session) {
        session.scope.launch {
            Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.collect {
                if (it) session.reading.retryPending()
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        active?.close()
        active = null
    }
    override val baseUrl get() = preferences.address.trimEnd('/')
    override val client get() = session().api.client
    override val pageLoadConcurrency = 2
    override val preserveDownloadPageBoundaries = true
    override val allowsUnvalidatedNetwork = true
    override val usesSharedDownloadStorage = false

    /** Server-managed downloads stay separate from Koharia's local DownloadManager queue. */
    suspend fun serverDownloadStatus(): SuwayomiDownloadStatus = session().api.downloadStatus()

    suspend fun enqueueServerDownloads(chapterUrls: List<String>): SuwayomiDownloadStatus {
        val session = session()
        val ids = chapterUrls.map { session.identity.chapter(it).second }
        session.checkActive()
        return session.api.enqueueDownloads(ids).also { session.checkActive() }
    }

    override fun remoteCategoriesScreen(mangaUrl: String?) =
        koharia.suwayomi.ui.SuwayomiCategoriesScreen(id, mangaUrl)

    override fun serverDownloadsScreen() = koharia.suwayomi.ui.SuwayomiDownloadsScreen(id)

    override suspend fun downloadChaptersOnServer(chapterUrls: List<String>) {
        enqueueServerDownloads(chapterUrls)
    }

    override fun serverChapterDownloads(mangaUrl: String) = kotlinx.coroutines.flow.channelFlow {
        val session = session()
        val mangaId = session.identity.mangaId(mangaUrl)
        var chapters = session.api.chapters(mangaId)
        var previousQueue = emptySet<Int>()
        suspend fun publish(status: SuwayomiDownloadStatus) {
            session.checkActive()
            val queue = status.queue.filter { it.mangaId == mangaId }.associateBy { it.chapterId }
            // A dequeued item may have completed or been cancelled. Read the stored chapters,
            // never infer a downloaded file from disappearing queue entries.
            if ((previousQueue - queue.keys).isNotEmpty()) chapters = session.api.chapters(mangaId)
            previousQueue = queue.keys
            session.checkActive()
            send(
                chapters.associate { chapter ->
                    session.identity.chapterUrl(mangaId, chapter.id) to
                        koharia.suwayomi.serverDownloadState(chapter, queue[chapter.id])
                },
            )
        }
        publish(session.api.downloadStatus())
        val events = koharia.suwayomi.SuwayomiDownloadEvents(session.api, json, this) { status ->
            publish(status ?: session.api.downloadStatus())
        }
        events.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            events.stop()
        }
    }

    suspend fun dequeueServerDownload(chapterUrl: String): SuwayomiDownloadStatus =
        session().api.dequeueDownload(session().identity.chapter(chapterUrl).second)
    override val mangaBehavior = ConnectionMangaBehavior(
        supportsChapterCoverGrid = true,
        providerManagedLibrary = true,
        allowsLocalLibraryManagement = false,
        allowsCategoryManagement = false,
        allowsFetchIntervalManagement = false,
    )

    /**
     * Marks a chapter whose preview comes from its own first page. Suwayomi exposes no chapter
     * thumbnail, so the cover is fetched lazily per visible grid item through
     * [loadChapterThumbnail] rather than as a plain URL.
     *
     * Returns null for a series whose extension the server no longer has: its pages can never be
     * resolved, so asking for them would only produce a failed request per chapter.
     */
    override fun chapterThumbnailUrl(chapterUrl: String): String? {
        if (!hasValidConnection()) return null
        val session = session()
        val mangaId = runCatching { session.identity.chapter(chapterUrl).first }.getOrNull() ?: return null
        if (session.unavailableSourceMangas.containsKey(mangaId)) return null
        return CHAPTER_THUMBNAIL_PREFIX + chapterUrl.encodeUtf8().base64Url()
    }

    /**
     * Resolves a chapter's first page through the connection's authenticated client. The server
     * stores the page list after its first resolution, so this does not refetch from the source.
     */
    override suspend fun loadChapterThumbnail(chapterUrl: String): ByteArray? = withIOContext {
        val encoded = chapterUrl.removePrefix(CHAPTER_THUMBNAIL_PREFIX)
        val decoded = encoded.decodeBase64()?.utf8() ?: return@withIOContext null
        val session = session()
        val (mangaId, chapterId) = runCatching { session.identity.chapter(decoded) }.getOrNull()
            ?: return@withIOContext null
        if (chapterId in session.failedPreviewChapters || session.unavailableSourceMangas.containsKey(mangaId)) {
            return@withIOContext null
        }
        // Checked before the fetch so a chapter belonging to another account is never requested.
        session.checkActive()
        val pages = runCatching { session.catalog.pages(chapterId) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (error.isMissingSource()) {
                    // A per-series condition. Recorded atomically because the grid's in-flight
                    // requests for other chapters of this series would otherwise each repeat it.
                    if (session.unavailableSourceMangas.putIfAbsent(mangaId, true) == null) {
                        logcat(LogPriority.INFO) {
                            "Suwayomi preview: series $mangaId has no source on the server; previews disabled"
                        }
                    }
                } else {
                    logcat(LogPriority.WARN, error) {
                        "Suwayomi preview: pages() failed for chapter $chapterId"
                    }
                }
                rememberFailedPreview(session, chapterId)
            }
            .getOrNull()
        if (pages == null) return@withIOContext null
        val page = pages.pages.firstOrNull()
        if (page.isNullOrBlank()) {
            logcat(LogPriority.WARN) {
                "Suwayomi preview: empty page list for chapter $chapterId " +
                    "(declared pageCount=${pages.chapter.pageCount})"
            }
            rememberFailedPreview(session, chapterId)
            return@withIOContext null
        }
        runCatching {
            session.api.client.newCall(session.api.resourceRequest(page)).await().use { response ->
                if (!response.isSuccessful) {
                    // The server could not get the bytes from the source; retrying it is futile.
                    logcat(LogPriority.WARN) {
                        "Suwayomi preview: HTTP ${response.code} for chapter $chapterId"
                    }
                    rememberFailedPreview(session, chapterId)
                    return@use null
                }
                response.body.bytes()
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Suwayomi preview: fetch failed for chapter $chapterId" }
            rememberFailedPreview(session, chapterId)
        }.getOrNull()
    }

    /** The server resolved the chapter but no longer has the extension that provides its pages. */
    private fun Throwable.isMissingSource(): Boolean =
        this is SuwayomiException && reason == SuwayomiException.Reason.SOURCE

    private fun rememberFailedPreview(session: Session, chapterId: Int) {
        val failed = session.failedPreviewChapters
        if (failed.size >= FAILED_PREVIEW_MEMORY) failed.clear()
        failed += chapterId
    }
    override fun hasValidConnection() = runCatching { SuwayomiApi.normalizeBase(preferences.address) }.isSuccess
    override suspend fun isConnectionReachable() = runCatching { session().api.about() }.isSuccess
    override suspend fun getAccount() = ConnectionAccount(preferences.username.ifBlank { name })
    override fun availableContentScopes() = setOf(LibraryContentScope.COMIC)
    override suspend fun readerContentScope(manga: Manga, chapter: Chapter) = LibraryContentScope.COMIC
    override fun createBrowseScreen(scope: LibraryContentScope, listingQuery: String?, showNavigationUp: Boolean) =
        SuwayomiLibraryScreen(id, listingQuery, showNavigationUp)
    override fun seriesSettingsAvailable() = kotlinx.coroutines.flow.flowOf(true)

    /** Server catalogue used by the connection's browse tab. */
    suspend fun serverSources(
        refresh: Boolean = false,
    ): List<koharia.suwayomi.SuwayomiSourceInfo> = session().let { session ->
        session.catalog.sources(refresh).also { session.checkActive() }
    }

    suspend fun serverExtensions(refresh: Boolean = false): List<koharia.suwayomi.SuwayomiExtension> =
        session().let { session ->
            session.catalog.extensions(fetchStores = refresh).also { session.checkActive() }
        }

    /** Installs, updates or uninstalls a server extension; completed when the server answers. */
    suspend fun serverExtensionAction(
        extension: koharia.suwayomi.SuwayomiExtension,
        action: koharia.suwayomi.SuwayomiExtensionAction,
        session: Session = session(),
    ): koharia.suwayomi.SuwayomiExtension? = session.let { session ->
        session.checkActive()
        val result = session.api.extensionAction(
            "mutation(\$id:String!){updateExtension(input:{id:\$id,patch:{${action.mutationField}:true}}){extension{pkgName isInstalled hasUpdate}}}",
            extension.pkgName,
        )
        session.checkActive()
        session.catalog.invalidateSourceConfiguration()
        result
    }

    /** Explicit remote discovery: refreshes the server's stored details and chapters for a manga. */
    suspend fun fetchSourceDetails(remoteId: Int) = session().let { session ->
        session.api.fetchSourceDetails(remoteId)
        session.checkActive()
    }

    /** Server-side pin (`webUI_isPinned`), the same key WebUI and Tsumiru write. */
    suspend fun setSourcePinned(sourceInfoId: Long, pinned: Boolean) = session().let { session ->
        session.catalog.setSourcePinned(sourceInfoId, pinned)
    }

    suspend fun sourceFilters(sourceInfoId: Long): List<koharia.suwayomi.SuwayomiSourceFilter> =
        session().let { session ->
            session.catalog.sourceFilters(sourceInfoId).also { session.checkActive() }
        }

    suspend fun sourceListing(
        sourceInfoId: Long,
        page: Int,
        query: String?,
        type: koharia.suwayomi.SuwayomiSourceMangaType,
        filters: List<koharia.suwayomi.SuwayomiFilterChange>,
    ): koharia.suwayomi.SuwayomiSourcePage = session().let { session ->
        val result = session.catalog.sourceListing(
            sourceInfoId,
            page,
            query,
            type,
            filters,
            session.catalog.listingRevision(),
        )
        session.checkActive()
        result
    }
    override fun downloadDirectoryName(): String {
        val account = runCatching { preferences.account }.getOrNull()?.also { downloadAccount = it }
            ?: downloadAccount ?: "unconfigured"
        return "Suwayomi_${id}_$account"
    }
    override fun downloadDirectoryNames() = listOf(downloadDirectoryName())
    override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())
    override fun legacyDownloadDirectoryNames() = emptyList<String>()
    override suspend fun historyMangaIds(): Set<Long> = session().let { session ->
        mangas.getMangaBySourceId(id).filter { it.url.startsWith(session.prefix) }.mapTo(hashSetOf()) { it.id }
    }
    override suspend fun filterLibraryEntries(mangas: List<Manga>): List<Manga> = session().let { session ->
        val ids = session.catalog.cachedShelf()?.mangas?.mapTo(hashSetOf()) { it.id }
        mangas.filter { entry ->
            val remote = runCatching { session.identity.mangaId(entry.url) }.getOrNull()
            remote != null && (ids == null || remote in ids)
        }
    }

    fun toSManga(entry: SuwayomiManga, session: Session = session()) = SManga.create().apply {
        url = session.identity.mangaUrl(entry.id)
        title = entry.title
        author = entry.author
        artist = entry.artist
        description = entry.description
        genre = entry.genre.joinToString()
        status = when (entry.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "LICENSED" -> SManga.LICENSED
            "PUBLISHING_FINISHED" -> SManga.PUBLISHING_FINISHED
            "CANCELLED" -> SManga.CANCELLED
            "ON_HIATUS" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        thumbnail_url = entry.thumbnailUrl?.let { session.api.resourceUrlOrNull(it) }
            ?.let { session.identity.imageUrl(it) }
        initialized = true
    }
    fun toManga(entry: SuwayomiManga, session: Session = session()): Manga {
        val remote = toSManga(entry, session)
        return Manga.create().copy(
            id = -entry.id.toLong(), source = id, url = remote.url, title = remote.title,
            author = remote.author, artist = remote.artist, description = remote.description, genre = entry.genre,
            status = remote.status.toLong(), thumbnailUrl = remote.thumbnail_url, initialized = false,
        )
    }
    suspend fun materialize(manga: Manga): Manga = materializeMutex.withLock {
        val session = session()
        session.identity.mangaId(manga.url)
        session.checkActive()
        mangas.getMangaByUrlAndSourceId(manga.url, id)
            ?: mangas.insertNetworkManga(listOf(manga.copy(id = -1))).single()
    }
    override suspend fun getMangaDetails(manga: SManga) = getMangaDetails(manga, false)
    override suspend fun getMangaDetails(
        manga: SManga,
        forceNetwork: Boolean,
    ): SManga = localized {
        session().let { session ->
            toSManga(session.catalog.manga(session.identity.mangaId(manga.url), forceNetwork), session)
        }
    }
    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${session().identity.mangaId(manga.url)}"
    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/manga/${session().identity.chapter(chapter.url).first}"
    override suspend fun getChapterList(manga: SManga) = getChapterList(manga, false)
    override suspend fun getChapterList(manga: SManga, forceNetwork: Boolean): List<SChapter> = localized {
        val session = session()
        val remote = session.catalog.chapters(session.identity.mangaId(manga.url), forceNetwork)
        val local = mangas.getMangaByUrlAndSourceId(manga.url, id)?.let {
            chapters.getChapterByMangaId(it.id)
        }.orEmpty()
        val byUrl = local.associateBy { it.url }
        val result = remote.map { entry ->
            SChapter.create().apply {
                url = session.identity.chapterUrl(entry.mangaId, entry.id)
                name = entry.name
                chapter_number = entry.chapterNumber
                date_upload = entry.uploadDate * 1000
                scanlator = entry.scanlator
                memo =
                    ConnectionChapterMetadata.withPagesCount(
                        byUrl[url]?.memo ?: JsonObject(emptyMap()),
                        entry.pageCount,
                    )
            }
        }
        val urls = result.mapTo(hashSetOf()) { it.url }
        result + local.filter { it.url !in urls }.map { old ->
            SChapter.create().apply {
                url = old.url
                name = old.name
                chapter_number = old.chapterNumber.toFloat()
                date_upload = old.dateUpload
                scanlator = old.scanlator
                memo = old.memo
            }
        }
    }
    override suspend fun getPageList(chapter: SChapter) = getConnectionPageList(chapter, false).pages
    override suspend fun getConnectionPageList(
        chapter: SChapter,
        forceNetwork: Boolean,
    ): ConnectionPageList = localized {
        val session = session()
        val (mangaId, chapterId) = session.identity.chapter(chapter.url)
        val online = Injekt.get<koharia.connection.ConnectionNetworkMonitor>().available.value
        val manifest = session.catalog.pagesForReading(chapterId, online, forceNetwork)
        require(manifest.chapter.mangaId == mangaId)
        ConnectionPageList(
            manifest.pages.mapIndexed { index, path ->
                Page(
                    index,
                    url = chapter.url,
                    imageUrl = session.identity.imageUrl(session.api.resourceUrl(path), manifest.version),
                )
            },
        )
    }
    override suspend fun getImage(page: Page): Response = localized {
        val session = session()
        val (_, chapterId) = session.identity.chapter(page.url)
        var response = session.api.client.newCall(imageRequest(page)).await()
        if (response.code == 404) {
            response.close()
            val fresh = session.catalog.pages(chapterId, true)
            val previous = page.imageUrl?.toHttpUrl()?.queryParameter("kohariaPages")
            if (previous != null && previous != fresh.version) {
                session.reading.pause(chapterId)
                throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
            }
            val path = fresh.pages.getOrNull(page.index) ?: throw SuwayomiException(SuwayomiException.Reason.NOT_FOUND)
            page.imageUrl = session.identity.imageUrl(session.api.resourceUrl(path), fresh.version)
            response = session.api.client.newCall(imageRequest(page)).await()
        }
        if (!response.isSuccessful || response.header("Content-Type").orEmpty().substringBefore(';')
                .let { !it.startsWith("image/") && it != "application/octet-stream" }
        ) {
            val reason = if (response.code == 401 || response.code == 403) {
                SuwayomiException.Reason.AUTH
            } else {
                SuwayomiException.Reason.IMAGE
            }
            response.close()
            throw SuwayomiException(reason)
        }
        try {
            session.checkActive()
        } catch (error: CancellationException) {
            response.close()
            throw error
        }
        response
    }
    override suspend fun pullPageProgress(chapterUrl: String, chapterMemo: JsonObject) = session().let { session ->
        val (mangaId, chapterId) = session.identity.chapter(chapterUrl)
        val manifest = session.catalog.pages(chapterId, true)
        val version = manifest.version
        val oldVersion = chapterMemo["suwayomiPagesVersion"]?.jsonPrimitive?.contentOrNull
        val progress = session.reading.pull(mangaId, chapterId, chapterMemo)
        val mapping = manifest.syncConflict != null || (oldVersion != null && oldVersion != version)
        if (mapping) session.reading.pause(chapterId)
        progress.copy(
            previousPublicationVersion = oldVersion,
            publicationVersion = version,
            updatedChapterMemo = buildJsonObject {
                progress.updatedChapterMemo.forEach { (key, value) -> put(key, value) }
                put("suwayomiPagesVersion", version)
            },
            requiresConfirmation = progress.requiresConfirmation || mapping,
            requiresPageMappingConfirmation = progress.requiresPageMappingConfirmation || mapping,
        )
    }
    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) = session().let { session ->
        val (mangaId, chapterId) = session.identity.chapter(chapterUrl)
        session.reading.record(mangaId, chapterId, pageIndex, totalPages, readAt, initialPage)
    }
    override suspend fun pushPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
    ) = session().let { session ->
        val (mangaId, chapterId) = session.identity.chapter(chapterUrl)
        session.reading.readerReady(mangaId, chapterId, pageIndex, totalPages)
    }
    override suspend fun confirmLocalPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) =
        session().let { session ->
            val (mangaId, chapterId) = session.identity.chapter(chapterUrl)
            session.reading.confirm(mangaId, chapterId, pageIndex, totalPages, readAt)
        }
    override suspend fun acceptRemotePageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int, readAt: Long) =
        session().let { session ->
            val (mangaId, chapterId) = session.identity.chapter(chapterUrl)
            session.reading.accept(mangaId, chapterId, pageIndex, totalPages, readAt)
        }
    override suspend fun setChapterReadStatus(chapterUrl: String, read: Boolean) = session().let { session ->
        val (mangaId, chapterId) = session.identity.chapter(chapterUrl)
        session.reading.markRead(mangaId, chapterId, read)
    }
    override suspend fun syncMangaProgress(manga: Manga) = session().let { session ->
        session.reading.syncManga(session.identity.mangaId(manga.url))
    }
    override suspend fun prepareReadingStateRestore(chapterUrls: List<String>) = session().let { session ->
        session.reading.prepareRestore(
            chapterUrls.filter { it.startsWith(session.prefix) }
                .map { session.identity.chapter(it).second },
        )
    }
    private suspend fun applyState(session: Session, state: SuwayomiReadState) {
        if (ConnectionRestoreState.isRestoring) return
        session.checkActive()
        val manga = mangas.getMangaByUrlAndSourceId(session.identity.mangaUrl(state.mangaId), id) ?: return
        val chapter = chapters.getChapterByMangaId(manga.id)
            .firstOrNull { it.url == session.identity.chapterUrl(state.mangaId, state.chapterId) } ?: return
        session.checkActive()
        chapters.updateAll(
            listOf(
                ChapterUpdate(
                    chapter.id,
                    read = state.read,
                    lastPageRead = state.page.toLong(),
                    memo = ConnectionChapterMetadata.withPagesCount(chapter.memo, state.count),
                ),
            ),
        )
        if (state.readAt > 0 && state.explicitRead == null) {
            val history = Injekt.get<GetHistory>().await(manga.id).filter { it.chapterId == chapter.id }
                .maxOfOrNull { it.readAt?.time ?: 0 } ?: 0
            if (state.readAt >
                history
            ) {
                Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(state.readAt), 0))
            }
        }
    }
    override suspend fun syncConnectionHistory() {
        if (ConnectionRestoreState.isRestoring) return
        val session = session()
        for (manga in mangas.getMangaBySourceId(id).filter { it.url.startsWith(session.prefix) }) {
            session.reading.syncManga(session.identity.mangaId(manga.url))
        }
    }
    private suspend fun listing(page: Int, query: String): MangasPage {
        require(page > 0)
        val session = session()
        val visibleCategories = preferences.visibleCategoryIds
        val entries = session.catalog.shelf().withVisibleCategories(visibleCategories).mangas.filter {
            it.title.contains(query, true) ||
                it.author.orEmpty().contains(query, true) || it.genre.any { tag -> tag.contains(query, true) }
        }
        val start = (page - 1) * 50
        session.checkActive()
        return MangasPage(entries.drop(start).take(50).map { toSManga(it, session) }, entries.size > start + 50)
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
    private fun unsupported(): Nothing = error("Use the Suwayomi API")
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

    private suspend fun <T> localized(block: suspend () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        throw IOException(context.suwayomiError(error), error)
    }

    private companion object {
        /** Recognised by the cover fetcher, which routes these through [loadChapterThumbnail]. */
        const val CHAPTER_THUMBNAIL_PREFIX = "koharia-local-v1:suwayomi/"
        const val FAILED_PREVIEW_MEMORY = 512
    }
}
