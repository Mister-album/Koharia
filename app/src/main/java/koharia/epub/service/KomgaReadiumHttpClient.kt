package koharia.epub.service

import koharia.epub.cache.EpubCacheManager
import koharia.epub.injectEpubParagraphIndentStyle
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.HttpStatus
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.mediatype.MediaType
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class KomgaReadiumHttpClient(
    private val sourceManager: SourceManager = Injekt.get(),
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get(),
    private val epubCacheManager: EpubCacheManager = Injekt.get(),
) {

    private val requestLogCount = AtomicInteger(0)

    suspend fun cachedResource(
        sourceId: Long,
        publicationKey: String,
        url: String,
    ): ByteArray? = epubCacheManager.getResource(sourceId, publicationKey, url)?.bytes

    fun create(
        sourceId: Long,
        publisherStylesOverride: Boolean? = null,
        publicationKey: String = "source:$sourceId",
        persistCache: Boolean = true,
    ): HttpClient {
        val client = DefaultHttpClient()
        client.callback = object : DefaultHttpClient.Callback {
            override suspend fun onStartRequest(request: HttpRequest) = Try.success(
                request.copy {
                    val source = sourceManager.get(sourceId) as? KomgaSource
                    val baseUrl = source?.baseUrl?.trimEnd('/').orEmpty()
                    val shouldInjectHeaders = request.url.toString().startsWith(baseUrl)
                    source?.currentReadiumHeaders()
                        .takeIf { shouldInjectHeaders }
                        ?.let { headers ->
                            headers.names().forEach { name ->
                                setHeader(name, headers.values(name))
                            }
                        }

                    if (requestLogCount.getAndIncrement() < 20) {
                        logcat(LogPriority.DEBUG) {
                            "EPUB http request url=${request.url} injectHeaders=$shouldInjectHeaders baseUrl=$baseUrl"
                        }
                    }
                },
            )
        }
        val cachedClient = EpubResourceCacheHttpClient(
            delegate = client,
            cacheManager = epubCacheManager,
            sourceId = sourceId,
            publicationKey = publicationKey,
            persistCache = persistCache,
        )
        return ParagraphIndentNormalizingHttpClient(cachedClient) {
            !(
                publisherStylesOverride
                    ?: scopedPreferenceStoreFactory.epubLayoutPreferences(sourceId).publisherStyles.get()
                )
        }
    }
}

