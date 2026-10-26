package koharia.lanraragi

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.await
import koharia.connection.ConnectionAddressRouter
import koharia.connection.NetworkDiagnostics
import koharia.domain.lanraragi.LanraragiEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.concurrent.TimeUnit

class LanraragiException(val reason: Reason, val status: Int? = null) : IOException(reason.name) {
    enum class Reason { ADDRESS, AUTH, VERSION, SERVER, EMPTY, UNAVAILABLE, PROGRESS_DISABLED, INCOMPLETE }
}

@Serializable
internal data class LanraragiArchiveDto(
    val arcid: String,
    val title: String = "",
    val tags: String? = null,
    val summary: String? = null,
    val pagecount: Int = 0,
    val progress: Int = 0,
    val lastreadtime: Long = 0,
    val isnew: JsonElement = JsonNull,
) {
    fun entry() = LanraragiEntry(
        id = arcid,
        title = title.ifBlank { arcid },
        tags = tags.orEmpty(),
        summary = summary.orEmpty(),
        pageCount = pagecount.coerceAtLeast(0),
        progress = progress.coerceAtLeast(0),
        lastRead = lastreadtime * 1000,
        isNew =
        isnew.toString().trim('"').equals("true", true) || isnew.toString() == "1",
    )
}

data class LanraragiServerInfo(
    val version: String,
    val tracksProgress: Boolean,
    val authenticatedProgress: Boolean,
    val pageSize: Int = 100,
) {
    val modern: Boolean get() = versionNumbers(version) >= 90080
}

internal fun versionNumbers(version: String): Int {
    val parts = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(version)?.groupValues ?: return 0
    return parts[1].toInt() * 1_000_000 + parts[2].toInt() * 10_000 + parts[3].toInt()
}

