@file:Suppress("ktlint:standard:max-line-length")

package koharia.suwayomi

import eu.kanade.tachiyomi.network.await
import koharia.connection.ConnectionAddressRouter
import koharia.connection.ConnectionAddressVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.LogPriority
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SuwayomiApi(
    networkClient: OkHttpClient,
    private val json: Json,
    address: String,
    private val mode: SuwayomiAuthMode,
    private val username: String,
    private val password: String,
    internalAddress: String = "",
    private val router: ConnectionAddressRouter? = null,
    private val imageScope: String? = null,
) : SuwayomiService, AutoCloseable {
    internal val apiJson: Json get() = json
    val base = normalizeBase(address)
    private val internal = internalAddress.takeIf(String::isNotBlank)?.let(::normalizeBase)
    private val dispatcher = Dispatcher(networkClient.dispatcher.executorService)
    private val rawClient = networkClient.newBuilder().dispatcher(dispatcher).cache(null)
        .cookieJar(CookieJar.NO_COOKIES).dns(okhttp3.Dns.SYSTEM)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .apply {
            interceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
        }.build()
    private class Authentication {
        var revision = 0L
        var access: String? = null
        var refresh: String? = null
        var cookies: List<Cookie> = emptyList()
    }
    private val sessions = ConcurrentHashMap<HttpUrl, Authentication>()

    /**
     * Address the connection last authenticated against. A connection with a primary and a LAN
     * address routes HTTP to either one, so the websocket must carry that address's session.
     */
    @Volatile private var authenticatedEndpoint: HttpUrl? = null

    /**
     * Source ids whose preference screen the server has already built for this process. A change is
     * resolved against that screen, so the first write for a source has to read it first.
     */
    private val preferencesRead = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    @Volatile private var closed = false
    val client: OkHttpClient = rawClient.newBuilder().apply {
        if (router != null) addInterceptor(router)
        addInterceptor(
            Interceptor { chain ->
                if (closed) throw java.io.IOException("Suwayomi session is closed")
                val original = chain.request()
                val requestScope = original.url.queryParameter("kohariaScope")
                if (requestScope != null && requestScope != imageScope) {
                    throw SuwayomiException(SuwayomiException.Reason.ADDRESS)
                }
                val endpoint = endpoint(original.url)
                    ?: throw SuwayomiException(SuwayomiException.Reason.ADDRESS)
                val auth = sessions.getOrPut(endpoint, ::Authentication)
                authenticatedEndpoint = endpoint
                val request: Request
                val revision: Long
                synchronized(auth) {
                    if (auth.revision == 0L) refresh(endpoint, auth)
                    revision = auth.revision
                    request = authenticated(original, auth)
                }
                val response = chain.proceed(request)
                if (!unauthorized(response)) {
                    response
                } else {
                    response.close()
                    synchronized(auth) {
                        if (revision == auth.revision) refresh(endpoint, auth)
                    }
                    val retry = synchronized(auth) { authenticated(original, auth) }
                    chain.proceed(retry)
                }
            },
        )
    }.build()

    private fun endpoint(url: HttpUrl): HttpUrl? {
        val comparable = if (url.scheme == "ws" || url.scheme == "wss") {
            url.newBuilder().scheme(if (url.scheme == "wss") "https" else "http").build()
        } else {
            url
        }
        return internal?.takeIf { ConnectionAddressRouter.owns(it, comparable) }
            ?: base.takeIf { ConnectionAddressRouter.owns(it, comparable) }
    }

    private fun authenticated(request: Request, auth: Authentication): Request = request.newBuilder().apply {
        removeHeader("Authorization")
        removeHeader("Cookie")
        when (mode) {
            SuwayomiAuthMode.NONE -> Unit
            SuwayomiAuthMode.BASIC_AUTH -> header("Authorization", Credentials.basic(username, password))
            SuwayomiAuthMode.UI_LOGIN -> header("Authorization", "Bearer ${auth.access}")
            SuwayomiAuthMode.SIMPLE_LOGIN -> header(
                "Cookie",
                auth.cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(request.url) }
                    .joinToString("; ") { "${it.name}=${it.value}" },
            )
        }
    }.build()

    private fun refresh(endpoint: HttpUrl, auth: Authentication) {
        when (mode) {
            SuwayomiAuthMode.NONE, SuwayomiAuthMode.BASIC_AUTH -> Unit
            SuwayomiAuthMode.SIMPLE_LOGIN -> {
                val request = Request.Builder().url(checkNotNull(endpoint.resolve("login.html")))
                    .post(FormBody.Builder().add("user", username).add("pass", password).build()).build()
                rawClient.newCall(request).execute().use { response ->
                    val cookies = Cookie.parseAll(request.url, response.headers)
                    if (response.code !in setOf(302, 303) || cookies.isEmpty()) {
                        throw SuwayomiException(SuwayomiException.Reason.AUTH)
                    }
                    auth.cookies = cookies
                }
            }
            SuwayomiAuthMode.UI_LOGIN -> {
                val refreshed = auth.refresh?.let { token ->
                    runCatching {
                        loginOperation(
                            endpoint,
                            "mutation(\$token:String!){refreshToken(input:{refreshToken:\$token}){accessToken}}",
                            buildJsonObject { put("token", token) },
                            "refreshToken",
                        )
                    }.getOrNull()
                }
                if (refreshed != null) {
                    auth.access = refreshed["accessToken"]?.jsonPrimitive?.contentOrNull
                } else {
                    val login = loginOperation(
                        endpoint,
                        "mutation(\$user:String!,\$pass:String!){login(input:{username:\$user,password:\$pass}){accessToken refreshToken}}",
                        buildJsonObject {
                            put("user", username)
                            put("pass", password)
                        },
                        "login",
                    )
                    auth.access = login["accessToken"]?.jsonPrimitive?.contentOrNull
                    auth.refresh = login["refreshToken"]?.jsonPrimitive?.contentOrNull
                }
                if (auth.access.isNullOrBlank()) throw SuwayomiException(SuwayomiException.Reason.AUTH)
            }
        }
        auth.revision++
    }

    private fun loginOperation(base: HttpUrl, query: String, variables: JsonObject, field: String): JsonObject =
        rawClient.newCall(graphqlRequest(base, query, variables)).execute().use { response ->
            if (!response.isSuccessful) throw SuwayomiException(SuwayomiException.Reason.AUTH, response.code)
            val root = runCatching { json.parseToJsonElement(response.body.string()).jsonObject }.getOrNull()
                ?: throw SuwayomiException(SuwayomiException.Reason.AUTH)
            if (root["errors"]?.jsonArray?.isNotEmpty() == true) {
                throw SuwayomiException(SuwayomiException.Reason.AUTH)
            }
            root["data"]?.jsonObject?.get(field)?.takeUnless { it == JsonNull }?.jsonObject
                ?: throw SuwayomiException(SuwayomiException.Reason.AUTH)
        }

    private fun unauthorized(response: Response): Boolean {
        if (response.code == 401) return true
        if (!response.request.url.encodedPath.endsWith("/api/graphql")) return false
        return runCatching {
            val root = json.parseToJsonElement(response.peekBody(1024 * 1024).string()).jsonObject
            root["errors"]?.jsonArray?.any { error ->
                val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                message == "Unauthorized" || message.contains("UnauthorizedException")
            } == true
        }.getOrDefault(false)
    }

    private fun graphqlRequest(base: HttpUrl, query: String, variables: JsonObject) = Request.Builder()
        .url(checkNotNull(base.resolve("api/graphql")))
        .post(
            buildJsonObject {
                put("query", query)
                put("variables", variables)
            }.toString()
                .toRequestBody("application/json".toMediaType()),
        )
        .apply {
            if (!query.trimStart().startsWith("mutation")) {
                tag(ConnectionAddressRouter.ReadOnlyRequest::class.java, ConnectionAddressRouter.ReadOnlyRequest)
            }
        }.build()

    private suspend fun operation(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject =
        client.newCall(graphqlRequest(base, query, variables)).await().use { response ->
            when {
                response.code == 401 || response.code == 403 ->
                    throw SuwayomiException(SuwayomiException.Reason.AUTH, response.code)
                response.code == 404 -> throw SuwayomiException(SuwayomiException.Reason.NOT_FOUND)
                !response.isSuccessful -> throw SuwayomiException(SuwayomiException.Reason.SERVER)
            }
            val root = runCatching { json.parseToJsonElement(response.body.string()).jsonObject }.getOrElse {
                throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
            }
            if (root["errors"]?.jsonArray?.isNotEmpty() == true) {
                // Source-side failures arrive as GraphQL errors; keep the server message diagnosable.
                logcat(LogPriority.WARN) { "Suwayomi rejected an operation: ${root["errors"]}" }
                throw SuwayomiException(
                    if (unauthorizedRoot(root)) SuwayomiException.Reason.AUTH else SuwayomiException.Reason.SERVER,
                )
            }
            root["data"]?.jsonObject ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
        }

    private fun unauthorizedRoot(root: JsonObject) = root["errors"]?.jsonArray?.any {
        val message = it.jsonObject["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        message == "Unauthorized" || message.contains("UnauthorizedException")
    } == true

    private inline fun <reified T> decode(data: JsonObject, field: String): T {
        val value = data[field]?.takeUnless { it == JsonNull }
            ?: throw SuwayomiException(SuwayomiException.Reason.NOT_FOUND)
        return runCatching { json.decodeFromJsonElement<T>(value) }.getOrElse {
            logcat(LogPriority.WARN, it) { "Suwayomi decode failed for field '$field'" }
            throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
        }
    }

    suspend fun about(): String = operation("query{aboutServer{version}}")["aboutServer"]?.jsonObject
        ?.get("version")?.jsonPrimitive?.contentOrNull ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)

    override suspend fun categories(): List<SuwayomiCategory> =
        decode<SuwayomiNodes<SuwayomiCategory>>(
            operation("query{categories(order:{by:ORDER}){nodes{id name order}}}"),
            "categories",
        ).nodes

    override suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga> = decode(
        operation(
            "query(\$after:Cursor){mangas(condition:{inLibrary:true},first:100,after:\$after,order:{by:ID}){nodes{$MANGA_FIELDS} pageInfo{hasNextPage endCursor}}}",
            buildJsonObject { put("after", after?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull) },
        ),
        "mangas",
    )

    override suspend fun manga(id: Int): SuwayomiManga = decode(
        operation("query(\$id:Int!){manga(id:\$id){$MANGA_FIELDS}}", buildJsonObject { put("id", id) }),
        "manga",
    )

    override suspend fun chapters(mangaId: Int): List<SuwayomiChapter> = decode<SuwayomiNodes<SuwayomiChapter>>(
        operation(
            "query(\$id:Int!){chapters(condition:{mangaId:\$id},order:{by:SOURCE_ORDER,byType:DESC}){nodes{$CHAPTER_FIELDS}}}",
            buildJsonObject { put("id", mangaId) },
        ),
        "chapters",
    ).nodes

    override suspend fun chapter(id: Int): SuwayomiChapter = decode(
        operation("query(\$id:Int!){chapter(id:\$id){$CHAPTER_FIELDS}}", buildJsonObject { put("id", id) }),
        "chapter",
    )

    override suspend fun pages(chapterId: Int): SuwayomiPages = decode<SuwayomiPages>(
        pageOperation(chapterId),
        "fetchChapterPages",
    ).also { result ->
        if (result.chapter.id != chapterId || result.pages.isEmpty() || result.pages.size != result.chapter.pageCount) {
            throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
        }
        result.pages.forEach(::resourceUrl)
    }

    private suspend fun pageOperation(chapterId: Int): JsonObject = try {
        operation(
            "mutation(\$id:Int!){fetchChapterPages(input:{chapterId:\$id}){pages chapter{$CHAPTER_FIELDS} syncConflict{deviceName remotePage}}}",
            buildJsonObject { put("id", chapterId) },
        )
    } catch (error: SuwayomiException) {
        if (error.reason != SuwayomiException.Reason.SERVER) throw error
        // Diagnose from typed database fields, never expose raw GraphQL messages or source URLs.
        val remote = chapter(chapterId)
        val manga = operation(
            "query(\$id:Int!){manga(id:\$id){source{id}}}",
            buildJsonObject { put("id", remote.mangaId) },
        )["manga"]?.takeUnless { it == JsonNull }?.jsonObject
            ?: throw SuwayomiException(SuwayomiException.Reason.NOT_FOUND)
        if (!remote.isDownloaded && manga["source"] == JsonNull) {
            throw SuwayomiException(SuwayomiException.Reason.SOURCE)
        }
        throw SuwayomiException(SuwayomiException.Reason.PAGES)
    }

    override suspend fun updateChapter(id: Int, pageIndex: Int?, read: Boolean?): SuwayomiChapter {
        val patch = buildJsonObject {
            pageIndex?.let { put("lastPageRead", it) }
            read?.let { put("isRead", it) }
        }
        val result = operation(
            "mutation(\$id:Int!,\$patch:UpdateChapterPatchInput!){updateChapter(input:{id:\$id,patch:\$patch}){chapter{$CHAPTER_FIELDS}}}",
            buildJsonObject {
                put("id", id)
                put("patch", patch)
            },
        )["updateChapter"]?.jsonObject ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
        return decode<SuwayomiChapter>(result, "chapter").also {
            if (it.id != id || it.lastPageRead < 0 || it.pageCount < 0 ||
                (it.pageCount > 0 && it.lastPageRead >= it.pageCount)
            ) {
                throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
            }
        }
    }

    override suspend fun extensions(refresh: Boolean): List<SuwayomiExtension> {
        val data = if (refresh) {
            operation("mutation{fetchExtensions(input:{}){extensions{${EXTENSION_FIELDS}}}}")
        } else {
            operation("query{extensions{nodes{${EXTENSION_FIELDS}}}}")
        }
        val value = data[if (refresh) "fetchExtensions" else "extensions"] ?: JsonNull
        val list = if (refresh) {
            value.jsonObject["extensions"]?.jsonArray ?: JsonArray(emptyList())
        } else {
            value.jsonObject["nodes"]?.jsonArray ?: JsonArray(emptyList())
        }
        return list.map { json.decodeFromJsonElement<SuwayomiExtension>(it) }
    }

    suspend fun extensionAction(query: String, id: String): SuwayomiExtension? {
        val payload = operation(query, buildJsonObject { put("id", id) })["updateExtension"]?.jsonObject
            ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
        return payload["extension"]?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement(it) }
    }

    override suspend fun sources(): List<SuwayomiSourceInfo> {
        val value =
            operation(
                "query{sources(order:[{by:NAME,byType:ASC}]){nodes{$SOURCE_FIELDS}}}",
            )["sources"]
                ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
        return value.jsonObject["nodes"]?.jsonArray.orEmpty().map {
            json.decodeFromJsonElement<SuwayomiSourceInfo>(it)
        }
    }

    override suspend fun source(id: Long): SuwayomiSourceInfo = decode(
        operation(
            "query(\$id:LongString!){source(id:\$id){$SOURCE_FIELDS}}",
            buildJsonObject { put("id", id.toString()) },
        ),
        "source",
    )

    override suspend fun sourceFilters(id: Long): List<SuwayomiSourceFilter> {
        val data = operation(
            "query(\$id:LongString!){source(id:\$id){filters{$FILTER_FIELDS$NESTED_FILTER_FIELDS}}}",
            buildJsonObject { put("id", id.toString()) },
        )
        val value = data["source"]?.jsonObject?.get("filters") ?: JsonNull
        if (value == JsonNull) throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
        return value.jsonArray.map(::decodeFilter)
    }

    override suspend fun setSourceMeta(id: Long, key: String, value: String): SuwayomiSourceMeta {
        val payload = decode<JsonObject>(
            operation(
                "mutation(\$id:LongString!,\$key:String!,\$value:String!){setSourceMeta(input:{meta:{sourceId:\$id,key:\$key,value:\$value}}){meta{key value}}}",
                buildJsonObject {
                    put("id", id.toString())
                    put("key", key)
                    put("value", value)
                },
            ),
            "setSourceMeta",
        )
        return payload["meta"]?.let { json.decodeFromJsonElement<SuwayomiSourceMeta>(it) }
            ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
    }

    /** Per-series metadata lives on the manga node; the top-level `metas` query is global only. */
    override suspend fun mangaMeta(id: Int): List<SuwayomiMangaMeta> =
        manga(id).meta

    override suspend fun setMangaMeta(id: Int, key: String, value: String): SuwayomiMangaMeta {
        val payload = decode<JsonObject>(
            operation(
                "mutation(\$id:Int!,\$key:String!,\$value:String!){setMangaMeta(input:{meta:{mangaId:\$id,key:\$key,value:\$value}}){meta{key value}}}",
                buildJsonObject {
                    put("id", id)
                    put("key", key)
                    put("value", value)
                },
            ),
            "setMangaMeta",
        )
        return payload["meta"]?.let { json.decodeFromJsonElement<SuwayomiMangaMeta>(it) }
            ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
    }

    override suspend fun updateChapters(
        ids: List<Int>,
        read: Boolean?,
        lastPageRead: Int?,
    ): List<SuwayomiChapter> {
        if (ids.isEmpty()) return emptyList()
        val patch = buildJsonObject {
            read?.let { put("isRead", it) }
            lastPageRead?.let { put("lastPageRead", it) }
        }
        if (patch.isEmpty()) return emptyList()
        val payload = decode<JsonObject>(
            operation(
                "mutation(\$ids:[Int!]!,\$patch:UpdateChapterPatchInput!){updateChapters(input:{ids:\$ids,patch:\$patch}){chapters{$CHAPTER_FIELDS}}}",
                buildJsonObject {
                    putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
                    put("patch", patch)
                },
            ),
            "updateChapters",
        )
        return payload["chapters"]?.let { json.decodeFromJsonElement<List<SuwayomiChapter>>(it) }.orEmpty()
    }

    private fun decodeFilter(element: JsonElement): SuwayomiSourceFilter {
        val obj = element.jsonObject
        val name = obj["filterName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (obj["__typename"]?.jsonPrimitive?.contentOrNull) {
            "SelectFilter" -> SuwayomiSelectFilter(
                name = name,
                values = obj["values"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                default = obj["selectDefault"]?.jsonPrimitive?.intOrNull ?: 0,
            )
            "TextFilter" -> SuwayomiTextFilter(
                name = name,
                default = obj["textDefault"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "CheckBoxFilter" -> SuwayomiCheckBoxFilter(
                name = name,
                default = obj["checkDefault"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "TriStateFilter" -> SuwayomiTriStateFilter(
                name = name,
                default = obj["triDefault"]?.jsonPrimitive?.contentOrNull ?: "IGNORE",
            )
            "SortFilter" -> SuwayomiSortFilter(
                name = name,
                values = obj["values"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                default = obj["sortDefault"]?.takeUnless { it == JsonNull }?.jsonObject?.let { selection ->
                    SuwayomiSortSelection(
                        index = selection["index"]?.jsonPrimitive?.intOrNull ?: 0,
                        ascending = selection["ascending"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                },
            )
            "GroupFilter" -> SuwayomiGroupFilter(
                name = name,
                filters = obj["filters"]?.jsonArray.orEmpty().map(::decodeFilter),
            )
            "HeaderFilter" -> SuwayomiHeaderFilter(name)
            "SeparatorFilter" -> SuwayomiSeparatorFilter(name)
            else -> SuwayomiUnknownFilter(name)
        }
    }

    override suspend fun sourceMangaPage(sourceId: Long, after: String?, query: String?): SuwayomiNodes<SuwayomiManga> = decode(
        operation(
            "query(\$sourceId:LongString!,\$after:Cursor,\$title:String){mangas(condition:{sourceId:\$sourceId,title:\$title},first:50,after:\$after,order:{by:TITLE,byType:ASC}){nodes{$MANGA_FIELDS} pageInfo{hasNextPage endCursor}}}",
            buildJsonObject {
                put("sourceId", sourceId.toString())
                put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                put("title", query?.let(::JsonPrimitive) ?: JsonNull)
            },
        ),
        "mangas",
    )

    override suspend fun discoverSourceManga(
        sourceId: Long,
        page: Int,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange>,
    ): SuwayomiSourcePage {
        val result = decode<SuwayomiSourcePage>(
            operation(
                "mutation(\$source:LongString!,\$page:Int!,\$query:String,\$filters:[FilterChangeInput!],\$type:FetchSourceMangaType!){fetchSourceManga(input:{source:\$source,page:\$page,query:\$query,filters:\$filters,type:\$type}){hasNextPage mangas{$MANGA_FIELDS}}}",
                buildJsonObject {
                    put("source", sourceId.toString())
                    put("page", page)
                    put("query", query?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "filters",
                        if (filters.isEmpty()) {
                            JsonNull
                        } else {
                            JsonArray(filters.map { it.toJson() })
                        },
                    )
                    put("type", type.name)
                },
            ),
            "fetchSourceManga",
        )
        logcat(LogPriority.INFO) {
            "Suwayomi listing type=$type page=$page hasNext=${result.hasNextPage} items=${result.mangas.size}"
        }
        return result
    }

    /**
     * The settings a source extension exposes. The server identifies each one by its index in this
     * list, which is the position a change has to be applied at.
     */
    override suspend fun sourcePreferences(sourceId: Long): List<SuwayomiSourcePreference> {
        val payload = decode<JsonObject>(
            operation(
                "query(\$id:LongString!){source(id:\$id){preferences{$PREFERENCE_FIELDS}}}",
                buildJsonObject { put("id", sourceId.toString()) },
            ),
            "source",
        )
        preferencesRead += sourceId
        return payload["preferences"]?.jsonArray.orEmpty()
            .mapIndexedNotNull { position, element -> decodePreference(element, position) }
    }

    override suspend fun updateSourcePreference(
        sourceId: Long,
        position: Int,
        change: SuwayomiPreferenceChange,
    ): List<SuwayomiSourcePreference> {
        // The server resolves a change against the preference screen it built during a read, so a
        // write for a source this process has not read yet would fail on the server side.
        if (sourceId !in preferencesRead) sourcePreferences(sourceId)
        val payload = decode<JsonObject>(
            operation(
                "mutation(\$source:LongString!,\$change:SourcePreferenceChangeInput!){" +
                    "updateSourcePreference(input:{source:\$source,change:\$change}){" +
                    "preferences{$PREFERENCE_FIELDS}}}",
                buildJsonObject {
                    put("source", sourceId.toString())
                    put("change", change.toJson(position))
                },
            ),
            "updateSourcePreference",
        )
        return payload["preferences"]?.jsonArray.orEmpty()
            .mapIndexedNotNull { index, element -> decodePreference(element, index) }
    }

    /**
     * The preference union carries only `__typename` plus the members of that variant, and several
     * variants share field names with different types, so each one is read field by field.
     */
    private fun decodePreference(element: JsonElement, position: Int): SuwayomiSourcePreference? {
        val obj = element as? JsonObject ?: return null
        val key = obj.stringOrNull("prefKey")
        val title = obj.stringOrNull("prefTitle")
        val summary = obj.stringOrNull("prefSummary")
        val enabled = obj.booleanOrNull("prefEnabled") ?: true
        val visible = obj.booleanOrNull("prefVisible") ?: true
        return when (obj.stringOrNull("__typename")) {
            "SwitchPreference" -> SuwayomiSwitchPreference(
                position = position,
                key = key,
                title = title,
                summary = summary,
                enabled = enabled,
                visible = visible,
                currentValue = obj.booleanOrNull("switchState"),
                default = obj.booleanOrNull("switchDefault") ?: false,
            )
            "CheckBoxPreference" -> SuwayomiCheckBoxPreference(
                position = position,
                key = key,
                title = title,
                summary = summary,
                enabled = enabled,
                visible = visible,
                currentValue = obj.booleanOrNull("checkState"),
                default = obj.booleanOrNull("checkDefault") ?: false,
            )
            "EditTextPreference" -> SuwayomiEditTextPreference(
                position = position,
                key = key,
                title = title,
                summary = summary,
                enabled = enabled,
                visible = visible,
                currentValue = obj.stringOrNull("editState"),
                default = obj.stringOrNull("editDefault"),
                dialogTitle = obj.stringOrNull("editDialogTitle"),
                dialogMessage = obj.stringOrNull("editDialogMessage"),
                text = obj.stringOrNull("editText"),
            )
            "ListPreference" -> SuwayomiListPreference(
                position = position,
                key = key,
                title = title,
                summary = summary,
                enabled = enabled,
                visible = visible,
                currentValue = obj.stringOrNull("listState"),
                default = obj.stringOrNull("listDefault"),
                entries = obj.stringList("listEntries"),
                entryValues = obj.stringList("listEntryValues"),
            )
            "MultiSelectListPreference" -> SuwayomiMultiSelectPreference(
                position = position,
                key = key,
                title = title,
                summary = summary,
                enabled = enabled,
                visible = visible,
                currentValue = obj["multiState"]?.takeUnless { it == JsonNull }?.let(::stringList),
                default = obj["multiDefault"]?.takeUnless { it == JsonNull }?.let(::stringList),
                entries = obj.stringList("multiEntries"),
                entryValues = obj.stringList("multiEntryValues"),
                dialogTitle = obj.stringOrNull("multiDialogTitle"),
                dialogMessage = obj.stringOrNull("multiDialogMessage"),
            )
            else -> null
        }
    }

    private fun JsonObject.stringOrNull(field: String): String? =
        this[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.booleanOrNull(field: String): Boolean? =
        this[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.stringList(field: String): List<String> =
        this[field]?.takeUnless { it == JsonNull }?.let(::stringList).orEmpty()

    private fun stringList(element: JsonElement): List<String> =
        element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }

    /** Authenticated request for an icon or cover the server exposes as a plain resource. */
    fun resourceRequest(path: String): Request = Request.Builder().url(resourceUrl(path)).build()

    fun resourceCacheKey(path: String): String = "${imageScope.orEmpty()}:$base:${resourceUrlOrNull(path).orEmpty()}"

    suspend fun fetchSourceDetails(id: Int) {
        operation(
            "mutation(\$id:Int!){fetchMangaAndChapters(input:{id:\$id,fetchManga:true,fetchChapters:true}){manga{id} chapters{id}}}",
            buildJsonObject { put("id", id) },
        )
    }

    suspend fun updateMangaCategories(id: Int, add: Set<Int>, remove: Set<Int>) {
        operation(
            "mutation(\$id:Int!,\$add:[Int!]!,\$remove:[Int!]!){updateMangaCategories(input:{id:\$id,patch:{addToCategories:\$add,removeFromCategories:\$remove}}){manga{id}}}",
            buildJsonObject {
                put("id", id)
                put("add", JsonArray(add.map(::JsonPrimitive)))
                put("remove", JsonArray(remove.map(::JsonPrimitive)))
            },
        )
    }

    suspend fun setLibraryMembership(id: Int, inLibrary: Boolean) {
        operation(
            "mutation(\$id:Int!,\$inLibrary:Boolean!){updateManga(input:{id:\$id,patch:{inLibrary:\$inLibrary}}){manga{id inLibrary}}}",
            buildJsonObject {
                put("id", id)
                put("inLibrary", inLibrary)
            },
        )
    }

    override suspend fun downloadStatus(): SuwayomiDownloadStatus = decodeDownload(
        operation(
            "query{downloadStatus{state queue{state progress tries position chapter{id mangaId name} manga{id title}}}}",
        ),
    )

    override suspend fun enqueueDownloads(ids: List<Int>): SuwayomiDownloadStatus = mutateDownload(
        "mutation(\$ids:[Int!]!){enqueueChapterDownloads(input:{ids:\$ids}){downloadStatus{state queue{state progress tries position chapter{id mangaId name} manga{id title}}}}}",
        buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } },
        "enqueueChapterDownloads",
    )

    override suspend fun dequeueDownload(id: Int): SuwayomiDownloadStatus = mutateDownload(
        "mutation(\$id:Int!){dequeueChapterDownload(input:{id:\$id}){downloadStatus{state queue{state progress tries position chapter{id mangaId name} manga{id title}}}}}",
        buildJsonObject { put("id", id) },
        "dequeueChapterDownload",
    )

    override suspend fun startDownloader(): SuwayomiDownloadStatus = mutateDownload(
        "mutation{startDownloader(input:{}){downloadStatus{state queue{state progress tries position chapter{id mangaId name} manga{id title}}}}}",
        JsonObject(emptyMap()),
        "startDownloader",
    )

    override suspend fun stopDownloader(): SuwayomiDownloadStatus = mutateDownload(
        "mutation{stopDownloader(input:{}){downloadStatus{state queue{state progress tries position chapter{id mangaId name} manga{id title}}}}}",
        JsonObject(emptyMap()),
        "stopDownloader",
    )

    override suspend fun clearDownloader(): SuwayomiDownloadStatus = mutateDownload(
        "mutation{clearDownloader(input:{}){downloadStatus{state queue{state progress tries position chapter{id mangaId name} manga{id title}}}}}",
        JsonObject(emptyMap()),
        "clearDownloader",
    )

    override suspend fun reorderDownload(chapterId: Int, position: Int): SuwayomiDownloadStatus = mutateDownload(
        "mutation(\$chapterId:Int!,\$to:Int!){reorderChapterDownload(input:{chapterId:\$chapterId,to:\$to}){downloadStatus{state queue{state progress tries position chapter{id mangaId name} manga{id title}}}}}",
        buildJsonObject {
            put("chapterId", chapterId)
            put("to", position)
        },
        "reorderChapterDownload",
    )

    override suspend fun createCategory(name: String): SuwayomiCategory = decodeCategory(
        operation(
            "mutation(\$name:String!){createCategory(input:{name:\$name}){category{id name order}}}",
            buildJsonObject {
                put("name", name)
            },
        ),
        "createCategory",
    )

    override suspend fun updateCategory(id: Int, name: String): SuwayomiCategory = decodeCategory(
        operation(
            "mutation(\$id:Int!,\$name:String){updateCategory(input:{id:\$id,patch:{name:\$name}}){category{id name order}}}",
            buildJsonObject {
                put("id", id)
                put("name", name)
            },
        ),
        "updateCategory",
    )

    override suspend fun deleteCategory(id: Int): List<SuwayomiCategory> {
        operation(
            "mutation(\$id:Int!){deleteCategory(input:{categoryId:\$id}){category{id name order}}}",
            buildJsonObject {
                put("id", id)
            },
        )
        return categories()
    }

    private fun decodeDownload(data: JsonObject): SuwayomiDownloadStatus = decode(data, "downloadStatus")

    private suspend fun mutateDownload(query: String, variables: JsonObject, field: String): SuwayomiDownloadStatus =
        decodeDownload(
            operation(
                query,
                variables,
            )[field]?.jsonObject ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL),
        )

    private fun decodeCategory(data: JsonObject, field: String): SuwayomiCategory = decode(
        data[field]?.jsonObject ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL),
        "category",
    )

    fun resourceUrl(path: String): String {
        val absolute = path.toHttpUrlOrNull()
        val resolved = absolute ?: if (path.startsWith("/api/")) {
            base.resolve(path.removePrefix("/"))
        } else {
            base.resolve(path)
        } ?: throw SuwayomiException(SuwayomiException.Reason.ADDRESS)
        if (endpoint(resolved) == null || resolved.username.isNotEmpty() || resolved.password.isNotEmpty() ||
            resolved.queryParameterNames.any { it.lowercase() in SENSITIVE_PARAMETERS }
        ) {
            throw SuwayomiException(SuwayomiException.Reason.ADDRESS)
        }
        return (router?.canonicalUrl(resolved) ?: resolved).toString()
    }

    /**
     * Presentation URLs for cover rows may point at hosts this connection does not proxy. Those must
     * degrade to "no image" rather than escape composition, so only real requests stay strict.
     */
    fun resourceUrlOrNull(path: String): String? = runCatching { resourceUrl(path) }.getOrNull()

    /** Request used by the GraphQL subscription. Authentication is still attached by the
     * same interceptor as normal HTTP calls, so cookies and bearer tokens stay connection scoped. */
    fun authenticatedWebSocketRequest(): Request {
        val endpoint = authenticatedEndpoint ?: base
        val scheme = if (endpoint.isHttps) "wss" else "ws"
        val url = endpoint.resolve("api/graphql")!!.toString().replaceFirst("^[a-z]+".toRegex(), scheme)
        return Request.Builder().url(url).header("Sec-WebSocket-Protocol", "graphql-transport-ws").build()
    }

    /** The server authenticates websocket sessions from the connection_init payload. */
    suspend fun websocketAuthorization(): String? {
        when (mode) {
            SuwayomiAuthMode.NONE -> return null
            SuwayomiAuthMode.BASIC_AUTH -> return Credentials.basic(username, password)
            SuwayomiAuthMode.SIMPLE_LOGIN -> return null
            SuwayomiAuthMode.UI_LOGIN -> about()
        }
        val auth = sessions[authenticatedEndpoint ?: base] ?: return null
        // The subscription handler passes this payload value directly to Jwt.verifyJwt;
        // unlike the HTTP header it must not include the "Bearer " prefix.
        return synchronized(auth) { auth.access }
    }

    suspend fun validate() {
        if (!supportsVersion(about())) throw SuwayomiException(SuwayomiException.Reason.VERSION)
        categories()
        libraryPage(null)
        val schema =
            operation(
                "query{chapter:__type(name:\"ChapterType\"){fields{name}}}",
            )
        val fields = schema["chapter"]?.jsonObject?.get("fields")?.jsonArray.orEmpty()
            .map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        val patchSchema = operation("query{patch:__type(name:\"UpdateChapterPatchInput\"){inputFields{name}}}")
        val patch = patchSchema["patch"]?.jsonObject?.get("inputFields")?.jsonArray.orEmpty()
            .map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        val mutationSchema = operation("query{mutations:__type(name:\"Mutation\"){fields{name}}}")
        val mutations = mutationSchema["mutations"]?.jsonObject?.get("fields")?.jsonArray.orEmpty()
            .map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        if (!fields.containsAll(listOf("id", "mangaId", "lastPageRead", "lastReadAt", "pageCount")) ||
            !patch.containsAll(listOf("lastPageRead", "isRead")) ||
            !mutations.containsAll(listOf("fetchChapterPages", "updateChapter"))
        ) {
            throw SuwayomiException(SuwayomiException.Reason.VERSION)
        }
    }

    suspend fun verifyInternal() {
        val lan = internal ?: return
        if (base == lan) return
        val direct = SuwayomiApi(rawClient, json, lan.toString(), mode, username, password)
        val key = "koharia.connection.verify.${UUID.randomUUID()}"
        val nonce = UUID.randomUUID().toString()
        var attempted = false
        try {
            koharia.connection.ConnectionValidation.at(koharia.connection.ConnectionValidation.Endpoint.INTERNAL) {
                direct.validate()
            }
            withContext(NonCancellable) {
                attempted = true
                operation(
                    "mutation(\$key:String!,\$value:String!){setGlobalMeta(input:{meta:{key:\$key,value:\$value}}){meta{key value}}}",
                    buildJsonObject {
                        put("key", key)
                        put("value", nonce)
                    },
                )
            }
            if (direct.meta(key) != nonce) {
                throw ConnectionAddressVerification.Failure(ConnectionAddressVerification.Reason.MISMATCH)
            }
        } finally {
            if (attempted) {
                val cleaned = withContext(NonCancellable) {
                    runCatching {
                        withTimeout(20_000) {
                            var value = meta(key)
                            repeat(8) {
                                if (value == null) {
                                    delay(500)
                                    value = meta(key)
                                }
                            }
                            if (value != null && value != nonce) error("Verification marker changed")
                            operation(
                                "mutation(\$key:String!){deleteGlobalMeta(input:{key:\$key}){clientMutationId}}",
                                buildJsonObject { put("key", key) },
                            )
                            check(meta(key) == null)
                        }
                    }.isSuccess
                }
                direct.close()
                if (!cleaned) {
                    throw ConnectionAddressVerification.Failure(
                        ConnectionAddressVerification.Reason.CLEANUP,
                        key,
                    )
                }
            } else {
                direct.close()
            }
        }
    }

    private suspend fun meta(key: String): String? = operation(
        "query(\$key:String!){metas(condition:{key:\$key}){nodes{value}}}",
        buildJsonObject { put("key", key) },
    )["metas"]?.jsonObject?.get("nodes")?.jsonArray?.singleOrNull()?.jsonObject
        ?.get("value")?.jsonPrimitive?.contentOrNull

    override fun close() {
        closed = true
        dispatcher.cancelAll()
        sessions.clear()
        authenticatedEndpoint = null
    }

    companion object {
        const val MINIMUM_VERSION = "2.4.2366"
        const val PROBE_PATH = "api/graphql?query=%7BaboutServer%7Bversion%7D%7D"
        private const val MANGA_FIELDS = "id title sourceId author artist description genre status thumbnailUrl inLibrary inLibraryAt categories{nodes{id name order}} chapters{totalCount} unreadCount downloadCount bookmarkCount meta{key value} lastReadChapter{id}"
        private const val CHAPTER_FIELDS = "id mangaId name chapterNumber sourceOrder scanlator uploadDate isRead lastPageRead lastReadAt pageCount isDownloaded"
        private const val SOURCE_FIELDS =
            "id name lang iconUrl contentWarning supportsLatest isConfigurable " +
                "meta{key value} extension{isObsolete isInstalled}"

        // `Filter` is a union of types whose fields collide (`default` is Int on SelectFilter but
        // String on TextFilter), so every shared name needs a distinct alias and the `name` field
        // only exists inside the concrete fragments.
        private const val FILTER_FIELDS =
            "__typename ... on SelectFilter{filterName:name values selectDefault:default} " +
                "... on TextFilter{filterName:name textDefault:default} " +
                "... on CheckBoxFilter{filterName:name checkDefault:default} " +
                "... on TriStateFilter{filterName:name triDefault:default} " +
                "... on SortFilter{filterName:name values sortDefault:default{index ascending}} " +
                "... on HeaderFilter{filterName:name} ... on SeparatorFilter{filterName:name}"
        private const val NESTED_FILTER_FIELDS =
            "... on GroupFilter{filterName:name filters{$FILTER_FIELDS}}"
        private const val EXTENSION_FIELDS = "storeIndexUrl apkName iconUrl name pkgName apkUrl jarUrl extensionLib versionCodeLong versionName lang contentWarning isInstalled hasUpdate isObsolete"

        // `Preference` is a union, so every field has to be selected inside its own inline fragment
        // and aliased: several variants share a field name with a different type.
        private const val PREFERENCE_FIELDS =
            "__typename " +
                "... on SwitchPreference{prefKey:key prefTitle:title prefSummary:summary " +
                "prefVisible:visible prefEnabled:enabled switchState:currentValue switchDefault:default} " +
                "... on CheckBoxPreference{prefKey:key prefTitle:title prefSummary:summary " +
                "prefVisible:visible prefEnabled:enabled checkState:currentValue checkDefault:default} " +
                "... on EditTextPreference{prefKey:key prefTitle:title prefSummary:summary " +
                "prefVisible:visible prefEnabled:enabled editState:currentValue editDefault:default " +
                "editDialogTitle:dialogTitle editDialogMessage:dialogMessage editText:text} " +
                "... on ListPreference{prefKey:key prefTitle:title prefSummary:summary " +
                "prefVisible:visible prefEnabled:enabled listState:currentValue listDefault:default " +
                "listEntries:entries listEntryValues:entryValues} " +
                "... on MultiSelectListPreference{prefKey:key prefTitle:title prefSummary:summary " +
                "prefVisible:visible prefEnabled:enabled multiState:currentValue multiDefault:default " +
                "multiEntries:entries multiEntryValues:entryValues " +
                "multiDialogTitle:dialogTitle multiDialogMessage:dialogMessage}"
        private val SENSITIVE_PARAMETERS = setOf(
            "token",
            "accesstoken",
            "refreshtoken",
            "access_token",
            "refresh_token",
            "password",
            "api_key",
            "apikey",
        )
        fun normalizeBase(address: String): HttpUrl = ConnectionAddressRouter.normalize(address)
            ?: throw SuwayomiException(SuwayomiException.Reason.ADDRESS)
        fun supportsVersion(version: String): Boolean {
            val numbers = version.removePrefix("v").split('.').map { it.toIntOrNull() ?: return false }
            if (numbers.size != 3) return false
            val minimum = listOf(2, 4, 2366)
            for (index in numbers.indices) {
                if (numbers[index] != minimum[index]) return numbers[index] > minimum[index]
            }
            return true
        }
    }
}
