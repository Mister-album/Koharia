package koharia.kavita

import eu.kanade.tachiyomi.network.await
import koharia.connection.ConnectionAddressRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

class KavitaApiClient(
    networkClient: OkHttpClient,
    address: String,
    private val key: String,
    namespace: String,
    private val expectedPrincipal: String? = null,
    private val router: ConnectionAddressRouter? = null,
    private val expectedIdentity: KavitaAccountIdentity? = null,
) : AutoCloseable {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
    val base = KavitaEndpoint.parse(address).base
    val virtualBase = "https://kavita.invalid/$namespace/".toHttpUrl()
    private val raw = networkClient.newBuilder().dispatcher(
        okhttp3.Dispatcher(),
    ).apply {
        // Kavita 0.8 authenticates some resources via query parameters; header redaction is insufficient.
        networkInterceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
        interceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
    }.cache(null).followRedirects(false).followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS).build()

    private val routedRaw = raw.newBuilder().apply { if (router != null) addInterceptor(router) }.build()

    @Volatile private var account: KavitaAccount? = null

    @Volatile private var closed = false
    private val authLock = Any()
    val client: OkHttpClient = raw.newBuilder().addInterceptor { chain ->
        if (closed) throw KavitaException(KavitaException.Reason.ACCOUNT_CHANGED)
        val original = chain.request()
        if (!ConnectionAddressRouter.owns(
                virtualBase,
                original.url,
            )
        ) {
            throw KavitaException(KavitaException.Reason.ADDRESS)
        }
        var user = authenticate()
        fun authenticated(): Request = original.newBuilder()
            .url(
                ConnectionAddressRouter.remap(original.url, virtualBase, base).newBuilder().apply {
                    // Older image/PDF endpoints require the key in addition to the JWT.
                    if (original.url.encodedPath.contains("/api/Reader/") ||
                        original.url.encodedPath.contains("/api/Image/")
                    ) {
                        setQueryParameter("apiKey", key)
                    }
                }.build(),
            )
            .header("Authorization", "Bearer ${user.token}")
            .apply { if (capabilities(user).authKeys) header("x-api-key", key) }
            .build()
        var result = chain.proceed(authenticated())
        if (result.code == 401) {
            result.close()
            synchronized(authLock) { if (account?.token == user.token) account = null }
            user = authenticate()
            result = chain.proceed(authenticated())
        }
        result.newBuilder().request(original).build()
    }.apply { if (router != null) addInterceptor(router) }.build()

    fun capabilities(user: KavitaAccount = authenticate()): KavitaCapabilities {
        val version = KavitaVersion.parse(user.kavitaVersion) ?: throw KavitaException(KavitaException.Reason.VERSION)
        if (version < KavitaVersion(0, 8)) throw KavitaException(KavitaException.Reason.VERSION)
        return KavitaCapabilities(version, user.roles.toSet())
    }

    suspend fun getAccount(refresh: Boolean = false): KavitaAccount = withContext(Dispatchers.IO) {
        synchronized(authLock) {
            if (refresh) account = null
            authenticate()
        }
    }
    internal val eventClient get() = routedRaw
    internal suspend fun eventRequest(path: String): Request {
        val user = getAccount()
        return Request.Builder().url(requireNotNull(base.resolve(path)))
            .header("Authorization", "Bearer ${user.token}").build()
    }
    internal fun invalidateAuthentication() {
        account = null
    }

    fun authenticate(): KavitaAccount = synchronized(authLock) {
        if (closed) throw KavitaException(KavitaException.Reason.ACCOUNT_CHANGED)
        account?.let { return@synchronized it }
        val endpoint = requireNotNull(base.resolve("api/Plugin/authenticate")).newBuilder()
            .addQueryParameter("apiKey", key).addQueryParameter("pluginName", "Koharia").build()
        val loggedIn = routedRaw.newCall(Request.Builder().url(endpoint).post("{}".toRequestBody(JSON)).build())
            .execute().use { response ->
                checkResponse(response)
                decode<KavitaAccount>(response.body.string())
            }
        if (loggedIn.token.isBlank() ||
            loggedIn.username.isBlank()
        ) {
            throw KavitaException(KavitaException.Reason.PROTOCOL)
        }
        var refreshed = routedRaw.newCall(
            Request.Builder().url(requireNotNull(base.resolve("api/Account/refresh-account")))
                .header("Authorization", "Bearer ${loggedIn.token}").build(),
        ).execute().use { response ->
            checkResponse(response)
            decode<KavitaAccount>(response.body.string())
        }.let {
            it.copy(
                token = it.token.ifBlank {
                    loggedIn.token
                },
                kavitaVersion = it.kavitaVersion.ifBlank { loggedIn.kavitaVersion },
            )
        }
        refreshed = withKavitaTokenClaims(refreshed, json)
        if (refreshed.roles.any { it.equals("Admin", true) }) {
            val info = routedRaw.newCall(
                Request.Builder().url(requireNotNull(base.resolve("api/Server/server-info-slim")))
                    .header("Authorization", "Bearer ${refreshed.token}").build(),
            ).execute().use {
                checkResponse(it)
                decode<KavitaServerInfo>(it.body.string())
            }
            if (info.installId.isBlank()) throw KavitaException(KavitaException.Reason.PROTOCOL)
            refreshed = refreshed.copy(installId = info.installId)
        }
        if (expectedPrincipal != null && expectedPrincipal != refreshed.principal) {
            throw KavitaException(KavitaException.Reason.ACCOUNT_CHANGED)
        }
        if (expectedIdentity != null && !expectedIdentity.matches(refreshed.identity)) {
            throw KavitaException(KavitaException.Reason.ACCOUNT_CHANGED)
        }
        capabilities(refreshed)
        account = refreshed
        refreshed
    }

    internal fun canonicalUrl(url: HttpUrl): HttpUrl = router?.canonicalUrl(url) ?: url

    fun url(path: String, vararg query: Pair<String, Any>): HttpUrl =
        requireNotNull(virtualBase.resolve("api/$path")).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build().also {
            require(ConnectionAddressRouter.owns(virtualBase, it))
        }

    fun request(path: String, vararg query: Pair<String, Any>): Request = Request.Builder().url(
        url(path, *query),
    ).build()

    suspend fun execute(path: String, method: String = "GET", body: JsonElement? = null): JsonElement {
        val request = Request.Builder().url(url(path)).method(
            method,
            if (method in setOf("GET", "HEAD")) null else (body?.toString() ?: "{}").toRequestBody(JSON),
        ).build()
        return client.newCall(request).await().use {
            checkResponse(it)
            val text = withContext(Dispatchers.IO) { it.body.string() }
            if (text.isBlank()) kotlinx.serialization.json.JsonNull else decode<JsonElement>(text)
        }
    }

    suspend fun mutate(path: String, body: JsonElement? = null, method: String = "POST") {
        val request = Request.Builder().url(url(path)).method(
            method,
            (body?.toString() ?: "{}").toRequestBody(JSON),
        ).build()
        client.newCall(request).await().use { checkResponse(it) }
    }

    inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString(text)
    } catch (_: kotlinx.serialization.SerializationException) {
        // Serialization errors can include response snippets containing credentials.
        throw KavitaException(KavitaException.Reason.PROTOCOL)
    }

    suspend inline fun <reified T> get(path: String): T = decode(execute(path).toString())
    suspend inline fun <reified T> post(
        path: String,
        body: JsonElement,
    ): T = decode(execute(path, "POST", body).toString())

    suspend fun libraries(): List<KavitaLibrary> = get("Library/libraries")
    suspend fun series(id: Long): KavitaSeries = get("Series/$id")
    suspend fun metadata(id: Long): KavitaMetadata = get("Series/metadata?seriesId=$id")
    suspend fun volumes(id: Long): List<KavitaVolume> = get("Series/volumes?seriesId=$id")
    suspend fun chapter(id: Long): KavitaChapter = try {
        get("Chapter?chapterId=$id")
    } catch (error: KavitaException) {
        if (error.status != 404 && error.status != 405) throw error
        get("Series/chapter?chapterId=$id")
    }

    suspend fun seriesPage(page: Int, filter: KavitaFilter, feed: String = "Series/all-v2"): KavitaSeriesPage {
        require(page > 0)
        val request = Request.Builder().url(url(feed, "PageNumber" to page, "PageSize" to 50))
            .post(json.encodeToString(filter).toRequestBody(JSON)).build()
        return client.newCall(request).await().use { response ->
            checkResponse(response)
            val items = json.decodeFromString<List<KavitaSeries>>(
                withContext(Dispatchers.IO) {
                    response.body.string()
                },
            )
            val pagination = response.header("Pagination")?.let { json.decodeFromString<KavitaPagination>(it) }
            if (pagination == null && items.isNotEmpty()) throw KavitaException(KavitaException.Reason.PROTOCOL)
            KavitaSeriesPage(items.distinctBy { it.id }, pagination?.let { it.currentPage < it.totalPages } ?: false)
        }
    }

    suspend fun progress(id: Long): KavitaProgress = get("Reader/get-progress?chapterId=$id")
    suspend fun annotationPage(page: Int, filter: JsonElement): KavitaAnnotationPage {
        require(page > 0)
        val request = Request.Builder().url(url("Annotation/all-filtered", "PageNumber" to page, "PageSize" to 50))
            .post(filter.toString().toRequestBody(JSON)).build()
        return client.newCall(request).await().use { response ->
            checkResponse(response)
            val items = decode<List<KavitaAnnotation>>(withContext(Dispatchers.IO) { response.body.string() })
            val paging = response.header("Pagination")?.let { decode<KavitaPagination>(it) }
                ?: throw KavitaException(KavitaException.Reason.PROTOCOL)
            if (paging.currentPage != page) throw KavitaException(KavitaException.Reason.PROTOCOL)
            KavitaAnnotationPage(items, paging.currentPage < paging.totalPages)
        }
    }
    suspend fun allReadingLists(): List<KavitaList> = buildList {
        var page = 1
        do {
            val request = request("ReadingList/lists", "PageNumber" to page, "PageSize" to 50)
                .newBuilder().post("{}".toRequestBody(JSON)).build()
            val hasNext = client.newCall(request).await().use {
                checkResponse(it)
                addAll(json.decodeFromString<List<KavitaList>>(withContext(Dispatchers.IO) { it.body.string() }))
                val paging = it.header("Pagination")?.let { value -> json.decodeFromString<KavitaPagination>(value) }
                    ?: throw KavitaException(KavitaException.Reason.PROTOCOL)
                require(paging.currentPage == page)
                paging.currentPage < paging.totalPages
            }
            page++
        } while (hasNext)
    }
    suspend fun saveProgress(progress: KavitaProgress) {
        if (!capabilities(getAccount()).writable) throw KavitaException(KavitaException.Reason.PERMISSION)
        execute("Reader/progress", "POST", json.parseToJsonElement(json.encodeToString(progress)))
    }
    fun cover(id: Long, version: String = "") = url("Image/series-cover", "seriesId" to id, "v" to version).toString()
    fun chapterCover(id: Long) = url("Image/chapter-cover", "chapterId" to id).toString()
    fun page(
        id: Long,
        index: Int,
    ) = url("Reader/image", "chapterId" to id, "page" to index, "extractPdf" to true).toString()
    fun rawRequest(
        id: Long,
        rangeStart: Long? = null,
    ): Request = request("Download/chapter", "chapterId" to id).newBuilder()
        .apply { rangeStart?.takeIf { it > 0 }?.let { header("Range", "bytes=$it-") } }.build()

    override fun close() {
        closed = true
        client.dispatcher.cancelAll()
        account = null
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        fun checkResponse(response: Response) {
            if (response.isSuccessful) return
            throw KavitaException(
                when (response.code) {
                    401 -> KavitaException.Reason.AUTHENTICATION
                    403 -> KavitaException.Reason.PERMISSION
                    404, 405 -> KavitaException.Reason.UNSUPPORTED
                    else -> KavitaException.Reason.NETWORK
                },
                response.code,
            )
        }
    }
}