class LanraragiApi(
    baseUrl: String,
    private val apiKey: String,
    networkClient: OkHttpClient,
    private val json: Json,
    private val diagnosticConnectionId: Long? = null,
    private val addressRouter: ConnectionAddressRouter? = null,
) {
    val base: HttpUrl = normalizeBase(baseUrl)

    @Volatile private var closed = false
    private val catalogCalls = mutableSetOf<Call>()
    private val clientBuilder = networkClient.newBuilder()
        .cache(null)
        .dispatcher(Dispatcher())
        .dns(Dns.SYSTEM)
        .addNetworkInterceptor { chain ->
            if (!owns(chain.request().url) && addressRouter?.ownsInternal(chain.request().url) != true) {
                if (BuildConfig.LANRARAGI_DIAGNOSTICS) {
                    NetworkDiagnostics.record(
                        "LanraragiNetwork: connectionId=$diagnosticConnectionId " +
                            "call=${NetworkDiagnostics.callId(chain.call())} event=address-rejected",
                    )
                }
                throw LanraragiException(LanraragiException.Reason.ADDRESS)
            }
            chain.proceed(chain.request())
        }
        .addInterceptor { chain ->
            val request = chain.request()
            if (!owns(request.url)) throw LanraragiException(LanraragiException.Reason.ADDRESS)
            val authorized = request.newBuilder().apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.encodeUtf8().base64()}")
            }.build()
            chain.proceed(authorized)
        }

    init {
        addressRouter?.let {
            clientBuilder.addInterceptor(it)
                .addNetworkInterceptor(ConnectionAddressRouter.redirectGuard)
        }
        if (BuildConfig.LANRARAGI_DIAGNOSTICS) {
            val inheritedFactory = networkClient.eventListenerFactory
            clientBuilder.eventListenerFactory { call ->
                inheritedFactory.create(call) + LanraragiNetworkTimingEventListener(
                    call = call,
                    connectionId = diagnosticConnectionId,
                    primaryBase = base,
                    addressRouter = addressRouter,
                )
            }
        }
    }

    val client = clientBuilder.build()

    // Full catalogue import includes server work, transfer, decoding and staged DB writes.
    // A fixed total deadline aborts healthy large imports. Bound idle reads instead, and keep
    // the shared dispatcher. Track body consumption separately for connection cancellation.
    internal val catalogClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    // Large pages may transfer continuously for longer than the metadata request deadline.
    // Keep inactivity timeouts and the shared dispatcher so connection reload still cancels them.
    val imageClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()

    // Visible reader pages must not queue behind a shelf full of thumbnails.
    val readerClient = imageClient.newBuilder().dispatcher(Dispatcher()).build()

    @Volatile private var info: LanraragiServerInfo? = null
    val hasTankThumbnails: Boolean get() = info?.modern == true

    fun close() {
        val activeCatalogCalls = synchronized(catalogCalls) {
            closed = true
            catalogCalls.toList()
        }
        activeCatalogCalls.forEach(Call::cancel)
        client.dispatcher.cancelAll()
        readerClient.dispatcher.cancelAll()
    }

    fun url(path: String): HttpUrl = base.newBuilder()
        .encodedPath(base.encodedPath.trimEnd('/') + "/" + path.trimStart('/'))
        .build()

    fun request(path: String): Request = Request.Builder().url(url(path)).build()

    fun imageUrl(resourcePath: String): String {
        val path = addressRouter?.canonicalResourcePath(resourcePath) ?: resourcePath
        val candidate = when {
            path.startsWith("http://") || path.startsWith("https://") -> path.toHttpUrl()
            path.startsWith(base.encodedPath.trimEnd('/') + "/") && base.encodedPath != "/" -> base.resolve(path)
            path.startsWith("/api/") -> base.resolve(path.removePrefix("/"))
            else -> base.resolve(path)
        } ?: throw LanraragiException(LanraragiException.Reason.ADDRESS)
        val canonical = addressRouter?.canonicalUrl(candidate) ?: candidate
        if (!owns(canonical)) throw LanraragiException(LanraragiException.Reason.ADDRESS)
        return canonical.toString()
    }

    private fun owns(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.encodedPath.startsWith(base.encodedPath)

    private suspend fun execute(request: Request): Response {
        if (closed) throw CancellationException("Connection configuration changed")
        return checkedResponse(client.newCall(request).await())
    }

    private fun checkedResponse(response: Response): Response {
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw LanraragiException(
                if (code == 401 ||
                    code == 403
                ) {
                    LanraragiException.Reason.AUTH
                } else {
                    LanraragiException.Reason.SERVER
                },
                code,
            )
        }
        return response
    }

    private suspend fun <T> catalogResponse(request: Request, consume: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) {
            coroutineScope {
                if (closed) throw CancellationException("Connection configuration changed")
                val call = catalogClient.newCall(request)
                synchronized(catalogCalls) {
                    if (closed) throw CancellationException("Connection configuration changed")
                    catalogCalls.add(call)
                }
                if (BuildConfig.LANRARAGI_DIAGNOSTICS) {
                    NetworkDiagnostics.record(
                        "LanraragiCatalog: connectionId=$diagnosticConnectionId " +
                            "call=${NetworkDiagnostics.callId(
                                call,
                            )} endpoint=${NetworkDiagnostics.endpoint(request)} " +
                            "event=request-policy callTimeoutMs=0 readTimeoutMs=${catalogClient.readTimeoutMillis}",
                    )
                }
                // await() covers cancellation only until headers arrive. Keep the socket
                // cancellable while blocking on the response body or consuming a batch.
                val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.await().use { response ->
                        currentCoroutineContext().ensureActive()
                        if (closed) throw CancellationException("Connection configuration changed")
                        consume(checkedResponse(response)).also {
                            currentCoroutineContext().ensureActive()
                            if (closed) throw CancellationException("Connection configuration changed")
                        }
                    }
                } catch (error: Exception) {
                    // Closing a blocked socket surfaces as IOException. Preserve cancellation
                    // semantics instead of reporting an intentional stop as a network failure.
                    currentCoroutineContext().ensureActive()
                    if (closed) throw CancellationException("Connection configuration changed", error)
                    throw error
                } finally {
                    cancellation.cancel()
                    synchronized(catalogCalls) { catalogCalls.remove(call) }
                }
            }
        }

    suspend fun read(request: Request, catalogSync: Boolean = false): JsonElement =
        if (catalogSync) catalogResponse(request, ::readJson) else execute(request).use(::readJson)

    private fun readJson(response: Response): JsonElement {
        if (response.code == 204) return JsonObject(emptyMap())
        return json.parseToJsonElement(response.body.string()).also { value ->
            if (value is JsonObject && value["success"]?.jsonPrimitive?.content in listOf("0", "false")) {
                throw LanraragiException(LanraragiException.Reason.SERVER)
            }
        }
    }

    suspend fun serverInfo(force: Boolean = false, catalogSync: Boolean = false): LanraragiServerInfo {
        if (!force) info?.let { return it }
        val value = read(request("api/info"), catalogSync).jsonObject
        val version = value.text("version")
        if (versionNumbers(version) < 90070) throw LanraragiException(LanraragiException.Reason.VERSION)
        return LanraragiServerInfo(
            version,
            value.bool("server_tracks_progress"),
            value.bool("authenticated_progress"),
            value["archives_per_page"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: 100,
        ).also { info = it }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun streamArchives(consume: suspend (List<LanraragiEntry>) -> Unit) {
        val started = System.nanoTime()
        var count = 0
        var consumeNanos = 0L
        var bytes = 0L
        var complete = false
        try {
            catalogResponse(request("api/archives")) { response ->
                val context = currentCoroutineContext()
                LanraragiTimedBody(response.body, { context.ensureActive() }) { size, finished ->
                    bytes = size
                    complete = finished
                }.use { body ->
                    json.decodeToSequence<LanraragiArchiveDto>(body.byteStream(), DecodeSequenceMode.ARRAY_WRAPPED)
                        .chunked(200).forEach { batch ->
                            context.ensureActive()
                            if (closed) throw CancellationException("Connection configuration changed")
                            val entries = batch.map(LanraragiArchiveDto::entry)
                            val batchStarted = System.nanoTime()
                            try {
                                consume(entries)
                            } finally {
                                consumeNanos += System.nanoTime() - batchStarted
                            }
                            count += entries.size
                        }
                }
            }
        } finally {
            if (BuildConfig.LANRARAGI_DIAGNOSTICS) {
                val elapsed = System.nanoTime() - started
                NetworkDiagnostics.record(
                    "LanraragiCatalog: connectionId=$diagnosticConnectionId event=archive-body " +
                        "entries=$count bytes=$bytes complete=$complete elapsedMs=${elapsed / 1_000_000} " +
                        "consumeMs=${consumeNanos / 1_000_000} " +
                        "requestDecodeMs=${(elapsed - consumeNanos) / 1_000_000}",
                )
            }
        }
    }

    suspend fun archive(id: String): LanraragiEntry = json.decodeFromJsonElement(
        LanraragiArchiveDto.serializer(),
        read(request("api/archives/$id/metadata")),
    ).entry()

    suspend fun pages(id: String): List<String> = read(request("api/archives/$id/files")).jsonObject["pages"]
        ?.jsonArray?.map { imageUrl(it.jsonPrimitive.content) }.orEmpty()

    suspend fun categories(catalogSync: Boolean = false): List<LanraragiEntry> =
        read(request("api/categories"), catalogSync).jsonArray.map { element ->
            val value = element.jsonObject
            LanraragiEntry(
                id = value.text("id"),
                kind = LanraragiEntry.Kind.CATEGORY,
                title = value.text("name"),
                search = value.text("search"),
                pinned = value.bool("pinned"),
                members = value.strings("archives"),
            )
        }

    suspend fun search(
        query: String = "",
        category: String? = null,
        catalogSync: Boolean = false,
    ): List<LanraragiEntry> {
        val pageSize = serverInfo(catalogSync = catalogSync).pageSize
        val result = linkedMapOf<String, LanraragiEntry>()
        var start = 0
        while (true) {
            val url = url("api/search").newBuilder().addQueryParameter("start", start.toString())
                .addQueryParameter("filter", query).addQueryParameter("groupby_tanks", "false")
                .addQueryParameter("sortby", "title").addQueryParameter("order", "asc")
                .apply { category?.let { addQueryParameter("category", it) } }.build()
            val value = read(Request.Builder().url(url).build(), catalogSync).jsonObject
            val batch = value["data"]?.jsonArray.orEmpty().map {
                json.decodeFromJsonElement(LanraragiArchiveDto.serializer(), it).entry()
            }
            val before = result.size
            batch.forEach { result[it.id] = it }
            if (batch.isNotEmpty() &&
                before == result.size
            ) {
                throw LanraragiException(LanraragiException.Reason.INCOMPLETE)
            }
            // Metadata can omit stale IDs; start addresses the server's result window, not the returned DTO count.
            start += pageSize
            val total = value["recordsFiltered"]?.jsonPrimitive?.intOrNull
            if (total != null && start >= total) break
            if (total == null && batch.size < pageSize) break
        }
        return result.values.toList()
    }

    suspend fun untagged(catalogSync: Boolean = false): Set<String> =
        read(request("api/archives/untagged"), catalogSync).jsonArray
            .mapTo(mutableSetOf()) { it.jsonPrimitive.content }

    suspend fun tanks(catalogSync: Boolean = false): List<LanraragiEntry> {
        val ids = linkedSetOf<String>()
        var page = 0
        while (true) {
            val value = read(
                Request.Builder().url(
                    url("api/tankoubons").newBuilder()
                        .addQueryParameter("page", page.toString()).build(),
                ).build(),
                catalogSync,
            ).jsonObject
            val batch = value["result"]?.jsonArray.orEmpty()
            if (batch.isEmpty()) break
            val before = ids.size
            batch.forEach { ids += it.jsonObject.text("id") }
            if (before == ids.size) throw LanraragiException(LanraragiException.Reason.INCOMPLETE)
            if (ids.size >= (value["total"]?.jsonPrimitive?.intOrNull ?: ids.size)) break
            page++
        }
        return ids.map { tank(it, catalogSync) }
    }

    suspend fun tank(id: String, catalogSync: Boolean = false): LanraragiEntry {
        val modern = serverInfo(catalogSync = catalogSync).modern
        val members = mutableListOf<String>()
        var page = 0
        var metadata: JsonObject
        while (true) {
            val url = url("api/tankoubons/$id" + if (modern) "/full" else "").newBuilder()
                .addQueryParameter("page", page.toString())
                .apply { if (!modern) addQueryParameter("include_full_data", "true") }.build()
            val value = read(Request.Builder().url(url).build(), catalogSync).jsonObject
            metadata = value["result"]?.jsonObject ?: throw LanraragiException(LanraragiException.Reason.UNAVAILABLE)
            val batch = metadata.strings("archives")
            members += batch
            if (members.size >= (value["total"]?.jsonPrimitive?.intOrNull ?: members.size)) break
            if (batch.isEmpty()) throw LanraragiException(LanraragiException.Reason.INCOMPLETE)
            page++
        }
        return LanraragiEntry(
            id,
            LanraragiEntry.Kind.TANK,
            metadata.text("name"),
            metadata.text("tags"),
            metadata.text("summary"),
            members = members.distinct(),
        )
    }

    suspend fun pushProgress(id: String, page: Int) {
        val info = serverInfo()
        if (!info.tracksProgress) throw LanraragiException(LanraragiException.Reason.PROGRESS_DISABLED)
        if (info.authenticatedProgress && apiKey.isBlank()) throw LanraragiException(LanraragiException.Reason.AUTH)
        read(Request.Builder().url(url("api/archives/$id/progress/$page")).put(ByteArray(0).toRequestBody()).build())
    }

    suspend fun clearNew(id: String) {
        read(Request.Builder().url(url("api/archives/$id/isnew")).delete().build())
    }

    companion object {
        fun normalizeBase(address: String): HttpUrl {
            val url = runCatching { address.trim().toHttpUrl() }.getOrNull()
                ?: throw LanraragiException(LanraragiException.Reason.ADDRESS)
            if (url.username.isNotBlank() || url.password.isNotBlank() || url.query != null || url.fragment != null) {
                throw LanraragiException(LanraragiException.Reason.ADDRESS)
            }
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
        }
    }
}

private fun JsonObject.text(key: String): String = this[key]?.takeUnless {
    it == JsonNull
}?.jsonPrimitive?.content.orEmpty()
private fun JsonObject.bool(key: String): Boolean =
    this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.content == "1") } ?: false
private fun JsonObject.strings(key: String): List<String> = this[key]?.takeUnless { it == JsonNull }?.jsonArray
    ?.map { it.jsonPrimitive.content }.orEmpty()
