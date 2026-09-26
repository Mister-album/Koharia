package koharia.smanga

import eu.kanade.tachiyomi.network.await
import koharia.connection.ConnectionAddressRouter
import koharia.connection.ConnectionAddressVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SmangaApi(
    networkClient: OkHttpClient,
    private val json: Json,
    address: String,
    private val username: String,
    private val password: String,
    private val namespace: String,
    private val addressRouter: ConnectionAddressRouter? = null,
    private val preparationDelay: suspend (Long) -> Unit = { delay(it) },
) {
    val base: HttpUrl = normalizeBase(address)
    private val dispatcher = Dispatcher()
    private val sessionMutex = Mutex()
    private val configMutex = Mutex()
    private val pageOrderRevision = AtomicLong()

    @Volatile private var session: Session? = null

    @Volatile private var orderChapterByNumber: Boolean? = null

    @Volatile private var closed = false

    // Inherited interceptors/event listeners may record request bodies or authentication headers.
    private val isolatedClient = networkClient.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }
        .cache(null)
        .dns(Dns.SYSTEM)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .eventListener(EventListener.NONE)
        .dispatcher(dispatcher)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun OkHttpClient.Builder.withAddressRouting() = apply {
        addressRouter?.let {
            addInterceptor(it)
            addNetworkInterceptor(ConnectionAddressRouter.redirectGuard)
        }
    }

    private val client = isolatedClient.newBuilder().withAddressRouting().build()
    private val historyClient = client.newBuilder().retryOnConnectionFailure(false).build()

    val opdsClient: OkHttpClient = isolatedClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            checkOpen()
            val request = chain.request()
            if (!ownsOpds(request.url)) throw SmangaException(SmangaException.Reason.ADDRESS)
            chain.proceed(
                request.newBuilder()
                    .removeHeader("token")
                    .removeHeader("Cookie")
                    .removeHeader("Range")
                    .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                    .build(),
            )
        }
        .withAddressRouting()
        .build()

    fun close() {
        closed = true
        session = null
        dispatcher.cancelAll()
    }

    suspend fun login(): SmangaAccount = ensureSession().account

    suspend fun account(): SmangaAccount = parseAccount(read("user/me").data())

    suspend fun validate(internalAddress: String = ""): SmangaAccount = withContext(Dispatchers.IO) {
        // Address verification must contact both endpoints directly, without a routing fallback.
        check(internalAddress.isBlank() || addressRouter == null)
        val account = account()
        validateOpds()
        if (internalAddress.isNotBlank() && normalizeBase(internalAddress) != base) {
            val alternate = SmangaApi(isolatedClient, json, internalAddress, username, password, namespace)
            try {
                // A new login token is a shared-database proof; never log in again at the alternate endpoint.
                alternate.session = ensureSession()
                val other = try {
                    parseAccount(alternate.read("user/me", retryAuthentication = false).data())
                } catch (error: SmangaException) {
                    if (error.reason == SmangaException.Reason.AUTH) {
                        throw ConnectionAddressVerification.Failure(ConnectionAddressVerification.Reason.MISMATCH)
                    }
                    throw error
                }
                if (other.id != account.id || other.userName != account.userName) {
                    throw ConnectionAddressVerification.Failure(ConnectionAddressVerification.Reason.MISMATCH)
                }
                alternate.validateOpds()
            } finally {
                alternate.close()
            }
        }
        account
    }

    private suspend fun validateOpds() {
        opdsClient.newCall(Request.Builder().url(opdsUrl("")).build()).await().use { response ->
            if (response.code == 404) throw SmangaException(SmangaException.Reason.OPDS_DISABLED, 404)
            checkHttp(response.code)
            val document = Jsoup.parse(readBody(response), "", Parser.xmlParser())
            val feed = document.children().firstOrNull()
            if (feed?.tagName() != "feed" || feed.attr("xmlns") != "http://www.w3.org/2005/Atom") {
                throw SmangaException(SmangaException.Reason.OPDS_DISABLED)
            }
        }
    }

    suspend fun media(): List<SmangaMedia> = allPages("media", emptyMap()) { value ->
        SmangaMedia(value.id("mediaId"), value.text("mediaName"), value.nonNegativeInt("mangaCount"))
    }.also { values ->
        if (values.map { it.id }.distinct().size != values.size) incomplete()
    }

    suspend fun tags(): List<SmangaTag> = read("tag").list().map {
        val value = it.objectValue()
        SmangaTag(value.id("tagId"), value.text("tagName"))
    }.distinctBy { it.id }

    /** count is the deduplicated size of this association page, not a total. Only an empty page ends it. */
    suspend fun taggedMangas(tagIds: List<Long>, page: Int, order: String): List<SmangaManga> {
        require(tagIds.isNotEmpty())
        tagIds.forEach(::requireId)
        requirePage(page, PAGE_SIZE)
        return read(
            "tags-manga",
            mapOf(
                "tagIds" to tagIds.distinct().joinToString(","),
                "page" to page.toString(),
                "pageSize" to PAGE_SIZE.toString(),
                "order" to order,
            ),
        ).list().map { parseManga(it.objectValue()) }.also {
            if (it.size > PAGE_SIZE) incomplete()
        }
    }

    suspend fun mangas(
        mediaId: Long,
        page: Int = 1,
        pageSize: Int = PAGE_SIZE,
        query: String = "",
        order: String = "mangaName asc",
    ): SmangaMangaPage {
        requireId(mediaId)
        requirePage(page, pageSize)
        val value = read(
            "manga",
            mapOf(
                "mediaId" to mediaId.toString(),
                "page" to page.toString(),
                "pageSize" to pageSize.toString(),
                "keyWord" to query,
                "order" to order,
            ),
        )
        val data = value.list().map { parseManga(it.objectValue()) }
        if (data.any { it.mediaId != mediaId } || data.size > pageSize) incomplete()
        return SmangaMangaPage(data, page, pageSize, value.count())
    }

    suspend fun manga(id: Long): SmangaManga {
        requireId(id)
        return parseManga(read("manga/$id").data()).also { if (it.id != id) incomplete() }
    }

    suspend fun chapters(mangaId: Long): List<SmangaChapter> {
        requireId(mangaId)
        return allPages("chapter", mapOf("mangaId" to mangaId.toString(), "order" to "id asc")) {
            parseChapter(it).also { chapter -> if (chapter.mangaId != mangaId) incomplete() }
        }.also { values ->
            if (values.map { it.id }.distinct().size != values.size) incomplete()
        }
    }

    suspend fun chapter(id: Long): SmangaChapter {
        requireId(id)
        return parseChapter(read("chapter/$id").data()).also { if (it.id != id) incomplete() }
    }

    fun invalidatePageOrder() {
        pageOrderRevision.incrementAndGet()
        orderChapterByNumber = null
    }

    suspend fun preparePages(chapterId: Long): SmangaPageManifest = withContext(Dispatchers.IO) {
        withTimeoutOrNull(PREPARE_TIMEOUT_MS) { preparePagesWithinDeadline(chapterId) }
            ?: throw SmangaException(SmangaException.Reason.PREPARING)
    }

    private suspend fun preparePagesWithinDeadline(chapterId: Long): SmangaPageManifest {
        requireId(chapterId)
        val numericOrder = configMutex.withLock {
            orderChapterByNumber ?: run {
                val revision = pageOrderRevision.get()
                val value = read("client-user-config")["data"]
                val config = if (value is JsonPrimitive && value.isString) {
                    parseObject(value.content)
                } else {
                    value?.objectValue() ?: JsonObject(emptyMap())
                }
                config.truth("orderChapterByNumber").also {
                    if (pageOrderRevision.get() == revision) orderChapterByNumber = it
                }
            }
        }
        repeat(PREPARE_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val params = buildMap {
                // The server deletes its extraction record at reTry >= 10.
                put("reTry", attempt.coerceAtMost(9).toString())
                // vine.any preserves strings; even "false" is truthy in the server's JavaScript.
                if (numericOrder) put("orderChapterByNumber", "1")
            }
            val value = read("chapter-images/$chapterId", params)
            when (value.text("status")) {
                "compressed" -> {
                    if (value.integer("code") != 200L) throw SmangaException(SmangaException.Reason.SERVER)
                    val paths = (value["data"] as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.contentOrNull ?: protocol()
                    } ?: protocol()
                    return SmangaPageManifest.create(chapterId, paths)
                }
                "compressing" -> if (attempt < PREPARE_ATTEMPTS - 1) preparationDelay(PREPARE_DELAY_MS)
                else -> throw SmangaException(SmangaException.Reason.SERVER)
            }
        }
        throw SmangaException(SmangaException.Reason.PREPARING)
    }

    suspend fun pushProgress(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        totalPages: Int,
        completed: Boolean,
    ): SmangaProgress {
        requireId(mangaId)
        requireId(chapterId)
        require(totalPages >= 0 && pageIndex >= 0 && pageIndex < Int.MAX_VALUE)
        if (totalPages > 0) require(pageIndex < totalPages)
        val result = read(
            "latest",
            body = buildJsonObject {
                put("mangaId", mangaId)
                put("chapterId", chapterId)
                put("page", pageIndex + 1)
                put("count", totalPages)
                put("finish", if (completed) 1 else 0)
            },
        )
        return writtenProgress(result, pageIndex, totalPages, completed)
    }

    suspend fun markUnread(mangaId: Long, chapterId: Long, totalPages: Int): SmangaProgress {
        requireId(mangaId)
        requireId(chapterId)
        require(totalPages >= 0)
        val result = read(
            "latest",
            body = buildJsonObject {
                put("mangaId", mangaId)
                put("chapterId", chapterId)
                put("page", 0)
                put("count", totalPages)
                put("finish", 0)
            },
        )
        return writtenProgress(result, -1, totalPages, false)
    }

    private fun writtenProgress(
        result: JsonObject,
        pageIndex: Int,
        totalPages: Int,
        completed: Boolean,
    ): SmangaProgress {
        val data = result.data()
        if (data.integer("page") != pageIndex.toLong() + 1 || data.integer("count") != totalPages.toLong() ||
            data.integer("finish") != (if (completed) 1L else 0L) || data.date("updateTime") <= 0
        ) {
            protocol()
        }
        return parseProgress(data) ?: protocol()
    }

    suspend fun addHistory(mediaId: Long, mangaId: Long, chapterId: Long) {
        requireId(mediaId)
        requireId(mangaId)
        requireId(chapterId)
        read(
            "history",
            body = buildJsonObject {
                put("mediaId", mediaId)
                put("mangaId", mangaId)
                put("chapterId", chapterId)
            },
            retryAuthentication = false,
            transport = historyClient,
        )
    }

    suspend fun history(page: Int = 1, pageSize: Int = PAGE_SIZE): SmangaHistoryPage {
        requirePage(page, pageSize)
        val value = read("history", mapOf("page" to page.toString(), "pageSize" to pageSize.toString()))
        val data = value.list().map {
            val item = it.objectValue()
            SmangaHistory(
                mangaId = item.id("mangaId"),
                chapterId = item.id("chapterId"),
                mediaId = item.id("mediaId"),
                mangaName = item.text("mangaName"),
                chapterName = item.text("chapterName"),
                createdAt = item.date("createTime"),
                latest = parseProgress(item["latest"]),
            )
        }
        if (data.size > pageSize) incomplete()
        return SmangaHistoryPage(data, page, pageSize, value.count())
    }

    fun coverUrl(mangaId: Long): String {
        requireId(mangaId)
        return opdsUrl("manga/$mangaId/cover").toString()
    }

    fun chapterCoverUrl(chapterId: Long): String {
        requireId(chapterId)
        return opdsUrl("chapter/$chapterId/cover").toString()
    }

    fun pageUrl(chapterId: Long, opdsPage: Int): String {
        requireId(chapterId)
        require(opdsPage > 0)
        return opdsUrl("chapter/$chapterId/page/$opdsPage").toString()
    }

    fun rawFileRequest(chapterId: Long): Request {
        requireId(chapterId)
        return Request.Builder().url(opdsUrl("chapter/$chapterId/download")).build()
    }

    private fun opdsUrl(path: String): HttpUrl = endpoint(if (path.isEmpty()) "opds" else "opds/$path")
        .newBuilder().addQueryParameter("koharia", namespace).build()

    private fun endpoint(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    private fun ownsOpds(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.queryParameterValues("koharia") == listOf(namespace) &&
            (url.encodedPath == base.encodedPath + "opds" || url.encodedPath.startsWith(base.encodedPath + "opds/"))

    private fun checkOpen() {
        if (closed) throw CancellationException("Connection configuration changed")
    }

    private suspend fun ensureSession(rejectedToken: String? = null): Session = sessionMutex.withLock {
        checkOpen()
        session?.takeIf { it.token != rejectedToken }?.let { return@withLock it }
        val request = Request.Builder().url(endpoint("login"))
            .post(
                buildJsonObject {
                    put("userName", username)
                    put("passWord", password)
                }
                    .toString().toRequestBody(JSON_MEDIA_TYPE),
            ).build()
        val value = execute(request).data()
        val token = value.text("token").takeIf(String::isNotBlank) ?: protocol()
        Session(token, parseAccount(value)).also {
            checkOpen()
            session = it
        }
    }

    private suspend fun read(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        retryAuthentication: Boolean = true,
        transport: OkHttpClient = client,
    ): JsonObject {
        var active = ensureSession()
        repeat(if (retryAuthentication) 2 else 1) { attempt ->
            checkOpen()
            val url = endpoint(path).newBuilder().apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            val request = Request.Builder().url(url.build()).header("token", active.token).apply {
                if (body != null) {
                    val encoded = body.toString().toRequestBody(JSON_MEDIA_TYPE)
                    post(if (retryAuthentication) encoded else NonReplayableBody(encoded))
                }
            }.build()
            try {
                return execute(request, transport)
            } catch (error: SmangaException) {
                if (!retryAuthentication || error.reason != SmangaException.Reason.AUTH || attempt != 0) throw error
                active = ensureSession(active.token)
            }
        }
        throw SmangaException(SmangaException.Reason.AUTH)
    }

    // await() receives headers; consuming the response body still performs blocking socket reads.
    private suspend fun execute(request: Request, transport: OkHttpClient = client): JsonObject =
        withContext(Dispatchers.IO) {
            checkOpen()
            val call = transport.newCall(request)
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.await().use { response ->
                    val text = readBody(response)
                    val value = try {
                        parseObject(text)
                    } catch (error: SmangaException) {
                        checkHttp(response.code)
                        throw error
                    }
                    val status = value.text("status").lowercase()
                    if (status == "permisson error" || status == "permission error" || status == "no permission") {
                        throw SmangaException(SmangaException.Reason.PERMISSION, response.code)
                    }
                    if (status == "token error") throw SmangaException(SmangaException.Reason.AUTH, response.code)
                    checkHttp(response.code)
                    val code = value.integer("code") ?: protocol()
                    if (status == "failed" || status == "error" || value.text("success") in listOf("false", "0")) {
                        throw SmangaException(SmangaException.Reason.SERVER, response.code)
                    }
                    if (code !in 200L..299L) {
                        if (code !in 0L..Int.MAX_VALUE.toLong()) protocol()
                        checkHttp(code.toInt())
                        throw SmangaException(SmangaException.Reason.SERVER, code.toInt())
                    }
                    value
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                cancellation.cancel()
            }
        }

    private fun readBody(response: Response): String {
        val source = response.body.source()
        source.request(MAX_JSON_BYTES + 1)
        if (source.buffer.size > MAX_JSON_BYTES) protocol()
        return source.readUtf8()
    }

    private fun parseObject(text: String): JsonObject = try {
        json.parseToJsonElement(text).objectValue()
    } catch (_: IllegalArgumentException) {
        protocol()
    }

    private suspend fun <T> allPages(path: String, query: Map<String, String>, parse: (JsonObject) -> T): List<T> {
        val result = mutableListOf<T>()
        var total: Int? = null
        repeat(MAX_PAGES) { index ->
            currentCoroutineContext().ensureActive()
            val value = read(path, query + mapOf("page" to (index + 1).toString(), "pageSize" to PAGE_SIZE.toString()))
            val count = value.count()
            if (count > MAX_PAGES * PAGE_SIZE || (total != null && total != count)) incomplete()
            total = count
            val batch = value.list()
            if (batch.size > PAGE_SIZE || result.size + batch.size > count) incomplete()
            result += batch.map { parse(it.objectValue()) }
            if (result.size == count) return result
            if (batch.isEmpty()) incomplete()
        }
        incomplete()
    }

    private fun parseAccount(value: JsonObject) = SmangaAccount(
        id = value.id("userId"),
        userName = value.text("userName"),
        role = value.text("role", "userRole"),
    )

    private fun parseManga(value: JsonObject): SmangaManga = SmangaManga(
        id = value.id("mangaId"), mediaId = value.id("mediaId"),
        name = value.text(
            "title",
            "mangaName",
        ),
        description = value.text("describe", "intro", "description", "summary"),
        author = value.text("author"), status = value.text("status"),
        tags = (value["tags"] as? JsonArray).orEmpty().mapNotNull {
            when (it) {
                is JsonObject -> it.text("tagName", "name").takeIf(String::isNotBlank)
                is JsonPrimitive -> it.contentOrNull?.takeIf(String::isNotBlank)
                else -> null
            }
        },
        chapterCount = value.nonNegativeInt("chapterCount"),
        createdAt = value.date("createTime"), updatedAt = value.date("updateTime"),
        sortName = value.text("mangaName", "title"),
    )

    private fun parseChapter(value: JsonObject) = SmangaChapter(
        id = value.id("chapterId"), mangaId = value.id("mangaId"), mediaId = value.id("mediaId"),
        name = value.text("chapterName"),
        number =
        value.text("chapterNumber").toFloatOrNull()?.takeIf { it.isFinite() } ?: -1f,
        pageCount = value.nonNegativeInt("pageCount"), format = value.text("chapterType").lowercase(),
        latest = parseProgress(value["latest"]),
        createdAt = value.date("createTime"), updatedAt = value.date("updateTime"),
    )

    private fun parseProgress(value: JsonElement?): SmangaProgress? {
        if (value == null || value == JsonNull) return null
        val data = value.objectValue()
        return SmangaProgress(
            pageIndex = data.nonNegativeInt("page") - 1,
            totalPages = data.nonNegativeInt("count"),
            completed = data.truth("finish"),
            updatedAt = data.date("updateTime"),
        )
    }

    private class Session(val token: String, val account: SmangaAccount)

    // Also prevents OkHttp from following a 503 Retry-After: 0 with another non-idempotent POST.
    private class NonReplayableBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot() = true
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 1000
        private const val MAX_JSON_BYTES = 16L * 1024 * 1024
        private const val PREPARE_ATTEMPTS = 180
        private const val PREPARE_DELAY_MS = 2000L
        private const val PREPARE_TIMEOUT_MS = 360_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeBase(address: String): HttpUrl {
            val url = address.trim().toHttpUrlOrNull() ?: throw SmangaException(SmangaException.Reason.ADDRESS)
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
                throw SmangaException(SmangaException.Reason.ADDRESS)
            }
            val path = url.encodedPath.trimEnd('/')
            val apiPath = if (path.endsWith("/api")) "$path/" else "$path/api/"
            return url.newBuilder().encodedPath(apiPath).build()
        }

        private fun requireId(id: Long) = require(id > 0)
        private fun requirePage(page: Int, pageSize: Int) = require(page > 0 && pageSize in 1..1000)

        private fun checkHttp(code: Int) {
            if (code in 200..299) return
            throw SmangaException(
                when (code) {
                    401 -> SmangaException.Reason.AUTH
                    403 -> SmangaException.Reason.PERMISSION
                    404 -> SmangaException.Reason.NOT_FOUND
                    in 300..399 -> SmangaException.Reason.ADDRESS
                    else -> SmangaException.Reason.SERVER
                },
                code,
            )
        }

        private fun protocol(): Nothing = throw SmangaException(SmangaException.Reason.PROTOCOL)
        private fun incomplete(): Nothing = throw SmangaException(SmangaException.Reason.INCOMPLETE)
        private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: protocol()
        private fun JsonObject.data(): JsonObject = get("data")?.objectValue() ?: protocol()
        private fun JsonObject.list(): JsonArray = get("list") as? JsonArray ?: protocol()
        private fun JsonObject.text(vararg names: String): String = names.firstNotNullOfOrNull { name ->
            (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }.orEmpty()
        private fun JsonObject.integer(name: String): Long? = text(name).toLongOrNull()
        private fun JsonObject.id(name: String): Long = integer(name)?.takeIf { it > 0 } ?: protocol()
        private fun JsonObject.nonNegativeInt(name: String): Int =
            integer(name)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        private fun JsonObject.count(): Int =
            integer("count")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: protocol()
        private fun JsonObject.truth(name: String): Boolean = text(name).let {
            it == "true" ||
                (it.toLongOrNull() ?: 0) > 0
        }
        private fun JsonObject.date(name: String): Long {
            val value = text(name)
            value.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching {
                    LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
                }.getOrDefault(0)
        }
    }
}
