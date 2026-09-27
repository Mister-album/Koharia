package koharia.kavita

import com.sun.net.httpserver.HttpServer
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

class KavitaProtocolTest {
    private val requests = CopyOnWriteArrayList<URI>()
    private val clients = mutableListOf<KavitaApiClient>()
    private val repository = MemoryKavitaRepository()
    private var version = "0.9.1.4"
    private var responseCode = 200
    private var responseBody = """[{"id":1,"name":"Book","libraryId":2}]"""
    private var pagination = """{"currentPage":1,"totalPages":1,"totalCount":1}"""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                requests += exchange.requestURI
                exchange.requestBody.use { it.readBytes() }
                val auth = exchange.requestURI.path.endsWith("authenticate") ||
                    exchange.requestURI.path.endsWith("refresh-account")
                val body = if (auth && version == "0.8.0") {
                    val token = """{"nameid":"7","role":["Login","Download"]}""".encodeUtf8().base64Url()
                    """{"username":"fixture","token":"header.$token.signature","kavitaVersion":"$version"}"""
                } else if (auth) {
                    """{"id":7,"username":"fixture","token":"fixture-token","roles":["Download"],"kavitaVersion":"$version"}"""
                } else {
                    responseBody
                }
                if (!auth && pagination.isNotEmpty()) exchange.responseHeaders.add("Pagination", pagination)
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(if (auth) 200 else responseCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        start()
    }
    private fun api(principal: String = "fixture") = KavitaApiClient(
        okhttp3.OkHttpClient(),
        "http://127.0.0.1:${server.address.port}/reader/",
        "fixture-key",
        "fixture",
        principal,
    ).also { clients += it }
    private fun catalog(account: String = "a") = KavitaCatalog(1, account, repository, api()) {}

    @AfterEach fun close() {
        clients.forEach { it.close() }
        server.stop(0)
    }

    @Test fun internalRouteCoversAuthenticationAndCatalogWithoutChangingIdentity() = runTest {
        val public = "http://127.0.0.1:1/public/"
        val internal = "http://127.0.0.1:${server.address.port}/private/"
        val network = Any()
        val router = koharia.connection.ConnectionAddressRouter(
            { public },
            { internal },
            { network },
            "api/health",
            authenticateProbe = false,
        )
        val api = KavitaApiClient(okhttp3.OkHttpClient(), public, "fixture-key", "stable", router = router)
            .also { clients += it }
        assertEquals("fixture", api.getAccount().username)
        api.execute("Library/libraries")
        assertTrue(requests.any { it.path == "/private/api/Plugin/authenticate" })
        assertTrue(requests.any { it.path == "/private/api/Library/libraries" })
        assertTrue(requests.all { it.path.startsWith("/private/") })
        api.eventClient.newCall(api.eventRequest("hubs/messages")).execute().use { assertEquals(200, it.code) }
        assertTrue(requests.any { it.path == "/private/hubs/messages" })
        val html =
            rewriteKavitaHtml("<div><img src='${internal}api/Book/4/book-resources?file=cover.jpg'></div>", api, 4)
        assertTrue(html.contains("https://kavita.invalid/stable/api/Book/4/book-resources/resource.jpg"))
        assertEquals(public, api.base.toString())
        assertEquals("https://kavita.invalid/stable/", api.virtualBase.toString())
    }

    @Test fun opdsImportPreservesProxySubpath() {
        val parsed = KavitaEndpoint.parse("https://example.test/reader/api/opds/a%2Bb")
        assertEquals("https://example.test/reader/", parsed.base.toString())
        assertEquals("a+b", parsed.importedKey)
        assertThrows(KavitaException::class.java) { KavitaEndpoint.parse("https://user:pass@example.test/") }
        assertThrows(KavitaException::class.java) { KavitaEndpoint.parse("https://example.test/api/opds/key/extra") }
    }

    @Test fun annotationPagesPersistEmptyResultsAndRequireServerPagination() = runTest {
        responseBody = "[]"
        pagination = """{"currentPage":1,"pageSize":50,"totalCount":0,"totalPages":0}"""
        val catalog = catalog()
        val filter = kotlinx.serialization.json.JsonObject(emptyMap())
        assertTrue(catalog.annotationPage(1, filter).items.isEmpty())
        val count = requests.size
        responseCode = 503
        assertTrue(catalog.annotationPage(1, filter).items.isEmpty())
        assertEquals(count, requests.size)
        assertTrue(runCatching { catalog.annotationPage(1, filter, true) }.isFailure)
        assertTrue(catalog.annotationPage(1, filter).items.isEmpty())
        assertTrue(runCatching { catalog("another").annotationPage(1, filter) }.isFailure)
        responseCode = 200
        pagination = ""
        assertTrue(runCatching { catalog.annotationPage(2, filter) }.isFailure)
    }

