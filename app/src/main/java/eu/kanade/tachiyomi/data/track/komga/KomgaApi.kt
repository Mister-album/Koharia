package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import koharia.source.komga.KomgaServerPreferences
import koharia.source.komga.KomgaSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
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
    private val serverPreferences: KomgaServerPreferences by lazy { Injekt.get<KomgaServerPreferences>() }
    private val json: Json by injectLazy()

    suspend fun getTrackSearch(url: String): TrackSearch =
        withIOContext {
            val source = resolveSourceForUrl(url) ?: error("No Komga source found for $url")
            val headers = source.currentHeaders()
            try {
                val track = with(json) {
                    if (url.contains(READLIST_API)) {
                        source.client.newCall(GET(url, headers))
                            .awaitSuccess()
                            .parseAs<ReadListDto>()
                            .toTrack()
                    } else {
                        source.client.newCall(GET(url, headers))
                            .awaitSuccess()
                            .parseAs<SeriesDto>()
                            .toTrack()
                    }
                }

                val progress = source.client
                    .newCall(
                        GET("${url.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi", headers),
                    )
                    .awaitSuccess().let {
                        with(json) {
                            if (url.contains("/api/v1/series/")) {
                                it.parseAs<ReadProgressV2Dto>()
                            } else {
                                it.parseAs<ReadProgressDto>().toV2()
                            }
                        }
                    }

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
        val source = resolveSourceForUrl(track.tracking_url) ?: error("No Komga source found for ${track.tracking_url}")
        val payload = if (track.tracking_url.contains("/api/v1/series/")) {
            json.encodeToString(ReadProgressUpdateV2Dto(track.last_chapter_read))
        } else {
            json.encodeToString(ReadProgressUpdateDto(track.last_chapter_read.toInt()))
        }
        source.client.newCall(
            Request.Builder()
                .url("${track.tracking_url.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi")
                .headers(source.currentHeaders())
                .put(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        )
            .awaitSuccess()
        return getTrackSearch(track.tracking_url)
    }

    suspend fun getSeriesBookProgress(url: String): List<SeriesBookProgress> =
        withIOContext {
            val source = resolveSourceForUrl(url) ?: return@withIOContext emptyList()
            with(json) {
                val baseUrl = url.substringBefore("/api/v1/series/")
                source.client.newCall(
                    GET("$url/books?unpaged=true&media_status=READY&deleted=false", source.currentHeaders()),
                )
                    .awaitSuccess()
                    .parseAs<PageWrapperDto<BookDto>>()
                    .content
                    .map { book ->
                        SeriesBookProgress(
                            seriesUrl = url,
                            url = "$baseUrl/api/v1/books/${book.id}",
                            readProgress = book.readProgress,
                            isEpub = book.media?.mediaProfile == "EPUB",
                        )
                    }
            }
        }

    suspend fun getInProgressBookProgress(sourceId: Long? = null): List<SeriesBookProgress> =
        withIOContext {
            val source = resolveSource(sourceId) ?: return@withIOContext emptyList()
            with(json) {
                val baseUrl = source.baseUrl.trimEnd('/')
                if (baseUrl.isBlank()) {
                    logcat(LogPriority.WARN) { "KomgaApi.getInProgressBookProgress: blank server base URL" }
                    emptyList()
                } else {
                    source.client
                        .newCall(
                            GET(
                                "$baseUrl/api/v1/books?unpaged=true&read_status=IN_PROGRESS&deleted=false",
                                source.currentHeaders(),
                            ),
                        )
                        .awaitSuccess()
                        .parseAs<PageWrapperDto<BookDto>>()
                        .content
                        .map { book ->
                            SeriesBookProgress(
                                seriesUrl = "$baseUrl/api/v1/series/${book.seriesId}",
                                url = "$baseUrl/api/v1/books/${book.id}",
                                readProgress = book.readProgress,
                                isEpub = book.media?.mediaProfile == "EPUB",
                            )
                        }
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
                        .awaitSuccess()
                        .parseAs<PageWrapperDto<SeriesDto>>()
                        .content
                        .map { it.toTrack().apply { tracking_url = "$baseUrl/api/v1/series/${it.id}" } }
                }
            }
        }

    suspend fun updateBookProgress(
        bookUrl: String,
        page: Int,
        completed: Boolean,
    ) {
        val source = resolveSourceForUrl(bookUrl) ?: return
        val payload = json.encodeToString(
            if (completed) {
                BookReadProgressUpdateDto(completed = true, page = page)
            } else {
                BookReadProgressUpdateDto(page = page)
            },
        )

        source.client.newCall(
            Request.Builder()
                .url("$bookUrl/read-progress")
                .headers(source.currentHeaders())
                .patch(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        ).awaitSuccess()
    }

    private fun resolveSource(sourceId: Long?): KomgaSource? {
        val targetSourceId = sourceId ?: serverPreferences.activeServerId.get()
        return sourceManager.get(targetSourceId) as? KomgaSource
    }

    private fun resolveSourceForUrl(url: String): KomgaSource? {
        val targetBaseUrl = url.substringBefore("/api/").trimEnd('/')
        return sourceManager.getOnlineSources()
            .filterIsInstance<KomgaSource>()
            .firstOrNull { it.baseUrl.trimEnd('/') == targetBaseUrl }
            ?: resolveSource(null)
                ?.takeIf { url.startsWith(it.baseUrl.trimEnd('/')) }
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
    )
}