private class EpubResourceCacheHttpClient(
    private val delegate: HttpClient,
    private val cacheManager: EpubCacheManager,
    private val sourceId: Long,
    private val publicationKey: String,
    private val persistCache: Boolean,
) : HttpClient {

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun stream(request: HttpRequest): org.readium.r2.shared.util.http.HttpTry<HttpStreamResponse> {
        if (!isCacheable(request)) return delegate.stream(request)

        cacheManager.getResource(sourceId, publicationKey, request.url.toString())?.let { cached ->
            return Try.success(
                HttpStreamResponse(
                    response = HttpResponse(
                        request = request,
                        url = request.url,
                        statusCode = HttpStatus(200),
                        headers = emptyMap(),
                        mediaType = cached.mediaType?.let { value -> MediaType(value) },
                    ),
                    body = ByteArrayInputStream(cached.bytes),
                ),
            )
        }

        return delegate.stream(request).map { response ->
            if (response.response.statusCode != HttpStatus(200) ||
                response.response.contentLength?.let { it > EpubCacheManager.MAX_RESOURCE_BYTES } == true
            ) {
                return@map response
            }
            val bytes = response.body.use { it.readBytes() }
            if (persistCache) {
                prefetchScope.launch {
                    cacheManager.putResource(
                        sourceId = sourceId,
                        publicationKey = publicationKey,
                        url = request.url.toString(),
                        mediaType = response.response.mediaType?.toString(),
                        bytes = bytes,
                    )
                    if (response.response.mediaType?.isHtml == true || isHtmlUrl(request.url.toString())) {
                        prefetchDependencies(
                            baseUrl = response.response.url.toString(),
                            content = bytes.toString(Charsets.UTF_8),
                        )
                    }
                }
            }
            HttpStreamResponse(response.response, ByteArrayInputStream(bytes))
        }
    }

    private fun isCacheable(request: HttpRequest): Boolean {
        if (!persistCache || request.method != HttpRequest.Method.GET) return false
        if (request.headers.keys.any { it.equals("Range", ignoreCase = true) }) return false
        val url = request.url.toString().substringBefore('?')
        return url.contains("/manifest/epub") ||
            url.endsWith("/positions") ||
            url.contains("/resource/")
    }

    private suspend fun prefetchDependencies(
        baseUrl: String,
        content: String,
    ) {
        val visited = Collections.synchronizedSet(mutableSetOf<String>())
        htmlDependency.findAll(content)
            .map { match -> match.groupValues[2] }
            .filter(::isFetchableReference)
            .take(MAX_PREFETCH_DEPENDENCIES)
            .forEach { reference ->
                prefetchDependency(baseUrl, reference, visited, parseCssDependencies = true)
            }
    }

    private suspend fun prefetchDependency(
        baseUrl: String,
        reference: String,
        visited: MutableSet<String>,
        parseCssDependencies: Boolean,
    ) {
        val resolved = runCatching { URI(baseUrl).resolve(reference).toString() }.getOrNull() ?: return
        if (!visited.add(resolved)) return
        val absoluteUrl = AbsoluteUrl(resolved) ?: return
        cacheManager.getResource(sourceId, publicationKey, resolved)?.let { return }
        delegate.stream(HttpRequest(absoluteUrl)).map { response ->
            if (response.response.statusCode != HttpStatus(200) ||
                response.response.contentLength?.let { it > EpubCacheManager.MAX_RESOURCE_BYTES } == true
            ) {
                response.body.close()
                return@map
            }
            val bytes = response.body.use { it.readBytes() }
            cacheManager.putResource(
                sourceId = sourceId,
                publicationKey = publicationKey,
                url = resolved,
                mediaType = response.response.mediaType?.toString(),
                bytes = bytes,
            )
            if (parseCssDependencies &&
                (
                    response.response.mediaType?.toString()?.startsWith("text/css") == true ||
                        resolved.substringBefore('?').endsWith(".css", ignoreCase = true)
                    )
            ) {
                cssDependency.findAll(bytes.toString(Charsets.UTF_8))
                    .map { match -> match.groupValues[2] }
                    .filter(::isFetchableReference)
                    .take(MAX_PREFETCH_DEPENDENCIES)
                    .forEach { cssReference ->
                        prefetchDependency(resolved, cssReference, visited, parseCssDependencies = false)
                    }
            }
        }
    }

    private fun isFetchableReference(reference: String): Boolean {
        val value = reference.trim()
        return value.isNotBlank() &&
            !value.startsWith("#") &&
            !value.startsWith("data:", ignoreCase = true) &&
            !value.startsWith("javascript:", ignoreCase = true)
    }

    private fun isHtmlUrl(url: String): Boolean {
        val path = url.substringBefore('?')
        return htmlExtensions.any { path.endsWith(it, ignoreCase = true) }
    }

    private companion object {
        const val MAX_PREFETCH_DEPENDENCIES = 64
        val htmlExtensions = listOf(".xhtml", ".html", ".htm")
        val htmlDependency = Regex(
            """(?:src|href)\s*=\s*([\"'])(.*?)\1""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val cssDependency = Regex(
            """url\(\s*([\"']?)(.*?)\1\s*\)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

private class ParagraphIndentNormalizingHttpClient(
    private val delegate: HttpClient,
    private val shouldNormalize: () -> Boolean,
) : HttpClient {

    override suspend fun stream(request: HttpRequest): org.readium.r2.shared.util.http.HttpTry<HttpStreamResponse> {
        return delegate.stream(request).map { streamResponse ->
            val path = request.url.toString().substringBefore('?')
            val isHtml = htmlExtensions.any { path.endsWith(it, ignoreCase = true) }
            val isFullGet = request.method == HttpRequest.Method.GET &&
                request.headers.keys.none { it.equals("Range", ignoreCase = true) }
            if (!isHtml || !isFullGet || !shouldNormalize()) {
                return@map streamResponse
            }

            val source = streamResponse.body.use { it.readBytes() }.toString(Charsets.UTF_8)
            val matches = leadingParagraphIndent.findAll(source).count()
            val paragraphCount = paragraphTag.findAll(source).count()
            val declaredTextIndentCount = declaredTextIndent.findAll(source).count()
            val normalized = leadingParagraphIndent
                .replace(source) { match -> match.groupValues[1] }
                .injectEpubParagraphIndentStyle()
            val normalizedBytes = normalized.toByteArray(Charsets.UTF_8)
            val normalizedResponse = streamResponse.response.copy(
                headers = streamResponse.response.headers
                    .filterKeys { !it.equals("Content-Length", ignoreCase = true) },
            )

            logcat(LogPriority.DEBUG) {
                "EPUB normalized paragraph indents url=${request.url} removedSpaces=$matches " +
                    "paragraphs=$paragraphCount declaredTextIndents=$declaredTextIndentCount injectedIndentStyle=true"
            }
            HttpStreamResponse(
                response = normalizedResponse,
                body = ByteArrayInputStream(normalizedBytes),
            )
        }
    }

    private companion object {
        val leadingParagraphIndent = Regex("""(<p\b[^>]*>)\u3000+""", RegexOption.IGNORE_CASE)
        val htmlExtensions = listOf(".xhtml", ".html", ".htm")
        val paragraphTag = Regex("""<p\b""", RegexOption.IGNORE_CASE)
        val declaredTextIndent = Regex("""text-indent\s*:""", RegexOption.IGNORE_CASE)
    }
}