    @Test fun explicitPermissionRevocationCannotReappearFromAnOfflineResourceCache() = runTest {
        responseBody = "[]"
        val catalog = catalog()
        catalog.resource("Reader/ptoc?chapterId=1")
        responseCode = 403
        assertTrue(runCatching { catalog.resource("Reader/ptoc?chapterId=1", true) }.isFailure)
        responseCode = 503
        assertTrue(runCatching { catalog.resource("Reader/ptoc?chapterId=1") }.isFailure)
    }

    @Test fun stableChapterIdentityRejectsOtherAccounts() {
        val identity = KavitaIdentity(1, "account-a")
        val ref = KavitaChapterRef(2, 3, 4, 5, 3)
        assertEquals(ref, identity.chapter(identity.chapter(ref)))
        assertThrows(IllegalArgumentException::class.java) {
            KavitaIdentity(1, "account-b").chapter(identity.chapter(ref))
        }
        assertNotEquals(identity.chapter(ref), KavitaIdentity(2, "account-a").chapter(ref))
    }

    @Test fun legacyAuthenticationKeepsKeysOutOfImageIdentities() = runTest {
        version = "0.8.0"
        val api = api()
        assertEquals("fixture", api.getAccount().principal)
        assertTrue(api.capabilities().downloads)
        assertFalse(api.page(1, 0).contains("fixture-key"))
        assertTrue(requests.all { it.path.startsWith("/reader/api/") })
        assertThrows(KavitaException::class.java) { api("another-account").authenticate() }
    }

