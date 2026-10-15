package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.parseAs
import koharia.komga.progress.KomgaProgressOrdering
import koharia.source.komga.KomgaProgressAccount
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import koharia.source.komga.awaitKomgaProgressSuccess
import koharia.source.komga.komgaProgressSync
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

private const val READLIST_API = "/api/v1/readlists"

class KomgaApi(
    private val trackId: Long,
    private val client: OkHttpClient,
) {

    private val sourceManager: SourceManager by lazy { Injekt.get<SourceManager>() }
    private val mangaRepository: MangaRepository by lazy { Injekt.get<MangaRepository>() }
    private val serverPreferences: KomgaServerPreferences by lazy { Injekt.get<KomgaServerPreferences>() }
    private val json: Json by injectLazy()
    private val ordering: koharia.komga.progress.KomgaProgressOrdering by lazy { Injekt.get() }
    private val pageEvents: koharia.domain.komga.KomgaPageEventRepository by lazy { Injekt.get() }
    private val progressCacheMutex = Mutex()
    private val completedProgressCache = mutableMapOf<Long, ProgressCacheEntry>()
    private val inProgressCache = mutableMapOf<Long, ProgressCacheEntry>()

    suspend fun getTrackSearch(url: String, sourceId: Long): TrackSearch {
        val source = requireOwnedSource(url, sourceId)
        return getTrackSearch(url, koharia.source.komga.captureKomgaProgressSession(source, sourceManager))
    }

    private suspend fun getTrackSearch(
        url: String,
        session: koharia.source.komga.KomgaProgressSession,
    ): TrackSearch = withIOContext {
        session.guard.requireCurrent()
        val source = session.source
        try {
            val track = with(json) {
                if (url.contains(READLIST_API)) {
                    source.client.newCall(session.request(GET(url).newBuilder()))
                        .awaitKomgaProgressSuccess()
                        .parseAs<ReadListDto>()
                        .toTrack()
                } else {
                    source.client.newCall(session.request(GET(url).newBuilder()))
                        .awaitKomgaProgressSuccess()
                        .parseAs<SeriesDto>()
                        .toTrack()
                }
            }

            val progress = source.client
                .newCall(
                    session.request(
                        GET("${url.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi")
                            .newBuilder(),
                    ),
                )
                .awaitKomgaProgressSuccess().let {
                    with(json) {
                        if (url.contains("/api/v1/series/")) {
                            it.parseAs<ReadProgressV2Dto>()
                        } else {
                            it.parseAs<ReadProgressDto>().toV2()
                        }
                    }
                }

            session.guard.requireCurrent()
            track.apply {
                cover_url = "$url/thumbnail"
                tracking_url = url
                total_chapters = progress.maxNumberSort.toLong()
                status = when (progress.booksCount) {
                    progress.booksUnreadCount -> Komga.UNREAD
                    progress.booksReadCount -> Komga.COMPLETED
                    else -> Komga.READING
                }
                last_chapter_read = progress.lastReadContinuousNumberSort
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not get item: $url" }
            throw e
        }
    }

    suspend fun updateProgress(track: Track): Track {
        val sourceId = trackSourceId(track)
        val source = requireOwnedSource(track.tracking_url, sourceId)
        val session = koharia.source.komga.captureKomgaProgressSession(source, sourceManager)
        val payload = if (track.tracking_url.contains("/api/v1/series/")) {
            json.encodeToString(ReadProgressUpdateV2Dto(track.last_chapter_read))
        } else {
            json.encodeToString(ReadProgressUpdateDto(track.last_chapter_read.toInt()))
        }
        val checkCurrent = {
            if (koharia.connection.ConnectionRestoreState.isRestoring) throw java.io.IOException("Restore in progress")
            session.guard.requireCurrent()
        }
        try {
            ordering.legacyAggregate(sourceId, session.account.key, checkCurrent) {
                if (pageEvents.hasUnsettled(sourceId, session.account.key)) {
                    throw koharia.komga.progress.KomgaProgressOnHold()
                }
                checkCurrent()
                source.client.newBuilder().retryOnConnectionFailure(false).build().newCall(
                    session.request(
                        Request.Builder()
                            .url(
                                "${track.tracking_url.replace(
                                    "/api/v1/series/",
                                    "/api/v2/series/",
                                )}/read-progress/tachiyomi",
                            )
                            .put(payload.toRequestBody("application/json".toMediaType())),
                    ),
                ).awaitKomgaProgressSuccess().use { }
                checkCurrent()
                invalidateProgressCache(sourceId)
            }
        } catch (_: koharia.komga.progress.KomgaProgressOnHold) {
            throw koharia.connection.ConnectionReadStatusException(koharia.connection.ConnectionReadStatus.CONFLICT)
        }
        return getTrackSearch(track.tracking_url, session)
    }

    suspend fun getSeriesBookProgress(url: String, sourceId: Long): List<SeriesBookProgress> {
        val source = requireOwnedSource(url, sourceId)
        return getSeriesBookProgress(url, koharia.source.komga.captureKomgaProgressSession(source, sourceManager))
    }

    internal suspend fun getSeriesBookProgress(
        url: String,
        session: koharia.source.komga.KomgaProgressSession,
    ): List<SeriesBookProgress> =
        withIOContext {
            session.guard.requireCurrent()
            val source = session.source
            with(json) {
                val baseUrl = url.substringBefore("/api/v1/series/")
                source.client.newCall(
                    session.request(
                        GET("$url/books?unpaged=true&media_status=READY&deleted=false")
                            .newBuilder()
                            .cacheControl(CacheControl.FORCE_NETWORK),
                    ),
                )
                    .awaitKomgaProgressSuccess()
                    .parseAs<PageWrapperDto<BookDto>>()
                    .content
                    .map { book ->
                        SeriesBookProgress(
                            seriesUrl = url,
                            url = "$baseUrl/api/v1/books/${book.id}",
                            readProgress = book.readProgress,
                            isEpub = book.media?.mediaProfile == "EPUB",
                            isDivinaCompatibleEpub = book.media?.isDivinaCompatibleEpub == true,
                        )
                    }.also { session.guard.requireCurrent() }
            }
        }

    /** Fresh response for callers holding a pre-request database snapshot. */
    internal suspend fun getInProgressBookProgress(
        session: koharia.source.komga.KomgaProgressSession,
        includeCompleted: Boolean,
    ): List<SeriesBookProgress> = withIOContext {
        session.guard.requireCurrent()
        val baseUrl = session.source.baseUrl.trimEnd('/')
        val readStatus = if (includeCompleted) "" else "&read_status=IN_PROGRESS"
        with(json) {
            session.source.client.newCall(
                session.request(
                    GET("$baseUrl/api/v1/books?unpaged=true$readStatus&deleted=false")
                        .newBuilder().cacheControl(CacheControl.FORCE_NETWORK),
                ),
            ).awaitKomgaProgressSuccess().parseAs<PageWrapperDto<BookDto>>().content.map { book ->
                SeriesBookProgress(
                    seriesUrl = "$baseUrl/api/v1/series/${book.seriesId}",
                    url = "$baseUrl/api/v1/books/${book.id}",
                    readProgress = book.readProgress,
                    isEpub = book.media?.mediaProfile == "EPUB",
                    isDivinaCompatibleEpub = book.media?.isDivinaCompatibleEpub == true,
                )
            }.also { session.guard.requireCurrent() }
        }
    }

    suspend fun getBookProgress(bookUrl: String, sourceId: Long, ownerKey: String? = null): BookProgressSnapshot? {
        val source = resolveSource(sourceId) ?: if (ownerKey != null) throw KomgaProgressOwnerChanged() else return null
        val session = koharia.source.komga.captureKomgaProgressSession(source, sourceManager)
        if (ownerKey != null && session.account.key != ownerKey) throw KomgaProgressOwnerChanged()
        if (!bookUrl.belongsTo(source)) {
            if (ownerKey != null) throw KomgaProgressOwnerChanged()
            return null
        }
        return getBookProgress(bookUrl, session) { session.guard.requireCurrent() }
    }

    internal suspend fun getBookProgress(
        bookUrl: String,
        session: koharia.source.komga.KomgaProgressSession,
        checkCurrent: suspend () -> Unit,
    ): BookProgressSnapshot = withIOContext {
        checkCurrent()
        session.guard.requireCurrent()
        val source = session.source
        with(json) {
            val request = session.request(GET(bookUrl).newBuilder().cacheControl(CacheControl.FORCE_NETWORK))
            val book = source.client.newCall(request)
                .awaitKomgaProgressSuccess()
                .parseAs<BookDto>()
            check(book.id == KomgaProgressOrdering.bookId(bookUrl)) { "Komga response book identity changed" }
            session.guard.requireCurrent()
            checkCurrent()
            BookProgressSnapshot(
                url = bookUrl,
                seriesUrl = book.seriesId.takeIf(String::isNotBlank)?.let {
                    "${bookUrl.substringBefore("/api/")}/api/v1/series/$it"
                },
                readProgress = book.readProgress,
                pageIndex = book.readProgress?.page?.let { (it - 1).coerceAtLeast(0) },
                totalPages = book.media?.pagesCount ?: 0,
                completed = book.readProgress?.completed ?: false,
                readDate = book.readProgress?.readDate,
                isEpub = book.media?.mediaProfile == "EPUB",
                isDivinaCompatibleEpub = book.media?.isDivinaCompatibleEpub == true,
                fileHash = book.fileHash,
                fileLastModified = book.fileLastModified,
                sizeBytes = book.sizeBytes,
                fileName = book.name,
            )
        }
    }

    suspend fun getInProgressBookProgress(
        sourceId: Long? = null,
        includeCompleted: Boolean = false,
        forceRefresh: Boolean = false,
    ): List<SeriesBookProgress> =
        withIOContext {
            val source = resolveSource(sourceId) ?: return@withIOContext emptyList()
            progressCacheMutex.withLock {
                val account = source.progressAccount()
                val cache = if (includeCompleted) completedProgressCache else inProgressCache
                val now = System.currentTimeMillis()
                val cached = cache[source.id]
                if (!forceRefresh && cached?.ownerKey == account.key &&
                    now - cached.fetchedAt <= PROGRESS_CACHE_TTL_MILLIS
                ) {
                    return@withLock cached.books
                }

                if (!includeCompleted && !forceRefresh) {
                    val completed = completedProgressCache[source.id]
                    if (completed?.ownerKey == account.key && now - completed.fetchedAt <= PROGRESS_CACHE_TTL_MILLIS) {
                        return@withLock completed.books.filter { it.readProgress != null && !it.readProgress.completed }
                    }
                }

                val baseUrl = source.baseUrl.trimEnd('/')
                if (baseUrl.isBlank()) {
                    logcat(LogPriority.WARN) { "KomgaApi.getInProgressBookProgress: blank server base URL" }
                    return@withLock emptyList()
                } else {
                    val readStatus = if (includeCompleted) "" else "&read_status=IN_PROGRESS"
                    val books = with(json) {
                        source.client
                            .newCall(
                                GET(
                                    "$baseUrl/api/v1/books?unpaged=true$readStatus&deleted=false",
                                    account.headers,
                                ).newBuilder()
                                    .tag(KomgaProgressAccount::class.java, account)
                                    .cacheControl(CacheControl.FORCE_NETWORK)
                                    .komgaProgressSync()
                                    .build(),
                            )
                            .awaitKomgaProgressSuccess()
                            .parseAs<PageWrapperDto<BookDto>>()
                            .content
                            .map { book ->
                                SeriesBookProgress(
                                    seriesUrl = "$baseUrl/api/v1/series/${book.seriesId}",
                                    url = "$baseUrl/api/v1/books/${book.id}",
                                    readProgress = book.readProgress,
                                    isEpub = book.media?.mediaProfile == "EPUB",
                                    isDivinaCompatibleEpub = book.media?.isDivinaCompatibleEpub == true,
                                )
                            }
                    }
                    account.requireCurrent(resolveSource(source.id)?.progressAccount()?.key.orEmpty())
                    val entry = ProgressCacheEntry(System.currentTimeMillis(), books, account.key)
                    cache[source.id] = entry
                    if (includeCompleted) {
                        inProgressCache[source.id] = entry.copy(
                            books = books.filter { it.readProgress != null && !it.readProgress.completed },
                        )
                    }
                    books
                }
            }
        }

    suspend fun search(query: String, sourceId: Long? = null): List<TrackSearch> =
        withIOContext {
            val source = resolveSource(sourceId) ?: return@withIOContext emptyList()
            with(json) {
                val baseUrl = source.baseUrl.trimEnd('/')
                if (baseUrl.isBlank()) {
                    logcat(LogPriority.WARN) { "KomgaApi.search: blank server base URL" }
                    emptyList()
                } else {
                    val httpUrl = "$baseUrl/api/v1/series".toHttpUrlOrNull()
                    if (httpUrl == null) {
                        logcat(LogPriority.WARN) { "KomgaApi.search: invalid server base URL: $baseUrl" }
                        return@withIOContext emptyList()
                    }

                    val url = httpUrl.newBuilder()
                        .addQueryParameter("search", query)
                        .addQueryParameter("deleted", "false")
                        .build()

                    source.client
                        .newCall(GET(url, source.currentHeaders()))
                        .awaitKomgaProgressSuccess()
                        .parseAs<PageWrapperDto<SeriesDto>>()
                        .content
                        .map { it.toTrack().apply { tracking_url = "$baseUrl/api/v1/series/${it.id}" } }
                }
            }
        }

    internal suspend fun setBookReadStatus(
        session: koharia.source.komga.KomgaProgressSession,
        bookUrl: String,
        read: Boolean,
        checkCurrent: suspend () -> Unit,
    ) {
        checkCurrent()
        val builder = Request.Builder().url("$bookUrl/read-progress")
        if (read) {
            builder.patch(
                json.encodeToString(BookReadProgressUpdateDto(completed = true))
                    .toRequestBody("application/json".toMediaType()),
            )
        } else {
            builder.delete()
        }
        session.source.client.newBuilder().retryOnConnectionFailure(false).build()
            .newCall(session.request(builder)).awaitKomgaProgressSuccess().use { }
        checkCurrent()
        session.guard.requireCurrent()
        invalidateProgressCache(session.sourceId)
    }

    suspend fun updateBookProgress(
        sourceId: Long,
        bookUrl: String,
        page: Int,
        completed: Boolean,
        ownerKey: String? = null,
    ) {
        val source = resolveSource(sourceId) ?: throw KomgaProgressOwnerChanged()
        val account = source.progressAccount()
        if (ownerKey != null && account.key != ownerKey) throw KomgaProgressOwnerChanged()
        if (!bookUrl.belongsTo(source)) throw KomgaProgressOwnerChanged()
        val session = koharia.source.komga.captureKomgaProgressSession(source, sourceManager)
        updateBookProgress(session, bookUrl, page, completed) { session.guard.requireCurrent() }
    }

    internal suspend fun updateBookProgress(
        session: koharia.source.komga.KomgaProgressSession,
        bookUrl: String,
        page: Int,
        completed: Boolean,
        checkCurrent: suspend () -> Unit,
    ) {
        checkCurrent()
        session.guard.requireCurrent()
        val source = session.source
        val payload = json.encodeToString(
            if (completed) {
                BookReadProgressUpdateDto(completed = true, page = page)
            } else {
                BookReadProgressUpdateDto(page = page)
            },
        )

        source.client.newBuilder().retryOnConnectionFailure(false).build().newCall(
            session.request(
                Request.Builder()
                    .url("$bookUrl/read-progress")
                    .patch(payload.toRequestBody("application/json".toMediaType())),
            ),
        ).awaitKomgaProgressSuccess().use { }
        session.guard.requireCurrent()
        checkCurrent()
        invalidateProgressCache(source.id)
    }

    suspend fun invalidateProgressCache(sourceId: Long) {
        progressCacheMutex.withLock {
            completedProgressCache.remove(sourceId)
            inProgressCache.remove(sourceId)
        }
    }

    private fun resolveSource(sourceId: Long?): KomgaSource? {
        val targetSourceId = sourceId ?: serverPreferences.activeServerId.get()
        return sourceManager.get(targetSourceId) as? KomgaSource
    }

    suspend fun trackSourceId(track: Track): Long {
        val manga = mangaRepository.getMangaByIdOrNull(track.manga_id)
            ?: error("Komga track has no owning series")
        check(manga.url.trimEnd('/') == track.tracking_url.trimEnd('/')) {
            "Komga track does not match its owning series"
        }
        requireOwnedSource(track.tracking_url, manga.source)
        return manga.source
    }

    private fun requireOwnedSource(url: String, sourceId: Long): KomgaSource =
        resolveSource(sourceId)?.takeIf { url.belongsTo(it) }
            ?: error("Komga connection no longer matches the requested resource")

    private fun String.belongsTo(source: KomgaSource): Boolean {
        return substringBefore("/api/").trimEnd('/') == source.baseUrl.trimEnd('/')
    }

    private fun SeriesDto.toTrack(): TrackSearch = TrackSearch.create(trackId).also {
        it.title = metadata.title
        it.summary = metadata.summary
        it.publishing_status = metadata.status
    }

    private fun ReadListDto.toTrack(): TrackSearch = TrackSearch.create(trackId).also {
        it.title = name
    }

    data class SeriesBookProgress(
        val seriesUrl: String? = null,
        val url: String,
        val readProgress: BookReadProgressDto?,
        val isEpub: Boolean = false,
        val isDivinaCompatibleEpub: Boolean = false,
    )

    data class BookProgressSnapshot(
        val url: String,
        val seriesUrl: String?,
        val readProgress: BookReadProgressDto?,
        val pageIndex: Int?,
        val totalPages: Int,
        val completed: Boolean,
        val readDate: String?,
        val isEpub: Boolean,
        val isDivinaCompatibleEpub: Boolean,
        val fileHash: String?,
        val fileLastModified: String?,
        val sizeBytes: Long,
        val fileName: String,
    )

    private data class ProgressCacheEntry(
        val fetchedAt: Long,
        val books: List<SeriesBookProgress>,
        val ownerKey: String,
    )

    private companion object {
        const val PROGRESS_CACHE_TTL_MILLIS = 5_000L
    }
}
