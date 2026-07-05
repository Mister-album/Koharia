package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.source.komga.KomgaSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Credentials
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
    private val sourcePreferences by lazy {
        (sourceManager.get(KomgaSource.ID) as? ConfigurableSource)?.sourcePreferences()
    }

    private val headers: Headers
        get() {
            return Headers.Builder().apply {
                val apiKey = sourcePreferences?.getString("API key", "").orEmpty()
                if (apiKey.isNotBlank()) {
                    add("X-API-Key", apiKey)
                }
            }
                .add("User-Agent", "Koharia v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
                .build()
        }

    private val json: Json by injectLazy()
    private val requestClient: OkHttpClient
        get() {
            val username = sourcePreferences?.getString("Username", "").orEmpty()
            val password = sourcePreferences?.getString("Password", "").orEmpty()
            val apiKey = sourcePreferences?.getString("API key", "").orEmpty()
            return client.newBuilder()
                .authenticator { _, response ->
                    if (apiKey.isNotBlank() || response.request.header("Authorization") != null || username.isBlank()) {
                        null
                    } else {
                        response.request.newBuilder()
                            .addHeader("Authorization", Credentials.basic(username, password))
                            .build()
                    }
                }
                .build()
        }

    suspend fun getTrackSearch(url: String): TrackSearch =
        withIOContext {
            try {
                val track = with(json) {
                    if (url.contains(READLIST_API)) {
                        requestClient.newCall(GET(url, headers))
                            .awaitSuccess()
                            .parseAs<ReadListDto>()
                            .toTrack()
                    } else {
                        requestClient.newCall(GET(url, headers))
                            .awaitSuccess()
                            .parseAs<SeriesDto>()
                            .toTrack()
                    }
                }

                val progress = requestClient
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
        val payload = if (track.tracking_url.contains("/api/v1/series/")) {
            json.encodeToString(ReadProgressUpdateV2Dto(track.last_chapter_read))
        } else {
            json.encodeToString(ReadProgressUpdateDto(track.last_chapter_read.toInt()))
        }
        requestClient.newCall(
            Request.Builder()
                .url("${track.tracking_url.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi")
                .headers(headers)
                .put(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        )
            .awaitSuccess()
        return getTrackSearch(track.tracking_url)
    }

    suspend fun getSeriesBookProgress(url: String): List<SeriesBookProgress> =
        withIOContext {
            with(json) {
                val baseUrl = url.substringBefore("/api/v1/series/")
                requestClient.newCall(GET("$url/books?unpaged=true&media_status=READY&deleted=false", headers))
                    .awaitSuccess()
                    .parseAs<PageWrapperDto<BookDto>>()
                    .content
                    .map { book ->
                        SeriesBookProgress(
                            url = "$baseUrl/api/v1/books/${book.id}",
                            readProgress = book.readProgress,
                        )
                    }
            }
        }

    suspend fun getInProgressBookProgress(): List<SeriesBookProgress> =
        withIOContext {
            with(json) {
                val baseUrl = (sourceManager.get(KomgaSource.ID) as? KomgaSource)?.baseUrl?.trimEnd('/').orEmpty()
                if (baseUrl.isBlank()) {
                    logcat(LogPriority.WARN) { "KomgaApi.getInProgressBookProgress: blank server base URL" }
                    emptyList()
                } else {
                    requestClient
                        .newCall(
                            GET("$baseUrl/api/v1/books?unpaged=true&read_status=IN_PROGRESS&deleted=false", headers),
                        )
                        .awaitSuccess()
                        .parseAs<PageWrapperDto<BookDto>>()
                        .content
                        .map { book ->
                            SeriesBookProgress(
                                seriesUrl = "$baseUrl/api/v1/series/${book.seriesId}",
                                url = "$baseUrl/api/v1/books/${book.id}",
                                readProgress = book.readProgress,
                            )
                        }
                }
            }
        }

    suspend fun search(query: String): List<TrackSearch> =
        withIOContext {
            with(json) {
                val baseUrl = (sourceManager.get(KomgaSource.ID) as? KomgaSource)?.baseUrl?.trimEnd('/').orEmpty()
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

                    requestClient
                        .newCall(GET(url, headers))
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
        val payload = json.encodeToString(
            if (completed) {
                BookReadProgressUpdateDto(completed = true, page = page)
            } else {
                BookReadProgressUpdateDto(page = page)
            },
        )

        requestClient.newCall(
            Request.Builder()
                .url("$bookUrl/read-progress")
                .headers(headers)
                .patch(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        ).awaitSuccess()
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
    )
}