    @Test fun concurrentAuthenticationIsSerialized() = runTest {
        val api = api()
        (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) { api.getAccount() } }.awaitAll()
        assertEquals(1, requests.count { it.path.endsWith("authenticate") })
    }

    @Test fun inheritedVerboseLoggingCannotExposeCredentialQueries() = runTest {
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger = okhttp3.logging.HttpLoggingInterceptor { messages += it }.apply {
            level = okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS
        }
        val network = okhttp3.OkHttpClient.Builder().addNetworkInterceptor(logger).build()
        KavitaApiClient(network, "http://127.0.0.1:${server.address.port}/reader/", "fixture-secret", "logging").use {
            it.getAccount()
        }
        assertTrue(messages.isEmpty())
    }

    @Test fun warmShelfAndValidEmptyCacheNeedNoNetwork() = runTest {
        val filter = KavitaFilter()
        val first = catalog().page(1, filter)
        val count = requests.size
        assertEquals(first, catalog().page(1, filter))
        assertEquals(count, requests.size)
        responseBody = "[]"
        val emptyFilter = KavitaFilter(listOf(KavitaFilterStatement(1, 5, "missing")))
        assertTrue(catalog().page(1, emptyFilter).items.isEmpty())
        val afterEmpty = requests.size
        assertTrue(catalog().page(1, emptyFilter).items.isEmpty())
        assertEquals(afterEmpty, requests.size)
    }

    @Test fun refreshFailureRetainsDataAndAccountsAreIsolated() = runTest {
        val first = catalog().page(1, KavitaFilter())
        responseBody = """[{"id":2,"name":"Second"}]"""
        assertEquals(2L, catalog("b").page(1, KavitaFilter()).items.single().id)
        responseCode = 500
        assertTrue(runCatching { catalog().refresh(KavitaFilter()) }.isFailure)
        assertEquals(first, catalog().page(1, KavitaFilter()))
    }

    @Test fun paginationMetadataControlsContinuation() = runTest {
        pagination = """{"currentPage":1,"totalPages":3,"totalCount":101}"""
        assertTrue(catalog().page(1, KavitaFilter()).hasNext)
        pagination = ""
        assertTrue(runCatching { catalog("other").page(1, KavitaFilter()) }.exceptionOrNull() is KavitaException)
    }

    @Test fun permissionDeniedDoesNotFallBackToLegacyEndpoint() = runTest {
        responseCode = 403
        val failure = runCatching { api().chapter(5) }.exceptionOrNull() as KavitaException
        assertEquals(KavitaException.Reason.PERMISSION, failure.reason)
        assertEquals(1, requests.count { it.path.endsWith("/Chapter") })
        assertFalse(requests.any { it.path.endsWith("/Series/chapter") })
    }

    @Test fun explicitUnreadSurvivesUnchangedBaselineButConflictsWithNewRemoteReading() {
        val ref = KavitaChapterRef(1, 2, 3, 4, 1)
        val baseline = KavitaProgress(pageNum = 10, lastModifiedUtc = "2026-01-01T00:00:00Z")
        val state = KavitaReadingState(ref, baseline.copy(pageNum = 0), 20, baseline, explicit = true)
        assertFalse(kavitaProgressConflict(state, baseline))
        assertTrue(kavitaProgressConflict(state, baseline.copy(pageNum = 12, lastModifiedUtc = "2026-01-02T00:00:00Z")))
    }

    @Test fun acknowledgementCannotEraseNewerOfflineRevision() = runTest {
        repository.putOperation(1, "a", KavitaOperation("progress/1", "new", 2, true))
        repository.acknowledge(1, "a", "progress/1", 1)
        assertTrue(repository.operations(1, "a").single().pending)
        repository.acknowledge(1, "a", "progress/1", 2)
        assertFalse(repository.operations(1, "a").single().pending)
    }

    @Test fun smartFiltersPreserveOrAndLimitAndRejectInexpressibleNarrowing() {
        val saved = KavitaFilter(
            listOf(KavitaFilterStatement(6, 0, "1"), KavitaFilterStatement(6, 0, "2")),
            combination = 0,
            limitTo = 5,
        )
        val base = KavitaFilter(listOf(KavitaFilterStatement(19, 0, "1")))
        assertEquals(saved, combineKavitaFilters(base, saved, true))
        assertThrows(KavitaException::class.java) { combineKavitaFilters(base, saved, false) }
        val and = combineKavitaFilters(base, saved.copy(combination = 1), false)
        assertEquals(3, and.statements.size)
        assertEquals(5, and.limitTo)
    }

    @Test fun invalidatedCacheSurvivesNetworkFailureButNotPermissionDenial() = runTest {
        val catalog = catalog()
        val expected = catalog.page(1, KavitaFilter())
        catalog.invalidate()
        responseCode = 503
        assertEquals(expected, catalog.page(1, KavitaFilter()))
        responseCode = 403
        val error = runCatching { catalog.page(1, KavitaFilter()) }.exceptionOrNull()
        assertEquals(KavitaException.Reason.PERMISSION, (error as? KavitaException)?.reason)
    }

    @Test fun libraryScopesSeparateBooksLightNovelsAndComics() {
        val scopes = koharia.connection.LibraryContentScope.entries
        assertEquals(
            listOf(2, 4),
            (0..5).filter {
                kavitaLibraryMatchesScope(it, koharia.connection.LibraryContentScope.BOOK)
            },
        )
        assertEquals(
            listOf(0, 1, 3, 5),
            (0..5).filter {
                kavitaLibraryMatchesScope(it, koharia.connection.LibraryContentScope.COMIC)
            },
        )
        assertTrue(scopes.isNotEmpty())
    }

    @Test fun stylesResolveNestedFontsImportsAndImagesWithoutCredentials() {
        val api = api()
        val css =
            rewriteKavitaCss(
                """@import "theme.css"; @font-face {src:url('../Fonts/title.ttf')} p {background:url('../Images/a.webp')}""",
                api,
                4,
                api.url(
                    "Book/4/book-resources",
                    "file" to "Styles/default.css",
                ),
            )
        assertTrue(css.contains("file=Styles%2Ftheme.css"))
        assertTrue(css.contains("file=Fonts%2Ftitle.ttf"))
        assertTrue(css.contains("file=Images%2Fa.webp"))
        assertTrue(css.contains("/resource.css?"))
        assertTrue(css.contains("/resource.webp?"))
        assertTrue(css.contains("/resource.ttf?"))
        responseBody = "body{color:red}"
        kotlinx.coroutines.runBlocking {
            val resource = fetchKavitaEpubResource(
                api,
                4,
                kavitaEpubResourceUrl(api, 4, "Styles/default.css"),
            )
            assertEquals("text/css", resource.mediaType)
        }
    }

    @Test fun svgAndHtmlImagesKeepTheirOwnAnchorsAndPublisherBodyClasses() {
        val api = api()
        val url = api.base.resolve("api/Book/4/book-resources?file=a.svg")!!
        val doc = org.jsoup.Jsoup.parse(
            rewriteKavitaHtml(
                """<div class="publisher"><img src="$url"><svg><image href="$url" /></svg></div>""",
                api,
                4,
            ),
        )
        assertTrue(doc.selectFirst("div.publisher")!!.hasClass("book-content"))
        assertEquals(2, doc.select("[data-koharia-kavita-image-index]").size)
        assertTrue(doc.selectFirst("image")!!.attr("href").contains("file=a.svg"))
        assertEquals("http://www.w3.org/1999/xlink", doc.selectFirst("svg")!!.attr("xmlns:xlink"))
    }

    @Test fun htmlConversionPreservesAnchorsAndRemovesCredentials() {
        val api = api()
        val resources = "//127.0.0.1:${server.address.port}/reader/api/Book/4/book-resources"
        val html = """
            <div><style>img {background:url('$resources?apiKey=secret&amp;file=cover.jpg')}</style>
            <p id="original">中文</p><p>Second</p><img src="$resources?apiKey=secret&amp;file=a.png">
            <a href="javascript:void(0)" kavita-page="2" kavita-part="original">Next</a></div>
        """.trimIndent()
        val result = rewriteKavitaHtml(html, api, 4)
        assertFalse(result.contains("apiKey"))
        assertFalse(result.contains("secret"))
        assertTrue(result.contains("id=\"original\""))
        assertTrue(result.contains("//body/p[2]"))
        assertTrue(result.contains("page-2.html#original"))
        assertTrue(result.contains("file=cover.jpg"))
    }

    @Test fun samePageCountFileReplacementInvalidatesOnlyItsPublication() = runTest {
        val changed = mutableListOf<Long>()
        val catalog = KavitaCatalog(1, "a", repository, api(), onChapterChanged = { changed += it }) {}
        responseBody =
            """{"id":4,"pages":10,"lastModifiedUtc":"2025-01-01","files":[{"id":9,"pages":10,"bytes":100}]}"""
        catalog.chapter(4)
        val original = catalog.contentVersion(4)
        responseBody = """{"pages":10}"""
        catalog.book(4)
        catalog.book(40)
        responseBody =
            """{"id":4,"pages":10,"lastModifiedUtc":"2025-01-02","files":[{"id":10,"pages":10,"bytes":200}]}"""
        catalog.chapter(4, true)
        assertEquals(listOf(4L), changed)
        assertNotEquals(original, catalog.contentVersion(4))
        assertTrue(repository.cache(1, "a", "book/4", "data")!!.stale)
        assertFalse(repository.cache(1, "a", "book/40", "data")!!.stale)
    }
}

