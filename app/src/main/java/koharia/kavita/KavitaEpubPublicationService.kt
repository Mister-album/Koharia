package koharia.kavita

import android.app.Application
import eu.kanade.tachiyomi.network.await
import koharia.connection.ConnectionAddressRouter
import koharia.connection.SharedAppPreferences
import koharia.epub.cache.EpubCacheManager
import koharia.epub.model.EpubOpenRequest
import koharia.epub.service.installEpubXhtmlCompatibility
import koharia.epub.service.requireReadableEpub
import koharia.epub.service.withEpubPositionsController
import koharia.epub.session.EpubReaderSession
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.FileExtension
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.ResourceAsset
import org.readium.r2.shared.util.format.Format
import org.readium.r2.shared.util.format.FormatSpecification
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.HttpStatus
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource
import org.readium.r2.shared.util.resource.filename
import org.readium.r2.shared.util.resource.mediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.IOException

class KavitaEpubPublicationService(
    private val session: KavitaSource.Session,
    private val application: Application = Injekt.get(),
    private val cache: EpubCacheManager = Injekt.get(),
) {
    suspend fun open(request: EpubOpenRequest, initial: Locator?): EpubReaderSession {
        val ref = session.identity.chapter(requireNotNull(request.remotePublication).resourceId)
        require(ref.format == 3)
        session.catalog.requireLibrary(ref.libraryId)
        val info = session.catalog.book(ref.chapterId)
        require(info.pages > 0)
        val toc = session.catalog.toc(ref.chapterId)
        val base = pageUrl(session.api, ref.chapterId, 0).resolve("manifest.json")!!
        val manifest = JSONObject()
            .put("@context", "https://readium.org/webpub-manifest/context.jsonld")
            .put(
                "metadata",
                JSONObject().put("title", request.title).put("@type", "https://schema.org/Book")
                    .put("conformsTo", "https://readium.org/webpub-manifest/profiles/epub"),
            )
            .put(
                "readingOrder",
                JSONArray(
                    (0 until info.pages).map { index ->
                        JSONObject()
                            .put("href", pageUrl(session.api, ref.chapterId, index).toString())
                            .put("type", "text/html")
                    },
                ),
            )
            .put("toc", tocLinks(toc, session.api, ref.chapterId, info.pages))
        val http = Transport(session, request, cache)
        val asset = ResourceAsset(
            Format(
                FormatSpecification(Specification.Json, Specification.Rwpm),
                MediaType.READIUM_WEBPUB_MANIFEST,
                FileExtension("json"),
            ),
            StringResource(
                manifest.toString(),
                AbsoluteUrl(base.toString()),
                Resource.Properties {
                    mediaType = MediaType.READIUM_WEBPUB_MANIFEST
                    filename = "manifest.json"
                },
            ),
        )
        val parser =
            DefaultPublicationParser(application, http, AssetRetriever(application.contentResolver, http), null)
        val publication = PublicationOpener(parser).open(asset, allowUserInteraction = false, onCreatePublication = {
            installEpubXhtmlCompatibility()
        }).getOrElse {
            asset.close()
            throw IOException(it.message)
        }
        publication.requireReadableEpub("Kavita EPUB has no readable content")
        val positioned = publication.withEpubPositionsController()
        return EpubReaderSession(
            chapterId = request.chapterId,
            title = request.title,
            publication = positioned.publication,
            navigatorFactory = EpubNavigatorFactory(positioned.publication),
            initialLocator = initial,
            positionsController = positioned.controller,
        )
    }

    @OptIn(org.readium.r2.shared.InternalReadiumApi::class)
    private class Transport(
        private val session: KavitaSource.Session,
        private val open: EpubOpenRequest,
        private val cache: EpubCacheManager,
    ) : HttpClient {
        override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> = withContext(Dispatchers.IO) {
            try {
                session.checkActive()
                val url = request.url.toString().toHttpUrl()
                if (!ConnectionAddressRouter.owns(session.api.virtualBase, url)) {
                    throw IOException("External EPUB resource")
                }
                val ref = session.identity.chapter(requireNotNull(open.remotePublication).resourceId)
                val page = pageIndex(url.toString())
                val cached = cache.getResource(open.sourceId, open.publicationKey, url.toString())
                val bytes: ByteArray
                val type: String
                if (cached != null) {
                    bytes = cached.bytes
                    type = cached.mediaType ?: "application/octet-stream"
                } else if (page == null) {
                    if (Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly.get()) {
                        throw IOException(
                            "EPUB resource is not cached",
                        )
                    }
                    val resource = fetchKavitaEpubResource(session.api, ref.chapterId, url)
                    type = resource.mediaType
                    bytes =
                        if (type ==
                            "text/css"
                        ) {
                            rewriteKavitaCss(
                                resource.bytes.toString(Charsets.UTF_8),
                                session.api,
                                ref.chapterId,
                                resource.url,
                            ).toByteArray()
                        } else {
                            resource.bytes
                        }
                    session.checkActive()
                    if (open.persistCache) {
                        cache.putResource(
                            open.sourceId,
                            open.publicationKey,
                            url.toString(),
                            type,
                            bytes,
                        )
                    }
                } else {
                    if (Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly.get()) {
                        throw IOException(
                            "EPUB resource is not cached",
                        )
                    }
                    val target = session.api.request("Book/${ref.chapterId}/book-page", "page" to page)
                    session.api.client.newCall(target).await().use { response ->
                        KavitaApiClient.checkResponse(response)
                        val original = withContext(Dispatchers.IO) {
                            response.body.byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    session.checkActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size().toLong() + count >
                                        EpubCacheManager.MAX_RESOURCE_BYTES
                                    ) {
                                        throw IOException("EPUB resource too large")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        type = "text/html"
                        var html = original.toString(Charsets.UTF_8)
                        if (html.trimStart().startsWith('"')) html = session.api.json.decodeFromString<String>(html)
                        bytes = rewriteKavitaHtml(html, session.api, ref.chapterId).toByteArray()
                    }
                    session.checkActive()
                    if (open.persistCache) {
                        cache.putResource(
                            open.sourceId,
                            open.publicationKey,
                            url.toString(),
                            type,
                            bytes,
                        )
                    }
                }
                val range = org.readium.r2.shared.util.http.HttpHeaders(request.headers).range
                    ?.toLongRange(bytes.size.toLong())
                val start = range?.first?.toInt() ?: 0
                val end = range?.last?.plus(1)?.toInt() ?: bytes.size
                require(start in 0..bytes.size && end in start..bytes.size)
                val headers = mutableMapOf(
                    "Content-Length" to listOf((end - start).toString()),
                    "Accept-Ranges" to listOf("bytes"),
                )
                if (range != null) headers["Content-Range"] = listOf("bytes $start-${end - 1}/${bytes.size}")
                Try.success(
                    HttpStreamResponse(
                        HttpResponse(
                            request,
                            request.url,
                            HttpStatus(
                                if (range ==
                                    null
                                ) {
                                    200
                                } else {
                                    206
                                },
                            ),
                            headers,
                            MediaType(type),
                        ),
                        ByteArrayInputStream(bytes, start, end - start),
                    ),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Try.failure(HttpError.IO(error as? IOException ?: IOException(error)))
            }
        }
    }

    companion object {
        fun pageUrl(
            api: KavitaApiClient,
            chapterId: Long,
            page: Int,
        ): HttpUrl = api.url("Book/$chapterId/koharia-epub/page-$page.html")
        fun pageIndex(href: String): Int? = href.substringBefore('#').substringBefore('?').substringAfterLast('/')
            .removePrefix("page-").removeSuffix(".html").toIntOrNull()
        private fun tocLinks(
            parts: List<KavitaBookPart>,
            api: KavitaApiClient,
            chapterId: Long,
            pages: Int,
        ): JSONArray =
            JSONArray(
                parts.filter { it.page in 0 until pages }.map {
                    JSONObject().put("title", it.title).put("type", "text/html")
                        .put(
                            "href",
                            pageUrl(
                                api,
                                chapterId,
                                it.page,
                            ).newBuilder().fragment(it.part.takeIf(String::isNotBlank)).build().toString(),
                        )
                        .put("children", tocLinks(it.children, api, chapterId, pages))
                },
            )
    }
}

/** Keep Kavita's body wrapper and element order so server XPath anchors remain reversible. */
internal fun rewriteKavitaHtml(html: String, api: KavitaApiClient, chapterId: Long): String {
    val document = Jsoup.parseBodyFragment(html)
    val root = document.body().children().firstOrNull { it.tagName() != "style" } ?: document.body()
    document.body().addClass("reading-section")
    document.body().addClass("book-content")
    root.addClass("book-content")
    fun annotate(element: Element, path: String) {
        element.attr("data-koharia-kavita-path", path)
        if (element.id().isBlank()) element.id("koharia-" + path.encodeUtf8().sha256().hex().take(24))
        val indices = mutableMapOf<String, Int>()
        element.children().forEach { child ->
            val index = (indices[child.tagName()] ?: 0) + 1
            indices[child.tagName()] = index
            annotate(child, "$path/${child.tagName()}[$index]")
        }
    }
    annotate(root, "//body")
    document.select("img, image").forEachIndexed { index, element ->
        element.attr("data-koharia-kavita-image-index", index.toString())
    }
    fun remap(value: String): String {
        if (value.startsWith("data:", true) || value.startsWith('#')) return value
        val resolved = api.canonicalUrl(
            api.base.resolve(org.jsoup.parser.Parser.unescapeEntities(value, true)) ?: return "",
        )
        val expected = api.base.resolve("api/Book/$chapterId/book-resources")!!
        if (resolved.host != expected.host ||
            !resolved.encodedPath.equals(expected.encodedPath, true)
        ) {
            return if (value.startsWith("data:") ||
                value.startsWith('#')
            ) {
                value
            } else {
                ""
            }
        }
        return kavitaEpubResourceUrl(api, chapterId, resolved.queryParameter("file") ?: "").toString()
    }
    document.select("[src], [xlink:href], [poster], link[href], image[href]").forEach { element ->
        for (attribute in listOf("src", "xlink:href", "href", "poster")) {
            if (element.hasAttr(attribute)) element.attr(attribute, remap(element.attr(attribute)))
        }
    }
    document.select("[srcset]").forEach { element ->
        val candidates = element.attr("srcset").split(',').mapNotNull { candidate ->
            val parts = candidate.trim().split(Regex("\\s+"), limit = 2)
            val url = remap(parts.first()).takeIf(String::isNotBlank) ?: return@mapNotNull null
            url + parts.getOrNull(1)?.let { " $it" }.orEmpty()
        }
        if (candidates.isEmpty()) {
            element.removeAttr("srcset")
        } else {
            element.attr("srcset", candidates.joinToString(", "))
        }
    }
    document.select("a[kavita-page]").forEach { link ->
        link.attr("kavita-page").toIntOrNull()?.let { page ->
            link.attr(
                "href",
                KavitaEpubPublicationService.pageUrl(api, chapterId, page).newBuilder()
                    .fragment(link.attr("kavita-part").takeIf(String::isNotBlank)).build().toString(),
            )
        }
    }
    // Kavita has already inlined/scoped CSS. Replace its resource URLs before anything reaches disk.
    val urls = Regex("""url\(\s*(['"]?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)
    document.select("style, [style]").forEach { element ->
        fun rewrite(value: String) = urls.replace(value) { "url(\"${remap(it.groupValues[2])}\")" }
        if (element.tagName() == "style") element.html(rewrite(element.data()))
        if (element.hasAttr("style")) element.attr("style", rewrite(element.attr("style")))
    }
    document.select("svg").forEach {
        it.attr("xmlns", "http://www.w3.org/2000/svg").attr("xmlns:xlink", "http://www.w3.org/1999/xlink")
    }
    document.select("script, iframe, object, embed, base, meta[http-equiv]").remove()
    document.allElements.forEach { element ->
        element.attributes().asList().filter { it.key.startsWith("on", true) }.forEach { element.removeAttr(it.key) }
    }
    document.outputSettings().prettyPrint(false)
    return document.outerHtml()
}