internal class MemoryKavitaRepository : KavitaRepository {
    private val caches = mutableMapOf<List<Any>, KavitaCacheEntry>()
    private val states = mutableMapOf<List<Any>, KavitaOperation>()
    private val annotations = mutableMapOf<List<Any>, koharia.domain.kavita.KavitaAnnotationEntry>()
    override suspend fun cache(
        connectionId: Long,
        account: String,
        group: String,
        key: String,
    ) = caches[listOf(connectionId, account, group, key)]
    override suspend fun entries(connectionId: Long, account: String, group: String) =
        caches.filterKeys { it.take(3) == listOf(connectionId, account, group) }.values.toList()
    override suspend fun putCache(connectionId: Long, account: String, group: String, entry: KavitaCacheEntry) {
        caches[listOf(connectionId, account, group, entry.key)] = entry
    }
    override suspend fun replaceCache(
        connectionId: Long,
        account: String,
        group: String,
        entries: List<KavitaCacheEntry>,
    ) {
        caches.keys.removeAll { it.take(3) == listOf(connectionId, account, group) }
        entries.forEach { putCache(connectionId, account, group, it) }
    }
    override suspend fun invalidate(connectionId: Long, account: String) {
        caches.replaceAll { key, value ->
            if (key.take(2) ==
                listOf(connectionId, account)
            ) {
                value.copy(stale = true)
            } else {
                value
            }
        }
    }
    override suspend fun invalidateGroup(connectionId: Long, account: String, groupPrefix: String) {
        caches.replaceAll { key, value ->
            if (key.take(2) == listOf(connectionId, account) &&
                (key[2] as String).startsWith(groupPrefix)
            ) {
                value.copy(stale = true)
            } else {
                value
            }
        }
    }
    override suspend fun operations(connectionId: Long, account: String) =
        states.filterKeys { it.take(2) == listOf(connectionId, account) }.values.toList()
    override suspend fun putOperation(connectionId: Long, account: String, operation: KavitaOperation) {
        states[listOf(connectionId, account, operation.key)] = operation
    }
    override suspend fun acknowledge(connectionId: Long, account: String, key: String, revision: Long) {
        val id = listOf(connectionId, account, key)
        states[id]?.takeIf { it.revision == revision }?.let { states[id] = it.copy(pending = false) }
    }
    override suspend fun removeConnection(connectionId: Long) {
        caches.keys.removeAll { it.first() == connectionId }
        states.keys.removeAll { it.first() == connectionId }
        annotations.keys.removeAll { it.first() == connectionId }
    }
    override suspend fun annotations(connectionId: Long, account: String, chapterId: Long?) =
        annotations.filterKeys { it.take(2) == listOf(connectionId, account) }.values
            .filter { chapterId == null || it.chapterId == chapterId }
    override suspend fun putAnnotation(
        connectionId: Long,
        account: String,
        annotation: koharia.domain.kavita.KavitaAnnotationEntry,
    ) {
        annotations[listOf(connectionId, account, annotation.key)] = annotation
    }
}
